package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import studykeeper.dto.CourseDTO;
import studykeeper.entity.Course;
import studykeeper.mapper.CourseMapper;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 课程表业务层：列表、新增、修改、删除。
 */
@Service
public class CourseService {

    /** 对外的时间格式：HH:mm。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 星期取值范围：1~7。 */
    private static final int MIN_DAY_OF_WEEK = 1;
    private static final int MAX_DAY_OF_WEEK = 7;

    private final CourseMapper courseMapper;

    public CourseService(CourseMapper courseMapper) {
        this.courseMapper = courseMapper;
    }

    /**
     * 课程列表：dayOfWeek 为空返回全部，否则只返回该天。
     * 按 dayOfWeek 升序、startTime 升序；同一天同一时间再按 id 升序，保证顺序稳定。
     */
    public List<CourseDTO> list(Long userId, Integer dayOfWeek) {
        List<Course> courses = courseMapper.selectList(new LambdaQueryWrapper<Course>()
                .eq(Course::getUserId, userId)
                .eq(dayOfWeek != null, Course::getDayOfWeek, dayOfWeek)
                .orderByAsc(Course::getDayOfWeek)
                .orderByAsc(Course::getStartTime)
                .orderByAsc(Course::getId));

        List<CourseDTO> result = new ArrayList<>(courses.size());
        for (Course course : courses) {
            result.add(toDTO(course));
        }
        return result;
    }

    /**
     * 新增课程。userId 来自 token，一次只加一天。
     * dayOfWeek 越界、时间缺失或 startTime >= endTime 时抛 IllegalArgumentException，由 Controller 转成 400。
     */
    public CourseDTO create(Long userId, CourseDTO request) {
        Integer dayOfWeek = requireDayOfWeek(request.getDayOfWeek());
        LocalTime startTime = requireTime(request.getStartTime(), "startTime");
        LocalTime endTime = requireTime(request.getEndTime(), "endTime");
        requireStartBeforeEnd(startTime, endTime);

        Course course = new Course();
        course.setUserId(userId);
        course.setCourseName(request.getCourseName());
        course.setDayOfWeek(dayOfWeek);
        course.setStartTime(startTime);
        course.setEndTime(endTime);
        course.setLocation(request.getLocation());

        LocalDateTime now = LocalDateTime.now();
        course.setCreatedAt(now);
        course.setUpdatedAt(now);

        courseMapper.insert(course);
        return toDTO(course);
    }

    /**
     * 修改课程，只允许改 courseName / dayOfWeek / startTime / endTime / location。
     * 请求里为 null 的字段表示不修改；校验用的是"合并后"的值。
     * 课程不存在或不属于当前用户时返回 null。
     */
    public CourseDTO update(Long userId, Long id, CourseDTO request) {
        Course course = courseMapper.selectById(id);
        if (course == null || !userId.equals(course.getUserId())) {
            return null;
        }

        // 先算出合并后的值并校验，校验不通过时不会产生任何写入
        Integer dayOfWeek = request.getDayOfWeek() == null
                ? course.getDayOfWeek()
                : requireDayOfWeek(request.getDayOfWeek());
        LocalTime startTime = request.getStartTime() == null
                ? course.getStartTime()
                : requireTime(request.getStartTime(), "startTime");
        LocalTime endTime = request.getEndTime() == null
                ? course.getEndTime()
                : requireTime(request.getEndTime(), "endTime");
        requireStartBeforeEnd(startTime, endTime);

        if (request.getCourseName() != null) {
            course.setCourseName(request.getCourseName());
        }
        if (request.getLocation() != null) {
            course.setLocation(request.getLocation());
        }
        course.setDayOfWeek(dayOfWeek);
        course.setStartTime(startTime);
        course.setEndTime(endTime);

        course.setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
        return toDTO(course);
    }

    /**
     * 删除课程。课程不存在或不属于当前用户时返回 false。
     */
    public boolean delete(Long userId, Long id) {
        return courseMapper.delete(new LambdaQueryWrapper<Course>()
                .eq(Course::getId, id)
                .eq(Course::getUserId, userId)) > 0;
    }

    /**
     * 实体 → DTO：LocalTime 按 HH:mm 转成字符串。
     */
    private CourseDTO toDTO(Course course) {
        CourseDTO dto = new CourseDTO();
        dto.setId(course.getId());
        dto.setCourseName(course.getCourseName());
        dto.setDayOfWeek(course.getDayOfWeek());
        dto.setStartTime(formatTime(course.getStartTime()));
        dto.setEndTime(formatTime(course.getEndTime()));
        dto.setLocation(course.getLocation());
        return dto;
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }

    /**
     * 校验星期：必须是 1~7。
     */
    private Integer requireDayOfWeek(Integer dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek < MIN_DAY_OF_WEEK || dayOfWeek > MAX_DAY_OF_WEEK) {
            throw new IllegalArgumentException("dayOfWeek 只能是 1-7 的数字");
        }
        return dayOfWeek;
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
            throw new IllegalArgumentException(fieldName + " 格式必须是 HH:mm，例如 08:00");
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
