package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /ai/parse-course（Python → Java）的响应体：{"courses": [...], "failed": [...]}。
 * Python 侧不套 code / message / data，字段名与 Pydantic 模型 ParseCourseResponse 完全一致。
 * 只解析文本：Java 拿到后原样交给前端，不落库、不去重。
 */
@Data
public class AiParseCourseResponse {

    /** 解析出的课程列表，可能为空数组 */
    private List<CourseItem> courses = new ArrayList<>();

    /** 解析失败的原始行（模型自己报的 + Python 侧剔除的），交给前端提示用户手工修正 */
    private List<String> failed = new ArrayList<>();

    /**
     * 解析出来的一门课，对应 Python 侧 CourseItem。
     */
    @Data
    public static class CourseItem {

        /** 课程名，如「高等数学」 */
        private String courseName;

        /** 上课的星期，1=周一 …… 7=周日，如 [1, 3, 5] */
        private List<Integer> daysOfWeek;

        /** 开始时间，格式 HH:mm */
        private String startTime;

        /** 结束时间，格式 HH:mm */
        private String endTime;

    }

}
