package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studykeeper.dto.ChatMessageDTO;
import studykeeper.entity.ChatMessage;
import studykeeper.mapper.ChatMessageMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天记录业务层：读历史（GET /api/chat/history）与落库（POST /api/ai/chat、/api/ai/chat/stream）。
 * <p>
 * 只有一张表 chat_message，只做两件事：「按用户查最近 N 条」与「插一条」。
 * 不做分页、不做删除、不做关键词搜索。
 * <p>
 * 事务：两个落库方法各自带 @Transactional（默认 REQUIRED），所以
 * <ul>
 *   <li>非流式：由 AiService.chat 的事务调用 → 加入那个事务，工具失败时连同对话记录一起回滚；</li>
 *   <li>流式：streamChat 本身没有事务（流要边写边推，不能把连接和事务绑 5 分钟），
 *       所以每调一次就是一个独立事务、各自提交。</li>
 * </ul>
 * 这里的方法必须由别的 Bean 调用（AiService → ChatService，走代理），同类自调用事务不生效。
 */
@Service
public class ChatService {

    /** 对外的时间格式：yyyy-MM-dd HH:mm:ss（docs/api.md §0.5）。 */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 历史条数默认值：GET /api/chat/history 不传 limit 时用 50。 */
    private static final int DEFAULT_LIMIT = 50;

    /** 历史条数上限：超过按参数非法处理（400），与 stats 的 days 越界口径一致。 */
    private static final int MAX_LIMIT = 200;

    /** chat_message.role 的两个取值。 */
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    /** AI 回复为空时的兜底文案：Python 侧已兜过一次，这里再兜一次，避免往 TEXT 列写空串。 */
    private static final String EMPTY_REPLY_FALLBACK = "（无回复）";

    private final ChatMessageMapper chatMessageMapper;

    public ChatService(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    /**
     * 取该用户最近 limit 条消息，按时间<b>正序</b>返回（最早的在前，最后一条是最新消息）。
     * <p>
     * 实现：先按 created_at 倒序 + id 倒序查出最近的 limit 条（created_at 是 DATETIME，没有小数秒，
     * 同一秒插入的 user / assistant 两条要靠 id 分先后），再在内存里反转成正序。
     * limit 为 null 时用默认值 50；不是 1-200 的数字时抛 IllegalArgumentException，由 Controller 转成 400。
     */
    public List<ChatMessageDTO> history(Long userId, Integer limit) {
        int size = normalizeLimit(limit);

        List<ChatMessage> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getUserId, userId)
                .orderByDesc(ChatMessage::getCreatedAt)
                .orderByDesc(ChatMessage::getId)
                // size 已限定在 1-200，且是 int，不存在注入风险
                .last("LIMIT " + size));

        List<ChatMessageDTO> result = new ArrayList<>(messages.size());
        for (int i = messages.size() - 1; i >= 0; i--) {
            result.add(toDTO(messages.get(i)));
        }
        return result;
    }

    /**
     * 落库一条用户消息。message 已在上层校验过非空，这里存 trim 后的值。
     * 自带事务：被流式链路（无外层事务）调用时是一条独立提交；被非流式 chat 调用时加入它的事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveUserMessage(Long userId, String message) {
        insert(userId, ROLE_USER, message);
    }

    /**
     * 落库一条 AI 回复。回复为空（模型只调工具、没说话）时存「（无回复）」，
     * 既避免前端渲染出空气泡，也避免往 TEXT 里写空串。事务口径同 saveUserMessage。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAssistantMessage(Long userId, String reply) {
        insert(userId, ROLE_ASSISTANT, reply == null || reply.isBlank() ? EMPTY_REPLY_FALLBACK : reply);
    }

    /**
     * 插一条消息。created_at 由应用显式赋值（表上虽然有 CURRENT_TIMESTAMP 默认值，但不依赖 DB 默认）。
     */
    private void insert(Long userId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(message);
    }

    /**
     * 实体 → DTO：LocalDateTime 按 yyyy-MM-dd HH:mm:ss 转成字符串。
     */
    private ChatMessageDTO toDTO(ChatMessage message) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setId(message.getId());
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt() == null ? null : message.getCreatedAt().format(DATETIME_FORMATTER));
        return dto;
    }

    /**
     * limit 规整：null 用默认值 50；不是 1-200 的数字抛 IllegalArgumentException。
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 只能是 1-" + MAX_LIMIT + " 的数字");
        }
        return limit;
    }

}
