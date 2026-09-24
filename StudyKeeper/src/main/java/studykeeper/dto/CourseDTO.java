package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 课程的请求体 / 响应体。
 * startTime / endTime 对外是 "HH:mm" 字符串，Service 层与实体里的 LocalTime 互转。
 */
@Data
@NoArgsConstructor
public class CourseDTO {

    private Long id;

    private String courseName;

    /** 1=周一 …… 7=周日 */
    private Integer dayOfWeek;

    /** 格式 HH:mm，如 08:00 */
    private String startTime;

    /** 格式 HH:mm，如 09:40 */
    private String endTime;

    private String location;

}
