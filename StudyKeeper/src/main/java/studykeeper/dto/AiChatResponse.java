package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * POST /ai/chat（Python → Java）的响应体：{"reply": "...", "toolCalls": [...]}。
 * Python 只返回模型「想做什么」（工具名 + 参数），不执行也不落库；执行在 AiService。
 */
@Data
public class AiChatResponse {

    /** 模型给用户的回复；只有工具调用时可能是空串 */
    private String reply;

    /** 工具调用意图，可能是空列表 */
    private List<ToolCall> toolCalls = new ArrayList<>();

    /**
     * 一次工具调用意图，对应 Python 侧 ToolCall。
     * arguments 是各工具自己的参数表（不同工具字段不同，所以只能用 Map）：
     * add_task → title / startTime / endTime / priority；
     * update_task → taskId / title / startTime / endTime / priority；
     * delete_task → taskId。
     * 注意 JSON 里的小整数被 Jackson 解析成 Integer、大数才是 Long，
     * 所以取 taskId 时必须用 Number 接再 longValue()，不能直接强转 Long。
     */
    @Data
    public static class ToolCall {

        /** 工具名：add_task / update_task / delete_task / complete_task */
        private String name;

        /** 工具参数 */
        private Map<String, Object> arguments;

    }

}
