package studykeeper.dto;

import lombok.Data;

/**
 * POST /ai/parse-course（Java → Python）的请求体。
 * 字段名与 Python 侧 Pydantic 模型 ParseCourseRequest 完全一致（那边 extra="forbid"，
 * 多传一个字段立刻 422），所以这里只有 rawText 一个字段。
 */
@Data
public class AiParseCourseRequest {

    /** 一段自由格式的课程文本，最长 5000 字（与 Python 侧 MAX_RAW_TEXT_LENGTH 一致） */
    private String rawText;

}
