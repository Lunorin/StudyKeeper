package studykeeper.dto;

import lombok.Data;

/**
 * 用户画像记忆的响应体（GET /api/memory、POST /api/memory）。
 * <p>
 * 新增时（POST）只有 category 与 content 会被读，id / 时间由服务端生成；
 * createdAt / updatedAt 对外是 "yyyy-MM-dd HH:mm:ss" 字符串（docs/api.md §0.5），由 MemoryService 转换。
 */
@Data
public class UserMemoryDTO {

    private Long id;

    /** habit / emotion / event / preference / goal（见 docs/api.md §0.6） */
    private String category;

    /** 记忆内容，最长 500 字（与 user_memory.content 的列长一致） */
    private String content;

    /** 格式 yyyy-MM-dd HH:mm:ss */
    private String createdAt;

    /** 格式 yyyy-MM-dd HH:mm:ss */
    private String updatedAt;

}
