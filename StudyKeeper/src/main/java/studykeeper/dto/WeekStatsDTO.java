package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * GET /api/stats/week 的业务数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeekStatsDTO {

    /** 本周周一 */
    private LocalDate weekStart;

    /** 本周周日 */
    private LocalDate weekEnd;

    /** 本周任务总数 */
    private int total;

    /** 本周已完成数（status = done） */
    private int completed;

    /** 完成百分比（整数，0~100） */
    private int percent;

}
