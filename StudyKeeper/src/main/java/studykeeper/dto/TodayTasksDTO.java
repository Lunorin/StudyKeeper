package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import studykeeper.entity.Task;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/task/today 的业务数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodayTasksDTO {

    /** 统计日期 */
    private LocalDate date;

    /** 今日任务总数 */
    private int total;

    /** 已完成任务数 */
    private int completed;

    /** 完成百分比（整数，0~100） */
    private int percent;

    /** 今日任务列表 */
    private List<Task> tasks;

}
