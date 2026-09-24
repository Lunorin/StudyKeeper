package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * GET /api/stats/trend 里的一天。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendItemDTO {

    private LocalDate date;

    /** 当天已完成数，没有任务的日子为 0 */
    private int completed;

}
