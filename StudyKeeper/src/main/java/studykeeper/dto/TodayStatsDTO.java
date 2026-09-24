package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * GET /api/stats/today 的业务数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodayStatsDTO {

    private LocalDate date;

    /** 当天任务总数 */
    private int total;

    /** 当天已完成数（status = done） */
    private int completed;

    /** 完成百分比（整数，0~100） */
    private int percent;

}
