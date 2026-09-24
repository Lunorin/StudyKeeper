package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import studykeeper.dto.TodayStatsDTO;
import studykeeper.dto.TrendItemDTO;
import studykeeper.dto.WeekStatsDTO;
import studykeeper.entity.Task;
import studykeeper.mapper.TaskMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计业务层：今日、本周、近 N 天趋势。
 * 口径：只统计当前登录用户（userId 来自 token）；只看 task 表（不看 completion_record）；
 * completed 指 status = 'done'；用 plan_date 判断任务属于哪一天。
 * <p>
 * **`status = 'skipped'`（用户在今日页删掉的长期任务实例）一律排除**：它们既不算完成，也不进分母 total。
 */
@Service
public class StatsService {

    /** 已完成状态。 */
    private static final String STATUS_DONE = "done";

    /**
     * 已跳过（软删除）状态：用户在今日页删掉的长期任务实例。
     * 这类任务已经「被删除」，不该再影响任何统计口径（今日 / 本周 / 趋势的分子与分母都要排除）。
     */
    private static final String STATUS_SKIPPED = "skipped";

    /** trend 的 days 取值范围。 */
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 30;

    private final TaskMapper taskMapper;

    public StatsService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 今日统计。
     */
    public TodayStatsDTO todayStats(Long userId) {
        LocalDate today = LocalDate.now();
        int total = countTasks(userId, today, today);
        int completed = countDoneTasks(userId, today, today);
        return new TodayStatsDTO(today, total, completed, percent(completed, total));
    }

    /**
     * 本周统计：周一 00:00 到 周日 23:59（plan_date 是日期，所以等价于周一~周日区间）。
     */
    public WeekStatsDTO weekStats(Long userId) {
        LocalDate weekStart = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);
        int total = countTasks(userId, weekStart, weekEnd);
        int completed = countDoneTasks(userId, weekStart, weekEnd);
        return new WeekStatsDTO(weekStart, weekEnd, total, completed, percent(completed, total));
    }

    /**
     * 近 N 天趋势：含今天在内的连续 N 天，按日期升序；没有任务的日子 completed = 0。
     * days 越界时抛 IllegalArgumentException，由 Controller 转成 400。
     */
    public List<TrendItemDTO> trend(Long userId, int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new IllegalArgumentException("days 只能是 1-30 的数字");
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1L);

        // 一次查出区间内所有已完成任务再按日期归类（区间最多 30 天，数据量很小）
        List<Task> doneTasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId)
                .eq(Task::getStatus, STATUS_DONE)
                // done 与 skipped 本来就互斥，这里再显式排一次，保证口径一目了然
                .ne(Task::getStatus, STATUS_SKIPPED)
                .ge(Task::getPlanDate, startDate)
                .le(Task::getPlanDate, endDate));

        Map<LocalDate, Integer> completedByDate = new HashMap<>();
        for (Task task : doneTasks) {
            if (task.getPlanDate() != null) {
                completedByDate.merge(task.getPlanDate(), 1, Integer::sum);
            }
        }

        // 把区间内每一天都补上，没有的给 0
        List<TrendItemDTO> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            result.add(new TrendItemDTO(date, completedByDate.getOrDefault(date, 0)));
        }
        return result;
    }

    /**
     * [startDate, endDate] 区间内的任务总数（分母）。已跳过的（skipped）不算。
     */
    private int countTasks(Long userId, LocalDate startDate, LocalDate endDate) {
        Long count = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId)
                .ne(Task::getStatus, STATUS_SKIPPED)
                .ge(Task::getPlanDate, startDate)
                .le(Task::getPlanDate, endDate));
        return count == null ? 0 : count.intValue();
    }

    /**
     * [startDate, endDate] 区间内已完成的任务数（分子）。已跳过的（skipped）不算。
     */
    private int countDoneTasks(Long userId, LocalDate startDate, LocalDate endDate) {
        Long count = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId)
                .eq(Task::getStatus, STATUS_DONE)
                .ne(Task::getStatus, STATUS_SKIPPED)
                .ge(Task::getPlanDate, startDate)
                .le(Task::getPlanDate, endDate));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 完成百分比：total 为 0 时返回 0（避免除零 / NaN）。
     */
    private int percent(int completed, int total) {
        return total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
    }

}
