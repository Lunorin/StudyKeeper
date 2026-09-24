package studykeeper.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import studykeeper.common.AiToolCallException;
import studykeeper.common.Result;
import studykeeper.config.AsyncConfig;
import studykeeper.dto.AiChatRequest;
import studykeeper.dto.AiChatResult;
import studykeeper.dto.AiParseCourseRequest;
import studykeeper.dto.AiParseCourseResponse;
import studykeeper.dto.AiPlanRequest;
import studykeeper.dto.ChatStreamEvent;
import studykeeper.dto.TaskItem;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.AiService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 接口（前端 → Java → Python）。
 * 目前有四个：POST /api/ai/plan（目标拆成今日任务列表，只返回不落库）、
 * POST /api/ai/chat（对话；AI 想增删改任务时由 Java 执行工具、真正改库）、
 * POST /api/ai/chat/stream（同一件事的 SSE 流式版：正文边收边推，结束时再推工具结果与完整回复）、
 * POST /api/ai/parse-course（课程文本解析，只解析不落库）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    /** AI 服务调用失败（docs/api.md §0.4 的错误码） */
    private static final int AI_SERVICE_ERROR_CODE = 3001;

    /** AI 工具调用失败：AI 连上了、话说完了，但想做的操作没执行成功（前端据此刷新任务列表并提示） */
    private static final int AI_TOOL_ERROR_CODE = 3002;

    /** 固定文案：AI 侧的真实原因只写在日志里，不返回给前端 */
    private static final String AI_SERVICE_ERROR_MESSAGE = "AI 服务调用失败";

    /** SSE 连接存活上限：5 分钟（到点后由 Spring 关掉连接，前端会收到断流） */
    private static final long CHAT_STREAM_TIMEOUT_MILLIS = 300_000L;

    /** 流式线程池（池 + 队列）都满时的兜底文案 */
    private static final String CHAT_STREAM_BUSY_MESSAGE = "当前对话请求过多，请稍后重试";

    private final AiService aiService;

    /** 流式对话线程池：streamChat 丢进去跑，请求线程立刻返回 emitter */
    private final ThreadPoolTaskExecutor chatStreamExecutor;

    public AiController(AiService aiService,
                        @Qualifier(AsyncConfig.CHAT_STREAM_EXECUTOR_BEAN_NAME)
                        ThreadPoolTaskExecutor chatStreamExecutor) {
        this.aiService = aiService;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /**
     * AI 拆解当天任务。
     * 请求体只读 goal 与 planDate：availableSlots 一律由服务端按课程表 + 休息时间 + 今日已有任务算，前端不用传（传了也忽略）。
     * userId 取自 token；goal / planDate 不合法或当天没有可用时段返回 400，AI 服务不可用返回 3001。
     */
    @PostMapping("/plan")
    public Result<List<TaskItem>> plan(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                       @RequestBody AiPlanRequest request) {
        try {
            return Result.success(aiService.plan(userId, request.getGoal(), request.getPlanDate()));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(AI_SERVICE_ERROR_CODE, AI_SERVICE_ERROR_MESSAGE);
        }
    }

    /**
     * AI 对话：可以纯聊天，也可以让 AI 帮忙增 / 改 / 删今日任务。
     * 请求体只读 message（context 里的今日任务由服务端现查；history 由服务端从 chat_message 现查，
     * 前端传了也忽略，所以刷新页面后能接着上一轮聊）。
     * 这一轮的「用户消息 + AI 回复」会在同一个事务里落库，之后可用 GET /api/chat/history 拉回来。
     * message 不合法返回 400；AI 服务不可用返回 3001；工具调用没执行成功（含 AI 想改的任务已不存在）
     * 返回 3002，此时这轮的工具改动与对话记录都已整体回滚。
     */
    @PostMapping("/chat")
    public Result<AiChatResult> chat(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                     @RequestBody AiChatRequest request) {
        try {
            return Result.success(aiService.chat(userId, request.getMessage()));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (AiToolCallException e) {
            return Result.error(AI_TOOL_ERROR_CODE, e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(AI_SERVICE_ERROR_CODE, AI_SERVICE_ERROR_MESSAGE);
        }
    }

    /**
     * AI 对话（SSE 流式，POST /api/ai/chat/stream）。
     * <p>
     * 请求体与非流式一样只读 message（context 与 history 都由服务端现查）；响应是 text/event-stream，
     * 前端拿到的每一条都是 {@link ChatStreamEvent} 的 JSON：
     * <pre>
     *   {"type":"delta","content":"…"}                       正文片段，边收边推（可能有很多条）
     *   {"type":"tool_calls","toolCalls":[…],"affectedTaskIds":[…]}
     *                                                       AI 想改任务时，Java 执行完再推这一条（可能没有）
     *   {"type":"end","reply":"…"}                            本轮结束，reply 是完整回复
     *   {"type":"error","message":"…"}                        出错（入参不合法 / AI 不可用 / 工具失败）
     * </pre>
     * 与非流式的区别：HTTP 状态码在流开始时就已经是 200 了，所以参数错误等也只能用 error 事件表达
     * （非流式对应 400 / 3001 / 3002）。
     * <p>
     * 请求线程只做一件事：把 streamChat 提交到流式线程池，然后立刻返回 emitter（连接最长 5 分钟）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                 @RequestBody AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(CHAT_STREAM_TIMEOUT_MILLIS);
        String message = request.getMessage();
        try {
            chatStreamExecutor.execute(() -> aiService.streamChat(userId, message, emitter));
        } catch (RejectedExecutionException e) {
            // 线程池 + 队列都满了：回一条 error 事件并关流，别让前端一直挂着等
            log.error("流式对话线程池已满，拒绝本次请求：{}", e.getMessage());
            sendBusyError(emitter);
        }
        return emitter;
    }

    /**
     * 流式线程池拒绝时，在请求线程里补一条 error 事件并关流。
     * 这里没法复用 AiService 里的私有推送方法，就地写一小段（只有几行，且不涉及业务）。
     */
    private void sendBusyError(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().data(ChatStreamEvent.error(CHAT_STREAM_BUSY_MESSAGE)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 推送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    /**
     * AI 解析课程文本：把一段自由格式的课程文本转成结构化课程列表，解析不了的原始行放进 failed。
     * 请求体只读 rawText（前端传的原样转发给 Python）；rawText 不合法返回 400，AI 服务不可用返回 3001。
     * 只解析不落库、不去重（用户确认后由前端调课程模块的接口保存），本接口与 userId 无关。
     */
    @PostMapping("/parse-course")
    public Result<AiParseCourseResponse> parseCourse(@RequestBody AiParseCourseRequest request) {
        try {
            return Result.success(aiService.parseCourse(request.getRawText()));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(AI_SERVICE_ERROR_CODE, AI_SERVICE_ERROR_MESSAGE);
        }
    }

}
