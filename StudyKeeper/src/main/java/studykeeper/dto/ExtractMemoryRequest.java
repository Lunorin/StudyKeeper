package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /ai/extract-memory（Java → Python）的请求体：{"messages": [{"role": "...", "content": "..."}]}。
 * 字段名与 Python 侧 Pydantic 模型 ExtractMemoryRequest 完全一致（那边 extra="forbid"，
 * 多传一个字段立刻 422），所以这里只有 messages 一个字段；messages 至少要有一条非空白内容。
 */
@Data
public class ExtractMemoryRequest {

    /** 待提炼的对话；本步只放「本轮」两条（用户这句 + AI 回复），最多 20 条（Python 侧上限） */
    private List<Message> messages = new ArrayList<>();

    /**
     * 一条对话消息，对应 Python 侧 MemoryMessage。
     */
    @Data
    public static class Message {

        /** user / assistant */
        private String role;

        /** 消息内容；Python 侧不接受 null，没有内容时给空串 */
        private String content;

    }

}
