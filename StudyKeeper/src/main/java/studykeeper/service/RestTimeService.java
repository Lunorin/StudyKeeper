package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import studykeeper.dto.RestTimeDTO;
import studykeeper.entity.RestTime;
import studykeeper.mapper.RestTimeMapper;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 休息时间业务层：列表、新增、修改、删除。
 */
@Service
public class RestTimeService {

    /** 对外的时间格式：HH:mm。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 星期取值范围：1~7。 */
    private static final int MIN_DAY_OF_WEEK = 1;
    private static final int MAX_DAY_OF_WEEK = 7;

    private final RestTimeMapper restTimeMapper;

    public RestTimeService(RestTimeMapper restTimeMapper) {
        this.restTimeMapper = restTimeMapper;
    }

    /**
     * 休息时段列表：每天生效（day_of_week 为 null）的排最前，
     * 然后按周几升序、开始时间升序；同一时间再按 id 升序，保证顺序稳定。
     */
    public List<RestTimeDTO> list(Long userId) {
        List<RestTime> restTimes = restTimeMapper.selectList(new LambdaQueryWrapper<RestTime>()
                .eq(RestTime::getUserId, userId)
                .last("ORDER BY day_of_week IS NULL DESC, day_of_week ASC, start_time ASC, id ASC"));

        List<RestTimeDTO> result = new ArrayList<>(restTimes.size());
        for (RestTime restTime : restTimes) {
            result.add(toDTO(restTime));
        }
        return result;
    }

    /**
     * 新增休息时段。userId 来自 token，dayOfWeek 为 null 表示每天生效。
     * dayOfWeek 越界、时间缺失或 startTime >= endTime 时抛 IllegalArgumentException，由 Controller 转成 400。
     */
    public RestTimeDTO create(Long userId, RestTimeDTO request) {
        requireValidDayOfWeek(request.getDayOfWeek());
        LocalTime startTime = requireTime(request.getStartTime(), "startTime");
        LocalTime endTime = requireTime(request.getEndTime(), "endTime");
        requireStartBeforeEnd(startTime, endTime);

        RestTime restTime = new RestTime();
        restTime.setUserId(userId);
        restTime.setDayOfWeek(request.getDayOfWeek());
        restTime.setStartTime(startTime);
        restTime.setEndTime(endTime);
        restTime.setLabel(request.getLabel());

        LocalDateTime now = LocalDateTime.now();
        restTime.setCreatedAt(now);
        restTime.setUpdatedAt(now);

        restTimeMapper.insert(restTime);
        return toDTO(restTime);
    }

    /**
     * 修改休息时段，只允许改 dayOfWeek / startTime / endTime / label。
     * 请求里为 null 的字段表示不修改（注意：因为 dayOfWeek 的 null 本身表示"每天"，
     * 所以本接口无法把已有记录改回"每天"，需要的话请 DELETE 后重新 POST）。
     * 校验用的是"合并后"的值；记录不存在或不属于当前用户时返回 null。
     */
    public RestTimeDTO update(Long userId, Long id, RestTimeDTO request) {
        RestTime restTime = restTimeMapper.selectById(id);
        if (restTime == null || !userId.equals(restTime.getUserId())) {
            return null;
        }

        // 先算出合并后的值并校验，校验不通过时不会产生任何写入
        if (request.getDayOfWeek() != null) {
            requireValidDayOfWeek(request.getDayOfWeek());
        }
        LocalTime startTime = request.getStartTime() == null
                ? restTime.getStartTime()
                : requireTime(request.getStartTime(), "startTime");
        LocalTime endTime = request.getEndTime() == null
                ? restTime.getEndTime()
                : requireTime(request.getEndTime(), "endTime");
        requireStartBeforeEnd(startTime, endTime);

        if (request.getDayOfWeek() != null) {
            restTime.setDayOfWeek(request.getDayOfWeek());
        }
        if (request.getLabel() != null) {
            restTime.setLabel(request.getLabel());
        }
        restTime.setStartTime(startTime);
        restTime.setEndTime(endTime);

        restTime.setUpdatedAt(LocalDateTime.now());
        restTimeMapper.updateById(restTime);
        return toDTO(restTime);
    }

    /**
     * 删除休息时段。记录不存在或不属于当前用户时返回 false。
     */
    public boolean delete(Long userId, Long id) {
        return restTimeMapper.delete(new LambdaQueryWrapper<RestTime>()
                .eq(RestTime::getId, id)
                .eq(RestTime::getUserId, userId)) > 0;
    }

    /**
     * 实体 → DTO：LocalTime 按 HH:mm 转成字符串。
     */
    private RestTimeDTO toDTO(RestTime restTime) {
        RestTimeDTO dto = new RestTimeDTO();
        dto.setId(restTime.getId());
        dto.setDayOfWeek(restTime.getDayOfWeek());
        dto.setStartTime(formatTime(restTime.getStartTime()));
        dto.setEndTime(formatTime(restTime.getEndTime()));
        dto.setLabel(restTime.getLabel());
        return dto;
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }

    /**
     * 校验星期：允许为 null（表示每天），否则必须是 1~7。
     */
    private void requireValidDayOfWeek(Integer dayOfWeek) {
        if (dayOfWeek != null && (dayOfWeek < MIN_DAY_OF_WEEK || dayOfWeek > MAX_DAY_OF_WEEK)) {
            throw new IllegalArgumentException("dayOfWeek 只能是 1-7 的数字，或留空表示每天");
        }
    }

    /**
     * 校验并解析时间，格式必须是 HH:mm。
     */
    private LocalTime requireTime(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        try {
            return LocalTime.parse(text.trim(), TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " 格式必须是 HH:mm，例如 10:00");
        }
    }

    /**
     * 校验开始时间必须早于结束时间。
     */
    private void requireStartBeforeEnd(LocalTime startTime, LocalTime endTime) {
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("startTime 必须早于 endTime");
        }
    }

}
