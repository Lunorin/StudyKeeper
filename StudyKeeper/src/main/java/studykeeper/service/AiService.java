package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import studykeeper.common.AiToolCallException;
import studykeeper.dto.AiChatRequest;
import studykeeper.dto.AiChatResponse;
import studykeeper.dto.AiChatResult;
import studykeeper.dto.AiParseCourseRequest;
import studykeeper.dto.ChatMessageDTO;
import studykeeper.dto.ChatStreamEvent;
import studykeeper.dto.AiParseCourseResponse;
import studykeeper.dto.AiPlanRequest;
import studykeeper.dto.AiPlanResponse;
import studykeeper.dto.CourseDTO;
import studykeeper.dto.RestTimeDTO;
import studykeeper.dto.TaskItem;
import studykeeper.entity.Task;
import studykeeper.entity.UserMemory;
import studykeeper.mapper.UserMemoryMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务（Python / FastAPI）调用层：/ai/plan（任务拆解）、/ai/chat（对话 + 执行工具调用）、
 * /ai/chat/stream（流式对话）与 /ai/parse-course（课程文本解析）。
 * <p>
 * plan：按 planDate 查出当天的课程与休息时间、查出当天已有任务 → 算出可用时段 → 连 goal / planDate 一起 POST 给 Python
 * → 把返回的 tasks 交给 Controller。
 * <p>
 * chat：查出今日任务、今日课程表、今日休息时段、今日空档（复用 /ai/plan 的算法）与用户画像记忆（user_memory，每类最多 3 条）当上下文、
 * 从 chat_message 查出该用户最近 20 条当历史（不再用前端传的 history）
 * → 连 message / history 一起 POST 给 Python → 拿回 reply 与 toolCalls → 按工具名分派到 TaskService
 * 真正改库 → 把这一轮的 user / assistant 两条消息落库 → 异步交给 MemoryService 提炼用户画像记忆
 * （失败只打日志，不影响本次回复）→ 返回 { reply, affectedTaskIds }；
 * 整个 chat 在一个事务里，任一工具失败或落库失败则前面几步的改动一起回滚。
 * <p>
 * complete_task（按关键词把今天的未完成任务批量标完成）比较特别：一条都没匹配到**不算失败**，
 * 但要落库 / 返回 {@link #COMPLETE_TASK_NOT_FOUND_REPLY} 这个提示，而不是让模型顺着用户说「都标完了」；
 * 匹配到了就用模型原本的 reply。其余工具（add / update / delete）的 reply 处理保持不变。
 * <p>
 * streamChat：把同一件事做成流式 —— 上下文与非流式完全一致，Python 换成 /ai/chat/stream，
 * 边收边把 delta 转发给前端；收尾时执行工具调用、落库回复，再把 tool_calls（含 affectedTaskIds）
 * 与 end 事件推给前端。这个方法是 SSE 的「生产者」，本身不加事务，落库走 ChatService 的独立事务。
 * <p>
 * parse-course：把原始课程文本 POST 给 Python → 原样返回解析结果（courses / failed）；
 * 只解析文本，不查库、不落库、不去重。
 * <p>
 * 失败约定：入参不合法抛 IllegalArgumentException（Controller → 400）；工具调用失败（未知工具 /
 * 缺必填参数 / 目标任务不存在）抛 AiToolCallException（Controller → 3002）；调用 AI 服务失败
 * （网络异常 / 超时 / 非 2xx / 响应体不合法）抛 RuntimeException（Controller → 3001）。
 * complete_task 一条都没匹配到不属于上面任何一档，正常返回。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** Python 侧接口路径：任务拆解 */
    private static final String PLAN_PATH = "/ai/plan";

    /** Python 侧接口路径：对话 + 工具调用意图（非流式） */
    private static final String CHAT_PATH = "/ai/chat";

    /** Python 侧接口路径：对话（SSE 流式） */
    private static final String CHAT_STREAM_PATH = "/ai/chat/stream";

    /** Python 侧接口路径：课程文本解析 */
    private static final String PARSE_COURSE_PATH = "/ai/parse-course";

    /** SSE 数据帧前缀（Python 侧发的是 "data: {json}\n\n"，解析时只认这个前缀） */
    private static final String SSE_DATA_PREFIX = "data:";

    /** 调用 AI 服务失败时给前端看的固定文案（真实原因只写日志） */
    private static final String AI_SERVICE_ERROR_MESSAGE = "AI 服务调用失败";

    /**
     * complete_task 按关键词一条都没匹配到时的回复模板（%s → 关键词）。
     * 这时模型的回复往往是「好的，都帮你标完了」，与库里的实际情况不符，所以整句换掉。
     */
    private static final String COMPLETE_TASK_NOT_FOUND_REPLY = "没找到与'%s'相关的未完成任务";

    /** 时间格式：HH:mm，与课程 / 休息模块保持一致 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 日期格式：yyyy-MM-dd */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 一天可用区间的起止：08:00 - 22:00（先写死，后面需要的话再做成配置） */
    private static final LocalTime DAY_START = LocalTime.of(8, 0);
    private static final LocalTime DAY_END = LocalTime.of(22, 0);

    /** 空档短于该时长（分钟）就丢掉，避免 AI 去填 10 分钟这种没法用的碎片 */
    private static final long MIN_SLOT_MINUTES = 30;

    /** 工具名，与 Python 侧 app/services/tools.py 的工具一一对应 */
    private static final String TOOL_ADD_TASK = "add_task";
    private static final String TOOL_UPDATE_TASK = "update_task";
    private static final String TOOL_DELETE_TASK = "delete_task";

    /** 按关键词把今天未完成的任务批量标完成（用户说「都背完了」这种，可能一次改多条） */
    private static final String TOOL_COMPLETE_TASK = "complete_task";

    /** 工具参数名 */
    private static final String ARG_TASK_ID = "taskId";
    private static final String ARG_TITLE = "title";
    private static final String ARG_START_TIME = "startTime";
    private static final String ARG_END_TIME = "endTime";
    private static final String ARG_PRIORITY = "priority";
    private static final String ARG_KEYWORD = "keyword";

    /** message 长度上限，与 Python 侧 MAX_MESSAGE_LENGTH 一致 */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** rawText 长度上限，与 Python 侧 MAX_RAW_TEXT_LENGTH 一致 */
    private static final int MAX_RAW_TEXT_LENGTH = 5000;

    /**
     * 从库里取多少条历史喂给模型：最近 20 条（正序，最早的在前）。
     * 必须 <= Python 侧 MAX_HISTORY（50），超了会被那边判 422。
     */
    private static final int RECENT_HISTORY_COUNT = 20;

    /** 传给 Python 的今日任务条数上限，与 Python 侧 MAX_CHAT_TASKS 一致 */
    private static final int MAX_CONTEXT_TASKS = 200;

    /** 传给 Python 的今日课程条数上限，与 Python 侧 MAX_CHAT_COURSES 一致 */
    private static final int MAX_CONTEXT_COURSES = 50;

    /** 传给 Python 的今日休息时段条数上限，与 Python 侧 MAX_CHAT_REST_TIMES 一致 */
    private static final int MAX_CONTEXT_REST_TIMES = 50;

    /**
     * 传给 Python 的用户画像记忆：每个 category 最多取几条。
     * 按 updated_at 倒序取最新的几条；类别只有 5 个，所以最多 15 条左右。
     */
    private static final int MAX_MEMORIES_PER_CATEGORY = 3;

    /** 工具参数里的时间：宽容一点，接受 "9:00" 与 "09:00"（模型不一定会补零） */
    private static final DateTimeFormatter TOOL_TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    private final CourseService courseService;

    private final RestTimeService restTimeService;

    private final TaskService taskService;

    /** 聊天记录（chat_message）：读历史 + 落库这一轮的对话 */
    private final ChatService chatService;

    /** 用户画像记忆（user_memory）：异步提炼，失败不影响聊天主流程 */
    private final MemoryService memoryService;

    /** 用户画像记忆（user_memory）：本类只读（查出来注入 chat 的 context），写由 MemoryService 负责 */
    private final UserMemoryMapper userMemoryMapper;

    private final RestTemplate restTemplate;

    /**
     * 流式链路要自己写 JSON 请求体、自己解析 SSE 帧，所以这里直接用 Jackson（Spring 容器里的那个）。
     */
    private final ObjectMapper objectMapper;

    /** AI 服务地址，来自 application.yml 的 ai.service.base-url */
    private final String baseUrl;

    public AiService(CourseService courseService, RestTimeService restTimeService, TaskService taskService,
                     ChatService chatService, MemoryService memoryService,
                     UserMemoryMapper userMemoryMapper, RestTemplate aiRestTemplate, ObjectMapper objectMapper,
                     @Value("${ai.service.base-url}") String baseUrl) {
        this.courseService = courseService;
        this.restTimeService = restTimeService;
        this.taskService = taskService;
        this.chatService = chatService;
        this.memoryService = memoryService;
        this.userMemoryMapper = userMemoryMapper;
        this.restTemplate = aiRestTemplate;
        this.objectMapper = objectMapper;
        // 去掉结尾的 "/"，避免拼出 http://host//ai/plan
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 把目标拆成带时间段的任务列表。
     * 可用时段按 planDate 当天的星期算（不是系统当前的星期），所以传明天的日期也能算对；
     * 已有任务取的是「今天」的（TaskService.todayTasks 固定用 LocalDate.now()）。
     */
    public List<TaskItem> plan(Long userId, String goal, String planDate) {
        // 1. 先挡掉不合法入参：Python 侧同样会判 422，在这里拦下可以少跑一次网络调用
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        LocalDate date = parsePlanDate(planDate);

        // 2. 课程按当天星期查（CourseService 支持 dayOfWeek 过滤）；休息时间查全部后在内存里筛；
        //    已有任务同样占用时间：用 TaskService 现查今天的任务（plan_date = 今天）
        int dayOfWeek = date.getDayOfWeek().getValue();
        List<CourseDTO> courses = courseService.list(userId, dayOfWeek);
        List<RestTimeDTO> restTimes = restTimeService.list(userId);
        List<Task> existingTasks = taskService.todayTasks(userId).getTasks();
        List<AiPlanRequest.AvailableSlot> availableSlots =
                calculateAvailableSlots(dayOfWeek, courses, restTimes, existingTasks);
        // TODO 临时调试日志：排查「AI 排任务未避开课程表」，定位完成后删除
        log.info("[调试] today dayOfWeek = {}", dayOfWeek);
        log.info("[调试] 课程列表 = {}", courses);
        log.info("[调试] 休息列表 = {}", restTimes);
        log.info("[调试] 已有任务列表 = {}", existingTasks);
        log.info("[调试] 算出 availableSlots = {}（含课程、休息、已有任务）", availableSlots);

        if (availableSlots.isEmpty()) {
            throw new IllegalArgumentException("planDate 当天没有可用的时间段（08:00-22:00 已被课程 / 休息 / 已有任务占满）");
        }

        // 3. 组装请求体并调用 Python
        AiPlanRequest request = new AiPlanRequest();
        request.setGoal(goal.trim());
        request.setPlanDate(date.format(DATE_FORMATTER));
        request.setAvailableSlots(availableSlots);

        AiPlanResponse response;
        try {
            response = restTemplate.postForObject(baseUrl + PLAN_PATH, jsonEntity(request), AiPlanResponse.class);
        } catch (RestClientException e) {
            // 真实原因只进日志，往上层只抛固定文案（与 Python 侧不外泄内部错误原因的做法一致）
            logAiFailure(PLAN_PATH, e);
            throw new RuntimeException("AI 服务调用失败", e);
        }

        if (response == null || response.getTasks() == null) {
            log.error("AI 服务返回格式不合法：缺少 tasks 字段");
            throw new RuntimeException("AI 服务调用失败");
        }
        return response.getTasks();
    }

    /**
     * 对话：把用户这句话（连同库里的历史、今日任务 / 课程表 / 休息时段）交给 Python，拿回回复与工具调用意图，
     * 再在本服务执行工具，最后把这一轮的 user / assistant 两条消息落库。
     * <p>
     * 历史不再由前端传：查的是 chat_message 里该用户最近 {@link #RECENT_HISTORY_COUNT} 条（正序），
     * 所以刷新页面 / 换设备后也能接着上一轮聊。
     * <p>
     * 整个方法在一个事务里（要求：多个 toolCalls 一起成功或一起失败，并且对话记录要么成对落库、要么都不落）。
     * 注意 Python 调用也在事务内，最坏情况会占住一个数据库连接 ai.service.timeout 毫秒。
     */
    @Transactional(rollbackFor = Exception.class)
    public AiChatResult chat(Long userId, String message) {
        // 1. 先挡掉不合法入参：Python 侧同样会判 422，在这里拦下可以少跑一次网络调用
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 长度不能超过 " + MAX_MESSAGE_LENGTH + " 字");
        }
        String content = message.trim();

        // 2. 组装请求体：history 从库里查（前端传什么都不看），context（今日任务 / 课程表 / 休息时段）现查
        AiChatRequest request = new AiChatRequest();
        request.setMessage(content);
        request.setHistory(buildHistory(userId));
        request.setContext(buildContext(userId));

        AiChatResponse response;
        try {
            response = restTemplate.postForObject(baseUrl + CHAT_PATH, jsonEntity(request), AiChatResponse.class);
        } catch (RestClientException e) {
            // 真实原因只进日志，往上层只抛固定文案（与 Python 侧不外泄内部错误原因的做法一致）
            logAiFailure(CHAT_PATH, e);
            throw new RuntimeException("AI 服务调用失败", e);
        }
        if (response == null) {
            log.error("AI 服务返回格式不合法：响应体为空");
            throw new RuntimeException("AI 服务调用失败");
        }

        // 3. 执行工具调用（任一失败都会抛异常，让整个事务回滚）
        List<AiChatResponse.ToolCall> toolCalls = response.getToolCalls() == null
                ? List.of() : response.getToolCalls();
        ToolExecution execution = executeToolCalls(userId, toolCalls);

        // 4. 落库：这一轮的「用户消息 + AI 回复」，与上面的工具改动同事务，任一处失败一起回滚
        String reply = response.getReply() == null ? "" : response.getReply();
        // complete_task 一条都没匹配到：返回给前端与落库的都换成提示，别让模型顺着用户说「都标完了」
        if (execution.completeTaskMissedKeyword() != null) {
            reply = notFoundReply(execution.completeTaskMissedKeyword());
        }
        chatService.saveUserMessage(userId, content);
        // 回复为空时由 ChatService 存「（无回复）」兜底；其余情况落库与返回给前端的是同一句
        chatService.saveAssistantMessage(userId, reply);

        // 5. 异步提炼用户画像记忆：@Async 方法立刻返回，不阻塞本次响应、也不在当前事务里；
        //    里面自己吞异常（失败只打日志），所以放事务内调用也不会把聊天事务带崩
        memoryService.extractAndSave(userId, content, reply);

        AiChatResult result = new AiChatResult();
        result.setReply(reply);
        result.setAffectedTaskIds(execution.affectedTaskIds());
        return result;
    }

    /**
     * 流式对话（POST /api/ai/chat/stream）：把 Python 吐出来的正文一段段转发给前端，
     * 流结束后再执行工具调用、落库回复，并把 tool_calls / end 事件补齐。
     * <p>
     * 与非流式 chat 的差别：
     * <ul>
     *   <li><b>不加 @Transactional</b>：一条 SSE 连接可能持续几十秒，不能一直占着数据库连接。
     *       「落库 user 消息」「落库 assistant 消息」各走 ChatService 里带 @Transactional 的方法，
     *       在流式链路里它们就是两个独立事务（各自提交）；</li>
     *   <li>工具调用不立刻执行：Python 把意图攒成一条 tool_calls 事件发过来，Java 先存着，
     *       等 end 到了才执行，执行结果（affectedTaskIds）随 tool_calls 事件一起推给前端；</li>
     *   <li>出错时 HTTP 状态码已经发出去了（200），只能再补一条 error 事件。</li>
     * </ul>
     * 本方法由 AiController 提交到 chatStreamExecutor 线程池执行；它自己负责关闭 emitter，不向上抛异常。
     *
     * @param emitter SSE 通道；方法返回时它一定已经被 complete（或客户端断开而失效）
     */
    public void streamChat(Long userId, String message, SseEmitter emitter) {
        // 本轮攒下来的工具调用意图：Python 在 end 之前单独发一条 tool_calls 事件
        List<AiChatResponse.ToolCall> pendingToolCalls = new ArrayList<>();
        try {
            // a. 校验入参 + 组装上下文（与非流式 chat 同一套：今日任务 / 课程表 / 休息时段 / 画像记忆）
            String content = requireMessage(message);
            AiChatRequest request = new AiChatRequest();
            request.setMessage(content);
            // 与非流式完全一致：history 由 Java 查库后传给 Python（两条链路都不看前端传的历史）
            request.setHistory(buildHistory(userId));
            request.setContext(buildContext(userId));

            // b. 落库 user 消息（独立事务）：先落库再开流，后面模型 / 工具失败也不会让这句话消失
            chatService.saveUserMessage(userId, content);

            // c. 流式调 Python：逐行读 SSE，读到一条处理一条；返回「本轮是否正常收尾（收到 end / error）」
            Boolean finished = restTemplate.execute(baseUrl + CHAT_STREAM_PATH, HttpMethod.POST,
                    streamRequestBody(request),
                    response -> readSseStream(response.getBody(), userId, pendingToolCalls, emitter));
            if (!Boolean.TRUE.equals(finished)) {
                // Python 没发 end 就把连接关了（服务端异常 / 超时断流）：补一条 error 并关流，
                // 否则前端会一直挂到 5 分钟超时
                log.warn("流式对话没有收到 end 事件（连接被提前关闭），已补 error 事件收尾");
                sendErrorAndComplete(emitter, AI_SERVICE_ERROR_MESSAGE);
            }
        } catch (IllegalArgumentException e) {
            // 入参不合法：非流式接口这里返回 400，SSE 只能发一条 error 事件
            log.warn("流式对话入参不合法：{}", e.getMessage());
            sendErrorAndComplete(emitter, e.getMessage());
        } catch (AiToolCallException e) {
            // 工具没执行成功（对应非流式的 3002）：正文已经推完了，补一条 error 让前端提示
            log.warn("流式对话工具调用失败：{}", e.getMessage());
            sendErrorAndComplete(emitter, e.getMessage());
        } catch (Exception e) {
            // 调用 AI 服务失败 / 读流失败等：真实原因只进日志（与非流式 3001 的做法一致）
            logAiFailure(CHAT_STREAM_PATH, e);
            sendErrorAndComplete(emitter, AI_SERVICE_ERROR_MESSAGE);
        }
    }

    /**
     * 校验 message：不能为空、不超过 2000 字（与非流式 chat 同一口径），返回 trim 后的值。
     */
    private String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 长度不能超过 " + MAX_MESSAGE_LENGTH + " 字");
        }
        return message.trim();
    }

    /**
     * 处理一条从 Python 收到的 SSE 事件。
     * <p>
     * start：不推送（Python 目前也不发，保留分支）；delta：原样转发；
     * tool_calls：先攒着，等 end 到了、Java 真正执行完再带着 affectedTaskIds 推给前端；
     * end：收尾（执行工具 → 落库 → 推事件 → 关流）；error：转发 error 并关流。
     *
     * @return true 表示这条事件之后本轮已经结束（end / error），不用再往下读了
     */
    private boolean handleStreamEvent(Long userId, ChatStreamEvent event,
                                      List<AiChatResponse.ToolCall> pendingToolCalls, SseEmitter emitter) {
        String type = event.getType() == null ? "" : event.getType().trim();
        switch (type) {
            case ChatStreamEvent.TYPE_START:
                log.debug("流式事件 start：不推送");
                return false;
            case ChatStreamEvent.TYPE_DELTA:
                sendEvent(emitter, event);
                return false;
            case ChatStreamEvent.TYPE_TOOL_CALLS:
                if (event.getToolCalls() != null) {
                    pendingToolCalls.addAll(event.getToolCalls());
                }
                return false;
            case ChatStreamEvent.TYPE_END:
                handleStreamEnd(userId, event, pendingToolCalls, emitter);
                return true;
            case ChatStreamEvent.TYPE_ERROR:
                log.warn("AI 服务流式返回错误：{}", event.getMessage());
                sendEvent(emitter, event);
                completeQuietly(emitter);
                return true;
            default:
                log.warn("未知的流式事件类型，已跳过：{}", type);
                return false;
        }
    }

    /**
     * 流结束（end 事件）后的收尾，按这个顺序做：
     * 执行工具调用 → （complete_task 没匹配到时换掉回复）→ 落库 assistant 消息 →
     * 推 tool_calls（带 affectedTaskIds）→ 推 end → 关流。
     * <p>
     * 这样排的好处：前端收到 tool_calls 时任务其实已经改好了，可以直接刷新任务列表。
     * 没有工具调用就不推这条事件（与 Python 侧「没有就不发」保持一致）。
     * 工具执行失败会抛 AiToolCallException，由 streamChat 统一转成一条 error 事件。
     */
    private void handleStreamEnd(Long userId, ChatStreamEvent endEvent,
                                 List<AiChatResponse.ToolCall> pendingToolCalls, SseEmitter emitter) {
        // 1. 执行工具调用（复用非流式 chat 的那套分派逻辑）
        ToolExecution execution = executeToolCalls(userId, pendingToolCalls);

        // 2. complete_task 一条都没匹配到：把回复换成提示，落库与推给前端的都是这句。
        //    只在真的没匹配到时才动 end 事件，其余情况它保持 Python 传来的样子（reply 是 null 就还是 null）。
        if (execution.completeTaskMissedKeyword() != null) {
            endEvent.setReply(notFoundReply(execution.completeTaskMissedKeyword()));
        }

        // 3. 落库 assistant 消息（独立事务；回复为空时 ChatService 存「（无回复）」）
        String reply = endEvent.getReply() == null ? "" : endEvent.getReply();
        chatService.saveAssistantMessage(userId, reply);

        // 4. 真有工具调用才补发 tool_calls：内容与 Python 给的一致，额外带上真正改到的任务 id
        if (!pendingToolCalls.isEmpty()) {
            ChatStreamEvent toolCallsEvent = new ChatStreamEvent();
            toolCallsEvent.setType(ChatStreamEvent.TYPE_TOOL_CALLS);
            toolCallsEvent.setToolCalls(pendingToolCalls);
            toolCallsEvent.setAffectedTaskIds(execution.affectedTaskIds());
            sendEvent(emitter, toolCallsEvent);
        }

        // 5. end 转发（reply 已按第 2 步调整过），然后关流
        sendEvent(emitter, endEvent);
        completeQuietly(emitter);
    }

    /**
     * 逐行读 Python 的 SSE 响应：空行（帧分隔符）、不是 "data:" 的行一律忽略，
     * 每解析出一条事件就交给 {@link #handleStreamEvent}，它返回 true（end / error）时停止读取。
     * <p>
     * 用 readLine() 而不是一次读完：readLine 会阻塞到下一行到达为止，
     * 这就实现了「Python 吐一段、Java 转一段」的边收边转发。
     *
     * @return true 表示本轮已经收到终止事件（end / error）；false 表示读到流末尾也没等到终止事件
     */
    private boolean readSseStream(InputStream body, Long userId,
                                  List<AiChatResponse.ToolCall> pendingToolCalls, SseEmitter emitter)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ChatStreamEvent event = parseSseLine(line);
                if (event == null) {
                    continue;
                }
                if (handleStreamEvent(userId, event, pendingToolCalls, emitter)) {
                    // end / error：本轮已经结束，后面即使还有内容也不再处理
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 解析一行 SSE：只有 "data: {json}" 这种行是一条事件，
     * 空行、注释行（":" 开头，常用于心跳）、其它字段（event: / id: / retry:）都返回 null。
     * JSON 不合法时记一条日志并跳过 —— 一行脏数据不该把整条流打断。
     */
    private ChatStreamEvent parseSseLine(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(SSE_DATA_PREFIX)) {
            return null;
        }
        String json = trimmed.substring(SSE_DATA_PREFIX.length()).trim();
        if (json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ChatStreamEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("流式事件解析失败，已跳过：{}", json);
            return null;
        }
    }

    /**
     * 流式请求的 RequestCallback：自己把请求体写成 JSON（不走消息转换器，避免被缓冲），
     * 并声明 Accept: text/event-stream。
     */
    private RequestCallback streamRequestBody(AiChatRequest request) {
        return httpRequest -> {
            httpRequest.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            httpRequest.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
            objectMapper.writeValue(httpRequest.getBody(), request);
        };
    }

    /**
     * 推一条事件给前端。
     * 客户端提前断开时 send 会抛 IOException / IllegalStateException：吞掉并记日志 ——
     * 流是前端自己断的，Java 侧不做补偿，也不做断线重连。
     */
    private void sendEvent(SseEmitter emitter, ChatStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 推送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    /**
     * 推一条 error 事件并关流（streamChat 的失败出口）。
     */
    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        sendEvent(emitter, ChatStreamEvent.error(message));
        completeQuietly(emitter);
    }

    /**
     * 关流：已经关过（或客户端已断开）时忽略，别让收尾动作再抛出异常。
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.warn("SSE 关闭失败（可能已经关闭）：{}", e.getMessage());
        }
    }

    /**
     * 解析一段自由格式的课程文本。
     * 只解析：rawText 原样交给 Python，拿回的 courses / failed 原样返回给 Controller，
     * 不查库、不落库、不去重（真正入库由用户确认后走课程模块的接口）。
     */
    public AiParseCourseResponse parseCourse(String rawText) {
        // 1. 先挡掉不合法入参：Python 侧同样会判 422，在这里拦下可以少跑一次网络调用
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("rawText 不能为空");
        }
        if (rawText.length() > MAX_RAW_TEXT_LENGTH) {
            throw new IllegalArgumentException("rawText 长度不能超过 " + MAX_RAW_TEXT_LENGTH + " 字");
        }

        // 2. 组装请求体并调用 Python
        AiParseCourseRequest request = new AiParseCourseRequest();
        request.setRawText(rawText.trim());

        AiParseCourseResponse response;
        try {
            response = restTemplate.postForObject(baseUrl + PARSE_COURSE_PATH, jsonEntity(request),
                    AiParseCourseResponse.class);
        } catch (RestClientException e) {
            // 真实原因只进日志，往上层只抛固定文案（与 Python 侧不外泄内部错误原因的做法一致）
            logAiFailure(PARSE_COURSE_PATH, e);
            throw new RuntimeException("AI 服务调用失败", e);
        }

        if (response == null || response.getCourses() == null) {
            log.error("AI 服务返回格式不合法：缺少 courses 字段");
            throw new RuntimeException("AI 服务调用失败");
        }
        if (response.getFailed() == null) {
            // Python 侧的 failed 有默认值；这里兜一下底，避免响应对应字段是 null
            response.setFailed(new ArrayList<>());
        }
        return response;
    }

    /**
     * 算当天的可用时段：08:00 - 22:00 减去课程、休息时间与已有任务的占用，只保留时长 >= 30 分钟的空档。
     *
     * @param dayOfWeek     当天星期，1=周一 …… 7=周日
     * @param courses       当天的课程（其余天的课程不会被传进来）
     * @param restTimes     用户全部休息时段；dayOfWeek 匹配当天或为 null（每天生效）的才算占用
     * @param existingTasks 当天已有的任务；只有 startTime / endTime 都填了的才占用时段，
     *                      已完成的同样算占用（那个时段已经被用掉了）
     */
    private List<AiPlanRequest.AvailableSlot> calculateAvailableSlots(int dayOfWeek,
                                                                     List<CourseDTO> courses,
                                                                     List<RestTimeDTO> restTimes,
                                                                     List<Task> existingTasks) {
        List<TimeRange> occupied = new ArrayList<>();
        for (CourseDTO course : courses) {
            addRange(occupied, course.getStartTime(), course.getEndTime());
        }
        for (RestTimeDTO restTime : restTimes) {
            if (restTime.getDayOfWeek() == null || restTime.getDayOfWeek() == dayOfWeek) {
                addRange(occupied, restTime.getStartTime(), restTime.getEndTime());
            }
        }
        for (Task task : existingTasks) {
            // 没填时段的任务跳过（startTime / endTime 任一为空都不算占用）；已完成的也算占用
            if (task.getStartTime() == null || task.getEndTime() == null) {
                continue;
            }
            addRange(occupied, task.getStartTime(), task.getEndTime());
        }
        occupied.sort(Comparator.comparing(TimeRange::start));

        // 游标从左往右扫一遍：占用起点晚于游标就说明中间有空档；占用之间重叠 / 包含也能正确处理
        List<AiPlanRequest.AvailableSlot> slots = new ArrayList<>();
        LocalTime cursor = DAY_START;
        for (TimeRange range : occupied) {
            // 与 [08:00, 22:00) 完全没有交集的占用直接跳过
            if (!range.end().isAfter(DAY_START) || !range.start().isBefore(DAY_END)) {
                continue;
            }
            if (range.start().isAfter(cursor)) {
                addSlot(slots, cursor, range.start());
            }
            if (range.end().isAfter(cursor)) {
                cursor = range.end();
            }
        }
        if (cursor.isBefore(DAY_END)) {
            addSlot(slots, cursor, DAY_END);
        }
        return slots;
    }

    /**
     * 空档达到最小时长才收进结果。
     */
    private void addSlot(List<AiPlanRequest.AvailableSlot> slots, LocalTime start, LocalTime end) {
        if (ChronoUnit.MINUTES.between(start, end) >= MIN_SLOT_MINUTES) {
            slots.add(AiPlanRequest.AvailableSlot.of(formatTime(start), formatTime(end)));
        }
    }

    /**
     * 把一条占用（课程或休息时间）加进列表；时间解析不了或 start >= end 时跳过该条，
     * 不让一行脏数据把整个请求搞崩（库里没有 CHECK 约束兜底）。
     */
    private void addRange(List<TimeRange> occupied, String startText, String endText) {
        addRange(occupied, parseTime(startText), parseTime(endText));
    }

    /**
     * 把一条占用（已有任务，时间已经是 LocalTime）加进列表；时间缺失或 start >= end 时跳过该条。
     */
    private void addRange(List<TimeRange> occupied, LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            return;
        }
        occupied.add(new TimeRange(start, end));
    }

    /**
     * 解析 planDate，格式必须是 yyyy-MM-dd。
     */
    private LocalDate parsePlanDate(String planDate) {
        if (planDate == null || planDate.isBlank()) {
            throw new IllegalArgumentException("planDate 不能为空");
        }
        try {
            return LocalDate.parse(planDate.trim(), DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("planDate 格式必须是 yyyy-MM-dd，例如 " + LocalDate.now());
        }
    }

    /**
     * 解析 HH:mm 时间，解析不了返回 null（由调用方决定跳过还是报错）。
     */
    private LocalTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(text.trim(), TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * LocalTime → "HH:mm"；null 转空串（Python 侧的 startTime / endTime 是 str，不接受 null）。
     */
    private String formatTime(LocalTime time) {
        return time == null ? "" : time.format(TIME_FORMATTER);
    }

    /**
     * 组装上下文：今日任务 + 今日课程表 + 今日休息时段 + 用户画像记忆 + 今日空档，
     * 前三样让模型知道用户今天的时间安排，第四样让模型「认识」这个用户，第五样（availableSlots）
     * 让模型知道今天还剩哪些空档，用于对话式排任务。
     * <p>
     * 课程按「今天」的星期查（CourseService 支持 dayOfWeek 过滤）；休息时间查全部后在内存里筛，
     * dayOfWeek 为 null（每天生效）或等于今天的才算 —— 与可用时段算的是同一套口径。
     * 前四个列表都只给模型看，Python 侧不会拿它们做任何计算（画像只是「了解一下」，不改行为）；
     * availableSlots 是 Java 侧按课程 / 休息 / 已有任务算出来的结果，同样只是提供给模型参考。
     * <p>
     * 除了空档之外，任何一个查询失败都会让整轮聊天失败（和今日任务 / 课程表取不到就不聊是一个道理）：
     * 上下文不完整时模型更容易编，宁可报错。空档相反：算不出来就给空数组，不让整轮聊天挂掉
     * （见 {@link #buildChatAvailableSlots}）。
     * <p>
     * chat 与 streamChat 都走这里，所以两边的上下文（含 availableSlots）天然一致。
     */
    private AiChatRequest.Context buildContext(Long userId) {
        int dayOfWeek = LocalDate.now().getDayOfWeek().getValue();

        // 原始数据先取出来放着：既用来拼给模型看的简报，也用来算空档（同一份数据不重复查库）
        List<Task> todayTasks = taskService.todayTasks(userId).getTasks();
        List<CourseDTO> todayCourses = courseService.list(userId, dayOfWeek);
        List<RestTimeDTO> allRestTimes = restTimeService.list(userId);

        List<AiChatRequest.TodayTask> tasks = new ArrayList<>();
        for (Task task : todayTasks) {
            if (tasks.size() >= MAX_CONTEXT_TASKS) {
                break;
            }
            AiChatRequest.TodayTask brief = new AiChatRequest.TodayTask();
            brief.setId(task.getId());
            brief.setTitle(blankIfNull(task.getTitle()));
            brief.setStatus(blankIfNull(task.getStatus()));
            brief.setStartTime(formatTime(task.getStartTime()));
            brief.setEndTime(formatTime(task.getEndTime()));
            tasks.add(brief);
        }

        List<AiChatRequest.TodayCourse> courses = new ArrayList<>();
        for (CourseDTO course : todayCourses) {
            if (courses.size() >= MAX_CONTEXT_COURSES) {
                break;
            }
            AiChatRequest.TodayCourse brief = new AiChatRequest.TodayCourse();
            brief.setCourseName(blankIfNull(course.getCourseName()));
            brief.setStartTime(blankIfNull(course.getStartTime()));
            brief.setEndTime(blankIfNull(course.getEndTime()));
            courses.add(brief);
        }

        List<AiChatRequest.TodayRestTime> restTimes = new ArrayList<>();
        for (RestTimeDTO restTime : allRestTimes) {
            // dayOfWeek 为 null 表示每天生效：只有「每天」或「今天」的才传给模型
            if (restTime.getDayOfWeek() != null && restTime.getDayOfWeek() != dayOfWeek) {
                continue;
            }
            if (restTimes.size() >= MAX_CONTEXT_REST_TIMES) {
                break;
            }
            AiChatRequest.TodayRestTime brief = new AiChatRequest.TodayRestTime();
            brief.setStartTime(blankIfNull(restTime.getStartTime()));
            brief.setEndTime(blankIfNull(restTime.getEndTime()));
            brief.setLabel(blankIfNull(restTime.getLabel()));
            restTimes.add(brief);
        }

        AiChatRequest.Context context = new AiChatRequest.Context();
        context.setTodayTasks(tasks);
        context.setTodayCourses(courses);
        context.setTodayRestTimes(restTimes);
        // 画像记忆：查不到也要给空数组（Python 侧据此决定不加「你对该用户的了解」那一段）
        context.setUserMemories(buildUserMemories(userId));
        // 今日空档：复用 /ai/plan 的算法（课程 + 休息 + 今日已有任务都算占用）；算不出来给空数组，不影响聊天
        context.setAvailableSlots(buildChatAvailableSlots(dayOfWeek, todayCourses, allRestTimes, todayTasks));
        return context;
    }

    /**
     * 算给模型的今日空档：直接复用 /ai/plan 的 {@link #calculateAvailableSlots}（同一套口径：
     * 08:00-22:00 减去课程、休息与今日已有任务的占用，只保留 >= 30 分钟的空档），不另写一份算法。
     * <p>
     * 参数与 plan 一致：courses 是当天的课（其余天的不传）、restTimes 是用户的全部休息时段
     * （由算法按 dayOfWeek 筛出「每天」与「今天」的）、existingTasks 是今日任务
     * （startTime / endTime 都填了才占用，已完成的同样算占用）。
     * <p>
     * 这里刻意**不让空档拖垮聊天**：算失败（库里有脏数据等）或算出来是空列表，都按空数组处理 ——
     * 模型少一份参考，总比整轮对话报错好。
     */
    private List<AiChatRequest.AvailableSlot> buildChatAvailableSlots(int dayOfWeek, List<CourseDTO> courses,
                                                                     List<RestTimeDTO> restTimes,
                                                                     List<Task> existingTasks) {
        List<AiChatRequest.AvailableSlot> slots = new ArrayList<>();
        try {
            for (AiPlanRequest.AvailableSlot slot : calculateAvailableSlots(dayOfWeek, courses, restTimes, existingTasks)) {
                slots.add(AiChatRequest.AvailableSlot.of(slot.getStartTime(), slot.getEndTime()));
            }
        } catch (RuntimeException e) {
            // 空档只是「锦上添花」的上下文：算不出来就退化成空数组，不向上抛
            log.warn("chat context 计算 availableSlots 失败，按空数组处理：{}", e.getMessage());
            slots.clear();
        }
        log.info("[调试] chat context 的 availableSlots = {}", slots);
        return slots;
    }

    /**
     * 组装用户画像记忆：查该用户全部记忆（按 updated_at 倒序，最近更新的在前），
     * 按 category 分组、每组最多 {@link #MAX_MEMORIES_PER_CATEGORY} 条，再把各组拼成一个列表。
     * <p>
     * 只传「分类 + 内容」两列（id / 时间不传，Python 侧 MemoryBrief 也只认这两个字段）；
     * 缺 category 或 content 的脏数据直接跳过，不往模型那边送。
     * 分组顺序取「每类最新一条的新旧」，同一类内部按时间倒序，输出稳定、便于排查。
     * <p>
     * 刻意不做：跨类排序 / 打分、语义去重、过期清理、合并更新 —— 这些是后续步骤的事，
     * 这里只是「把最近记住的几件事告诉模型」。
     */
    private List<AiChatRequest.UserMemoryItem> buildUserMemories(Long userId) {
        List<UserMemory> all = userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByDesc(UserMemory::getUpdatedAt)
                .orderByDesc(UserMemory::getId));
        if (all == null || all.isEmpty()) {
            // 还没提炼出任何记忆：给空数组，不是 null
            return new ArrayList<>();
        }

        Map<String, List<AiChatRequest.UserMemoryItem>> grouped = new LinkedHashMap<>();
        for (UserMemory memory : all) {
            String category = trimToEmpty(memory.getCategory());
            String content = trimToEmpty(memory.getContent());
            if (category.isEmpty() || content.isEmpty()) {
                continue;
            }
            List<AiChatRequest.UserMemoryItem> bucket =
                    grouped.computeIfAbsent(category, key -> new ArrayList<>());
            if (bucket.size() >= MAX_MEMORIES_PER_CATEGORY) {
                // 这一类已经取够最新 3 条，后面的（更旧的）不要了
                continue;
            }
            AiChatRequest.UserMemoryItem item = new AiChatRequest.UserMemoryItem();
            item.setCategory(category);
            item.setContent(content);
            bucket.add(item);
        }

        List<AiChatRequest.UserMemoryItem> memories = new ArrayList<>();
        for (List<AiChatRequest.UserMemoryItem> bucket : grouped.values()) {
            memories.addAll(bucket);
        }
        return memories;
    }

    /**
     * null → 空串：Python 侧这些字段都是 str，给 null 会被判 422。
     */
    private String blankIfNull(String text) {
        return text == null ? "" : text;
    }

    /**
     * null → 空串，并去掉首尾空白：用于把 user_memory 里取出来的值整理干净再发给 Python
     * （空白内容当作「没有这条记忆」，由调用方跳过）。
     */
    private String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * 组装历史消息：从库（chat_message）取该用户最近 {@link #RECENT_HISTORY_COUNT} 条。
     * ChatService.history 已经按时间正序返回（最早的在前），这里只做两件事：
     * role 为空的脏数据丢掉、content 为 null 转空串（Python 侧不接受 null）。
     * <p>
     * 刻意不再用前端传来的 history：前端可以伪造历史，而且刷新页面后前端手里没有历史，
     * 「跨会话记忆」必须以库为准。
     */
    private List<AiChatRequest.ChatMessage> buildHistory(Long userId) {
        List<AiChatRequest.ChatMessage> history = new ArrayList<>();
        for (ChatMessageDTO item : chatService.history(userId, RECENT_HISTORY_COUNT)) {
            if (item == null || item.getRole() == null || item.getRole().isBlank()) {
                continue;
            }
            AiChatRequest.ChatMessage message = new AiChatRequest.ChatMessage();
            message.setRole(item.getRole().trim());
            message.setContent(item.getContent() == null ? "" : item.getContent());
            history.add(message);
        }
        return history;
    }

    /**
     * 按 Python 给的顺序执行工具调用（一次请求只有一批，不做多轮）。
     *
     * @return 这一批的执行结果：改到的任务 id（按调用顺序）+ complete_task 没匹配到时的关键词
     */
    private ToolExecution executeToolCalls(Long userId, List<AiChatResponse.ToolCall> toolCalls) {
        List<Long> affectedTaskIds = new ArrayList<>();
        String completeTaskMissedKeyword = null;
        for (AiChatResponse.ToolCall toolCall : toolCalls) {
            ToolExecution execution = executeToolCall(userId, toolCall);
            affectedTaskIds.addAll(execution.affectedTaskIds());
            // 只要有一处没匹配到就记下来（多个 complete_task 时取第一个，回复里只能提一个关键词）
            if (completeTaskMissedKeyword == null) {
                completeTaskMissedKeyword = execution.completeTaskMissedKeyword();
            }
        }
        return new ToolExecution(affectedTaskIds, completeTaskMissedKeyword);
    }

    /**
     * 分派一条工具调用：工具名对不上、参数不够都算执行失败（抛 AiToolCallException）。
     * <p>
     * 返回的是「一条调用可能改到的多个任务 id」：complete_task 是按关键词批量完成的，
     * 其余工具一条调用只改一个任务（用 {@link ToolExecution#of(Long)} 包一下）。
     */
    private ToolExecution executeToolCall(Long userId, AiChatResponse.ToolCall toolCall) {
        String name = toolCall.getName() == null ? "" : toolCall.getName().trim();
        Map<String, Object> arguments = toolCall.getArguments() == null ? Map.of() : toolCall.getArguments();

        return switch (name) {
            case TOOL_ADD_TASK -> ToolExecution.of(addTaskByTool(userId, arguments));
            case TOOL_UPDATE_TASK -> ToolExecution.of(updateTaskByTool(userId, arguments));
            case TOOL_DELETE_TASK -> ToolExecution.of(deleteTaskByTool(userId, arguments));
            case TOOL_COMPLETE_TASK -> completeTaskByTool(userId, arguments);
            default -> throw new AiToolCallException("未知的工具：" + (name.isEmpty() ? "(空)" : name));
        };
    }

    /**
     * add_task：{ title, startTime?, endTime?, priority? }。
     * 工具 schema 里没有日期，而聊天只针对今天，所以 plan_date 固定为今天
     * （task.plan_date 是 NOT NULL，不填直接插不进去，也不会出现在今日列表里）。
     */
    private Long addTaskByTool(Long userId, Map<String, Object> arguments) {
        Task task = new Task();
        task.setTitle(requireTitle(arguments));
        task.setStartTime(parseToolTime(arguments, ARG_START_TIME, TOOL_ADD_TASK));
        task.setEndTime(parseToolTime(arguments, ARG_END_TIME, TOOL_ADD_TASK));
        task.setPriority(optionalText(arguments.get(ARG_PRIORITY)));
        task.setPlanDate(LocalDate.now());
        return taskService.create(userId, task).getId();
    }

    /**
     * update_task：{ taskId, title?, startTime?, endTime?, priority? }。
     * 只改传了的字段（TaskService.update 用 null 表示「不修改」）。
     */
    private Long updateTaskByTool(Long userId, Map<String, Object> arguments) {
        Long taskId = requireTaskId(arguments, TOOL_UPDATE_TASK);

        Task patch = new Task();
        patch.setTitle(optionalText(arguments.get(ARG_TITLE)));
        patch.setStartTime(parseToolTime(arguments, ARG_START_TIME, TOOL_UPDATE_TASK));
        patch.setEndTime(parseToolTime(arguments, ARG_END_TIME, TOOL_UPDATE_TASK));
        patch.setPriority(optionalText(arguments.get(ARG_PRIORITY)));

        if (taskService.update(userId, taskId, patch) == null) {
            throw new AiToolCallException("任务不存在或不属于当前用户（taskId=" + taskId + "）");
        }
        return taskId;
    }

    /**
     * delete_task：{ taskId }。
     */
    private Long deleteTaskByTool(Long userId, Map<String, Object> arguments) {
        Long taskId = requireTaskId(arguments, TOOL_DELETE_TASK);
        if (!taskService.delete(userId, taskId)) {
            throw new AiToolCallException("任务不存在或不属于当前用户（taskId=" + taskId + "）");
        }
        return taskId;
    }

    /**
     * complete_task：{ keyword }。用户说「数学作业都做完了」时，模型不必先把 taskId 猜对，
     * keyword 交给 {@link TaskService#completeByKeyword} 按「今天 + 未完成 + title 模糊匹配」处理，
     * 匹配到的**全部**标记完成（可能不止一条，用户说的是「都」）。
     * <p>
     * 匹配 0 条不算失败（Service 返回空列表、不抛异常）：这里把关键词记进结果，
     * 由调用方把回复换成「没找到…」的提示 —— 否则模型会说「好的，都帮你标完了」，而库里其实什么都没改。
     */
    private ToolExecution completeTaskByTool(Long userId, Map<String, Object> arguments) {
        String keyword = optionalText(arguments.get(ARG_KEYWORD));
        if (keyword == null) {
            throw new AiToolCallException("工具 " + TOOL_COMPLETE_TASK + " 缺少必填参数 keyword");
        }

        List<Long> completedIds = taskService.completeByKeyword(userId, keyword);
        if (completedIds.isEmpty()) {
            log.info("complete_task 没匹配到今日未完成任务：userId={}，keyword={}", userId, keyword);
            return new ToolExecution(List.of(), keyword);
        }
        return new ToolExecution(completedIds, null);
    }

    /**
     * complete_task 一条都没匹配到时的提示文案（%s → 关键词）。
     */
    private String notFoundReply(String keyword) {
        return String.format(COMPLETE_TASK_NOT_FOUND_REPLY, keyword);
    }

    /**
     * 取必填的 taskId。JSON 里的小整数会被 Jackson 解析成 Integer（大数才是 Long），
     * 所以统一用 Number 接再 longValue()，不能直接强转 Long。
     */
    private Long requireTaskId(Map<String, Object> arguments, String toolName) {
        Object value = arguments.get(ARG_TASK_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException e) {
                throw new AiToolCallException("工具 " + toolName + " 的 taskId 不是合法数字：" + text);
            }
        }
        throw new AiToolCallException("工具 " + toolName + " 缺少必填参数 taskId");
    }

    /**
     * 取 add_task 的必填参数 title（只有这个工具有 title）。
     */
    private String requireTitle(Map<String, Object> arguments) {
        String title = optionalText(arguments.get(ARG_TITLE));
        if (title == null) {
            throw new AiToolCallException("工具 " + TOOL_ADD_TASK + " 缺少必填参数 title");
        }
        return title;
    }

    /**
     * 可选参数转字符串：null / 空白都当「没给这个字段」。
     */
    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 解析工具参数里的时间：宽容一点，接受 "9:00" 与 "09:00"。
     * 给了但解析不出来时按「没给」处理并记日志 —— 模型写错时间不该让整轮对话失败。
     */
    private LocalTime parseToolTime(Map<String, Object> arguments, String fieldName, String toolName) {
        String text = optionalText(arguments.get(fieldName));
        if (text == null) {
            return null;
        }
        try {
            return LocalTime.parse(text, TOOL_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("工具 {} 的 {} 不是合法时间，已按未提供处理：{}", toolName, fieldName, text);
            return null;
        }
    }

    /**
     * JSON 请求实体：显式声明 Content-Type，让 Jackson 按 UTF-8 写出中文。
     */
    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /**
     * 统一记录「调用 AI 服务失败」的真实原因：只进日志，不外泄给前端。
     */
    private void logAiFailure(String path, Exception e) {
        log.error("调用 AI 服务失败：{} {}", baseUrl + path, e.getMessage());
    }

    /**
     * 一批（或一条）工具调用的执行结果。
     *
     * @param affectedTaskIds           真正改到的任务 id，按工具调用顺序；complete_task 一次可能好几条
     * @param completeTaskMissedKeyword complete_task 按关键词一条都没匹配到时记下的关键词（回复要换成提示）；
     *                                  没发生就是 null
     */
    private record ToolExecution(List<Long> affectedTaskIds, String completeTaskMissedKeyword) {

        /** 一条调用只改一个任务的普通工具（add_task / update_task / delete_task）用这个包一下 */
        static ToolExecution of(Long taskId) {
            return new ToolExecution(List.of(taskId), null);
        }
    }

    /**
     * 一段被占用的时间，左闭右开。
     */
    private record TimeRange(LocalTime start, LocalTime end) {
    }

}
