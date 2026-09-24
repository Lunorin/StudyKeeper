package studykeeper.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.dto.TodayStatsDTO;
import studykeeper.dto.TrendItemDTO;
import studykeeper.dto.WeekStatsDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.StatsService;

import java.util.List;

/**
 * 统计接口。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 今日统计。
     */
    @GetMapping("/today")
    public Result<TodayStatsDTO> today(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        return Result.success(statsService.todayStats(userId));
    }

    /**
     * 本周统计（周一 ~ 周日）。
     */
    @GetMapping("/week")
    public Result<WeekStatsDTO> week(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        return Result.success(statsService.weekStats(userId));
    }

    /**
     * 近 N 天趋势，days 默认 7、范围 1-30；越界返回 400。
     */
    @GetMapping("/trend")
    public Result<List<TrendItemDTO>> trend(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                            @RequestParam(required = false, defaultValue = "7") Integer days) {
        try {
            return Result.success(statsService.trend(userId, days));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

}
