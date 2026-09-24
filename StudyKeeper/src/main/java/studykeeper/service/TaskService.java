package studykeeper.service;

import org.springframework.stereotype.Service;
import studykeeper.entity.Task;
import studykeeper.mapper.TaskMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 任务业务层，当前只包含"手动创建任务"这一个场景。
 */
@Service
public class TaskService {

    /** 登录功能未实现，userId 先硬编码为 1。 */
    private static final Long DEFAULT_USER_ID = 1L;

    /** 来源：手动创建。 */
    private static final String SOURCE_MANUAL = "manual";

    /** 新建任务初始状态：待完成。 */
    private static final String STATUS_PENDING = "pending";

    /** 优先级为空时的兜底值，与表默认值保持一致。 */
    private static final String DEFAULT_PRIORITY = "medium";

    /** 难度默认值，与表默认值保持一致。 */
    private static final BigDecimal DEFAULT_DIFFICULTY = new BigDecimal("1.00");

    private final TaskMapper taskMapper;

    public TaskService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 创建一条手动录入的任务。
     * 服务端可控字段在此统一赋值，忽略请求体里传入的同名字段。
     */
    public Task create(Task task) {
        task.setId(null);

        // 硬编码字段
        task.setUserId(DEFAULT_USER_ID);
        task.setSource(SOURCE_MANUAL);
        task.setStatus(STATUS_PENDING);
        task.setDifficulty(DEFAULT_DIFFICULTY);

        // 模板 / 计划批次本次不涉及，保持为 null
        task.setTemplateId(null);
        task.setPlanBatchId(null);

        if (task.getPriority() == null || task.getPriority().isBlank()) {
            task.setPriority(DEFAULT_PRIORITY);
        }

        // duration 由 startTime 与 endTime 计算，单位为分钟
        task.setDuration(calculateDuration(task.getStartTime(), task.getEndTime()));

        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        taskMapper.insert(task);
        return task;
    }

    /**
     * 计算时长（分钟）。起止时间任一为空时返回 null。
     */
    private Integer calculateDuration(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            return null;
        }
        return (int) ChronoUnit.MINUTES.between(startTime, endTime);
    }

}
