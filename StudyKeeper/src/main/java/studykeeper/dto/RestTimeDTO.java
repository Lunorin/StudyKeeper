package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 休息时段的请求体 / 响应体。
 * startTime / endTime 对外是 "HH:mm" 字符串，Service 层与实体里的 LocalTime 互转。
 */
@Data
@NoArgsConstructor
public class RestTimeDTO {

    private Long id;

    /** 1=周一 …… 7=周日；null 表示每天生效 */
    private Integer dayOfWeek;

    /** 格式 HH:mm，如 10:00 */
    private String startTime;

    /** 格式 HH:mm，如 10:20 */
    private String endTime;

    private String label;

}
