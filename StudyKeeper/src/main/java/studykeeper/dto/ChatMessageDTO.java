package studykeeper.dto;

import lombok.Data;

/**
 * 聊天历史里的一条消息，GET /api/chat/history 的 data 元素。
 * createdAt 对外是 "yyyy-MM-dd HH:mm:ss" 字符串（docs/api.md §0.5），由 ChatService 从 LocalDateTime 转过来。
 */
@Data
public class ChatMessageDTO {

    private Long id;

    /** user / assistant */
    private String role;

    private String content;

    /** 格式 yyyy-MM-dd HH:mm:ss，如 2026-09-17 08:30:00 */
    private String createdAt;

}
