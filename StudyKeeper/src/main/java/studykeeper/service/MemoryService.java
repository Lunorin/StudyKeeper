package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import studykeeper.dto.ExtractMemoryRequest;
import studykeeper.dto.ExtractMemoryResponse;
import studykeeper.dto.UserMemoryDTO;
import studykeeper.entity.UserMemory;
import studykeeper.mapper.UserMemoryMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 用户画像记忆业务层：调 Python /ai/extract-memory 提炼，再把提炼结果落到 user_memory。
 * <p>
 * 两类职责，互不干扰：
 * <ul>
 *   <li>写（自动）：{@link #extractAndSave(Long, String, String)} —— 每次聊天后异步提炼，失败只打日志；</li>
 *   <li>读写（人工）：{@link #list(Long)} / {@link #create(Long, UserMemoryDTO)} / {@link #delete(Long, Long)}
 *       —— 供 /api/memory 三个接口让用户自己查看、补充、删除记忆（不可编辑、不批量删）。</li>
 * </ul>
 * <p>
 * 调用时机：AiService.chat 落库完 chat_message 之后调 {@link #extractAndSave(Long, String, String)}。
 * 方法是 @Async（线程池 memoryExtractExecutor），立刻返回，不阻塞这轮聊天；
 * 提炼失败只打日志 —— 绝不影响聊天主流程，也不会回滚已经落库的对话（异步线程不在聊天事务里）。
 * <p>
 * 本步刻意不做：记忆合并 / 更新、遗忘 / 清理、向量检索 / 语义去重、失败重试、批量或定时提炼。
 * 去重只按「user_id + category + content 完全相同」判断，命中就跳过（不刷新 updated_at）。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** Python 侧接口路径：用户画像记忆提炼（只提炼，不落库） */
    private static final String EXTRACT_MEMORY_PATH = "/ai/extract-memory";

    /** 用户消息短于该长度就不提炼：「嗯」「好的」这类没有信息量 */
    private static final int MIN_MESSAGE_LENGTH = 5;

    /** 纯寒暄白名单：整句（去掉空白与标点、转小写后）只由这些词组成时跳过 */
    private static final List<String> GREETING_WORDS = List.of("你好", "在吗", "嗨", "哈喽", "hi", "hello");

    /** 记忆内容的列长上限（user_memory.content 是 VARCHAR(500)），超了截断 */
    private static final int MAX_CONTENT_LENGTH = 500;

    /** 记忆类别的列长上限（user_memory.category 是 VARCHAR(50)），超了丢弃 */
    private static final int MAX_CATEGORY_LENGTH = 50;

    /**
     * 去掉空白与标点，用于判断「整句是不是纯寒暄」。
     * 用 Unicode 标点属性 \p{IsPunctuation} 覆盖中英文标点（「在吗？」「hi!」），
     * 再补上 ASCII 空白、全角空格与波浪号（~ 是 Sm，不算标点）。
     */
    private static final String NOISE_PATTERN = "[\\s\\u3000\\p{IsPunctuation}~]";

    /** role 取值，与 chat_message / Python 侧一致 */
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    /** 记忆的 5 个分类（docs/api.md §0.6）：POST /api/memory 只接受这 5 个值 */
    private static final List<String> CATEGORIES =
            List.of("habit", "emotion", "event", "preference", "goal");

    /** GET /api/memory 的组间顺序：重要的在前（不在 5 类里的脏数据排在最后） */
    private static final List<String> CATEGORY_ORDER =
            List.of("event", "goal", "emotion", "habit", "preference");

    /** 未知分类的排序权重：排在 5 类之后 */
    private static final int UNKNOWN_CATEGORY_ORDER = CATEGORY_ORDER.size();

    /** 对外的时间格式：yyyy-MM-dd HH:mm:ss（docs/api.md §0.5） */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMemoryMapper userMemoryMapper;

    private final RestTemplate restTemplate;

    /** AI 服务地址，来自 application.yml 的 ai.service.base-url */
    private final String baseUrl;

    public MemoryService(UserMemoryMapper userMemoryMapper, RestTemplate aiRestTemplate,
                         @Value("${ai.service.base-url}") String baseUrl) {
        this.userMemoryMapper = userMemoryMapper;
        this.restTemplate = aiRestTemplate;
        // 去掉结尾的 "/"，避免拼出 http://host//ai/extract-memory
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 当前用户的全部记忆（GET /api/memory）。
     * <p>
     * 排序：按 category 分组，组间顺序固定 event &gt; goal &gt; emotion &gt; habit &gt; preference（重要的在前），
     * 组内按 updated_at 倒序（同一时间再按 id 倒序），保证结果稳定、前端不用再排。
     * 不在 5 类里的历史脏数据不隐藏（那是用户的数据），统一排在 5 类之后。
     * <p>
     * 不做分页：一个人的画像条数很少（一次提炼最多 5 条），全量返回更简单。
     */
    public List<UserMemoryDTO> list(Long userId) {
        List<UserMemory> memories = userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId));

        // 分类顺序是产品定的优先级，写在 SQL 里（FIELD()）反而更难读，所以在内存里排；
        // selectList 返回的是可变 List，直接 sort 即可（数据量小，无性能问题）
        memories.sort(Comparator
                .comparingInt((UserMemory memory) -> categoryOrder(memory.getCategory()))
                .thenComparing(UserMemory::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UserMemory::getId, Comparator.nullsLast(Comparator.reverseOrder())));

        List<UserMemoryDTO> result = new ArrayList<>(memories.size());
        for (UserMemory memory : memories) {
            result.add(toDTO(memory));
        }
        return result;
    }

    /**
     * 异步提炼并落库：把这一轮的「用户消息 + AI 回复」交给 Python 提炼，返回的记忆存进 user_memory。
     * <p>
     * 注意：@Async 方法必须由别的 Bean 调用（AiService 注入本 Bean 后调用），同类内部自调用不会异步。
     * 返回值是 void，调用方拿不到结果也不该等它；任何异常（网络失败 / 422 / 500 / 落库失败）
     * 都在方法内被吞掉，只写日志。
     */
    @Async("memoryExtractExecutor")
    public void extractAndSave(Long userId, String userMessage, String assistantReply) {
        try {
            // a. 规则过滤：太短或纯寒暄的话直接不提炼，省一次模型调用
            if (shouldSkip(userMessage)) {
                log.debug("[记忆提炼] 用户 {} 的消息被规则跳过，不提炼", userId);
                return;
            }

            // b. 组装请求：本步只放这一轮的两条消息（不做多轮 / 批量提炼）
            ExtractMemoryRequest request = new ExtractMemoryRequest();
            request.setMessages(List.of(
                    buildMessage(ROLE_USER, userMessage),
                    buildMessage(ROLE_ASSISTANT, assistantReply)));

            // c. 调 Python；响应缺少 memories 字段时按「没有可记的」处理
            ExtractMemoryResponse response =
                    restTemplate.postForObject(baseUrl + EXTRACT_MEMORY_PATH, jsonEntity(request),
                            ExtractMemoryResponse.class);
            if (response == null || response.getMemories() == null) {
                log.warn("[记忆提炼] 用户 {} 的提炼响应不合法：缺少 memories 字段", userId);
                return;
            }

            // d. 逐条落库：已存在（user_id + category + content 完全相同）就跳过
            int saved = 0;
            for (ExtractMemoryResponse.Memory memory : response.getMemories()) {
                if (saveIfAbsent(userId, memory)) {
                    saved++;
                }
            }

            // e. 统一记一条结果日志（新增 0 条也会打，方便确认「提炼成功但没有新信息」）
            log.info("[记忆提炼] 用户 {} 新增 {} 条", userId, saved);
        } catch (Exception e) {
            // 提炼是「附加功能」：失败只留日志，不往上层抛（异步线程抛出去也没人接，只会打堆栈）
            log.warn("[记忆提炼] 用户 {} 提炼失败：{}", userId, e.getMessage());
        }
    }

    /**
     * 规则过滤：这类消息不值得花一次模型调用。
     * <ul>
     *   <li>去掉首尾空白后长度 &lt; 5（「嗯」「好的」）</li>
     *   <li>整句只由打招呼的词组成（「你好」「在吗？」「hi」「hello」「你好你好」）</li>
     * </ul>
     */
    private boolean shouldSkip(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        if (text.length() < MIN_MESSAGE_LENGTH) {
            return true;
        }

        // 去掉空白与标点再判断：「在吗？」「hi!」也算纯寒暄；统一小写，避免 "HI" 漏掉
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll(NOISE_PATTERN, "");
        if (normalized.isEmpty()) {
            return true;
        }

        // 贪心地把整句拆成白名单里的词：能拆干净说明整句都是寒暄，有一点拆不动就不是
        String rest = normalized;
        while (!rest.isEmpty()) {
            String matched = null;
            for (String word : GREETING_WORDS) {
                if (rest.startsWith(word)) {
                    matched = word;
                    break;
                }
            }
            if (matched == null) {
                return false;
            }
            rest = rest.substring(matched.length());
        }
        return true;
    }

    /**
     * 落库一条记忆（不存在时）。category / content 的合法性在这里再兜一层，
     * 避免 Python 侧万一给了超长数据、insert 直接报错把整批记忆都带崩。
     *
     * @return 真的插入了返回 true；重复跳过 / 这条不合法返回 false
     */
    private boolean saveIfAbsent(Long userId, ExtractMemoryResponse.Memory memory) {
        if (memory == null) {
            return false;
        }

        String category = memory.getCategory() == null ? "" : memory.getCategory().trim();
        String content = memory.getContent() == null ? "" : memory.getContent().trim();
        if (category.isEmpty() || content.isEmpty()) {
            log.warn("[记忆提炼] 用户 {} 的一条记忆缺 category 或 content，已丢弃", userId);
            return false;
        }
        if (category.length() > MAX_CATEGORY_LENGTH) {
            // 类别只有 5 个短词，超长说明不是约定内的数据，直接丢
            log.warn("[记忆提炼] 用户 {} 的一条记忆 category 超长，已丢弃：{}", userId, category);
            return false;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            // Python 侧也会截断，这里再兜一次，避免 insert 时报 Data too long
            content = content.substring(0, MAX_CONTENT_LENGTH);
            log.warn("[记忆提炼] 用户 {} 的一条记忆 content 超过 {} 字，已截断", userId, MAX_CONTENT_LENGTH);
        }

        if (exists(userId, category, content)) {
            // 去重命中：跳过（刻意不刷新 updated_at，保留这条记忆「第一次记下来」的时间）
            return false;
        }

        UserMemory entity = new UserMemory();
        entity.setUserId(userId);
        entity.setCategory(category);
        entity.setContent(content);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userMemoryMapper.insert(entity);
        return true;
    }

    /**
     * 去重查询：user_id + category + content 完全相同就算已有。
     * 不做语义去重（「用户喜欢晨跑」与「用户爱早上跑步」会被当成两条）—— 那是后续步骤的事。
     */
    private boolean exists(Long userId, String category, String content) {
        Long count = userMemoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getCategory, category)
                .eq(UserMemory::getContent, content));
        return count != null && count > 0;
    }

    /**
     * 一条消息；content 为 null 时给空串（Python 侧不接受 null）。
     */
    private ExtractMemoryRequest.Message buildMessage(String role, String content) {
        ExtractMemoryRequest.Message message = new ExtractMemoryRequest.Message();
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        return message;
    }

    /**
     * JSON 请求实体：显式声明 Content-Type，让 Jackson 按 UTF-8 写出中文。
     * 与 AiService 里的同名方法一致（只有四行，暂不抽公共类）。
     */
    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /**
     * 新增一条记忆（POST /api/memory）：用户手动补一条画像。
     * <p>
     * category 必须是 5 类之一（否则抛 IllegalArgumentException → 400「无效的分类」），
     * content 去空白后不能为空、不能超过 500 字（与列长一致）。
     * <p>
     * 刻意不做「相同内容去重」：这是用户自己加的，加重复也照存；
     * 去重只发生在异步提炼那条路径上（见 saveIfAbsent），不在这里做画像合并。
     */
    public UserMemoryDTO create(Long userId, UserMemoryDTO request) {
        String category = requireCategory(request == null ? null : request.getCategory());
        String content = requireContent(request == null ? null : request.getContent());

        UserMemory entity = new UserMemory();
        entity.setUserId(userId);
        entity.setCategory(category);
        entity.setContent(content);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userMemoryMapper.insert(entity);
        return toDTO(entity);
    }

    /**
     * 删除一条记忆（DELETE /api/memory/{id}）。
     * 先 selectById 再比对 user_id：不存在或不属于当前用户都返回 false，
     * 由 Controller 统一转成 404「记忆不存在」（不区分「不存在」与「不是你的」，见 decisions §1.1）。
     */
    public boolean delete(Long userId, Long id) {
        UserMemory memory = userMemoryMapper.selectById(id);
        if (memory == null || !userId.equals(memory.getUserId())) {
            return false;
        }
        userMemoryMapper.deleteById(id);
        return true;
    }

    /**
     * 校验并规整 category：只接受 5 个约定值（大小写敏感，与库里存的值一致）。
     */
    private String requireCategory(String category) {
        String cleaned = category == null ? "" : category.trim();
        if (!CATEGORIES.contains(cleaned)) {
            // 文案按需求固定，前端可直接展示
            throw new IllegalArgumentException("无效的分类");
        }
        return cleaned;
    }

    /**
     * 校验并规整 content：去空白后不能为空，且不超过 500 字。
     */
    private String requireContent(String content) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (cleaned.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content 长度不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        return cleaned;
    }

    /**
     * 分类的排序权重：5 类按 CATEGORY_ORDER，未知分类（脏数据）排在最后。
     */
    private int categoryOrder(String category) {
        int index = CATEGORY_ORDER.indexOf(category);
        return index < 0 ? UNKNOWN_CATEGORY_ORDER : index;
    }

    /**
     * 实体 → DTO：时间按 yyyy-MM-dd HH:mm:ss 转成字符串。
     */
    private UserMemoryDTO toDTO(UserMemory memory) {
        UserMemoryDTO dto = new UserMemoryDTO();
        dto.setId(memory.getId());
        dto.setCategory(memory.getCategory());
        dto.setContent(memory.getContent());
        dto.setCreatedAt(formatDateTime(memory.getCreatedAt()));
        dto.setUpdatedAt(formatDateTime(memory.getUpdatedAt()));
        return dto;
    }

    private String formatDateTime(LocalDateTime time) {
        return time == null ? null : time.format(DATETIME_FORMATTER);
    }

}
