package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /ai/extract-memory（Python → Java）的响应体：{"memories": [{"category": "...", "content": "..."}]}。
 * 没有值得记住的信息时 memories 是空数组。Python 侧只负责提炼，落库由 Java 侧 MemoryService 做。
 */
@Data
public class ExtractMemoryResponse {

    /** 提炼出来的记忆，最多 5 条（Python 侧 MAX_MEMORIES） */
    private List<Memory> memories = new ArrayList<>();

    /**
     * 一条记忆，对应 Python 侧 ExtractedMemory。
     */
    @Data
    public static class Memory {

        /** habit / emotion / event / preference / goal */
        private String category;

        /** 内容，Python 侧保证非空且不超过 500 字 */
        private String content;

    }

}
