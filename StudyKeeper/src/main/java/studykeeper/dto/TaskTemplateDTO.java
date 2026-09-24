package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 长期任务的请求体 / 响应体。
 * 请求时只有 title / description / duration / priority / repeatDays 生效；
 * id、difficulty、active 由服务端控制或按默认值处理。
 */
@Data
@NoArgsConstructor
public class TaskTemplateDTO {

    private Long id;

    private String title;

    private String description;

    private Integer duration;

    private String priority;

    /** 重复的星期：1=周一 …… 7=周日 */
    private List<Integer> repeatDays;

    private BigDecimal difficulty;

    private Boolean active;

}
