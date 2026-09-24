package studykeeper.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 一条流式对话事件（POST /api/ai/chat/stream）。
 * <p>
 * 两个方向共用这一个类：
 * <ul>
 *   <li>Python → Java：解析 /ai/chat/stream 里的 {@code data: {...}} 帧；</li>
 *   <li>Java → 前端：原样转发（delta / error / end），tool_calls 事件额外带上 affectedTaskIds。</li>
 * </ul>
 * {@code @JsonInclude(NON_NULL)}：只写有值的字段，转发出去的事件与 Python 原来那条长得一样
 * （delta 就只有 type + content，不会多出一堆 null 字段）。
 * <p>
 * type 取值与 Python 侧 app/services/chat.py 的 EVENT_* 一一对应：
 * delta（正文片段）、tool_calls（攒齐的工具调用意图）、end（本轮结束）、error（出错）；
 * start 是保留值 —— Python 目前不发，Java 也不转给前端。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatStreamEvent {

    /** 保留：Python 目前不发 start（Java 收到也不推送） */
    public static final String TYPE_START = "start";

    /** 正文片段，content 有值 */
    public static final String TYPE_DELTA = "delta";

    /** 工具调用意图（Python 攒齐后发一条），Java 执行完补 affectedTaskIds 再转发 */
    public static final String TYPE_TOOL_CALLS = "tool_calls";

    /** 本轮结束，reply 是完整回复 */
    public static final String TYPE_END = "end";

    /** 出错，message 是给前端看的文案（真实原因只写服务端日志） */
    public static final String TYPE_ERROR = "error";

    /** 事件类型：delta / tool_calls / end / error（start 保留） */
    private String type;

    /** delta 时有值：模型刚吐出来的一小段正文 */
    private String content;

    /**
     * tool_calls 时有值。元素结构与非流式响应里的 toolCalls 完全一致，所以直接复用
     * {@link AiChatResponse.ToolCall}（Python 侧两条路径用的是同一套 parse 逻辑，结构一致）。
     */
    private List<AiChatResponse.ToolCall> toolCalls;

    /** end 时有值：本轮完整回复 */
    private String reply;

    /** error 时有值：给前端看的原因 */
    private String message;

    /** Java 侧补的字段：tool_calls 事件里带上这轮真正改到的任务 id（Python 不返回这个） */
    private List<Long> affectedTaskIds;

    /**
     * 造一条 error 事件（AiService 与 AiController 都会用：正常失败、线程池拒绝）。
     */
    public static ChatStreamEvent error(String message) {
        ChatStreamEvent event = new ChatStreamEvent();
        event.setType(TYPE_ERROR);
        event.setMessage(message);
        return event;
    }

}
