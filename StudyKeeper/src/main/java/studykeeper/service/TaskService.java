package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studykeeper.dto.TaskBatchRequest;
import studykeeper.dto.TaskBatchResult;
import studykeeper.dto.TaskItem;
import studykeeper.dto.TaskStatusDTO;
import studykeeper.dto.TodayTasksDTO;
import studykeeper.entity.CompletionRecord;
import studykeeper.entity.Task;
import studykeeper.mapper.CompletionRecordMapper;
import studykeeper.mapper.TaskMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务业务层：手动创建、批量创建（AI 拆解确认）、今日列表、修改、删除、完成 / 取消完成。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** 来源：手动创建。 */
    private static final String SOURCE_MANUAL = "manual";

    /** 新建任务初始状态：待完成。 */
    private static final String STATUS_PENDING = "pending";

    /** 优先级为空时的兜底值，与表默认值保持一致。 */
    private static final String DEFAULT_PRIORITY = "medium";

    /** 难度默认值，与表默认值保持一致。 */
    private static final BigDecimal DEFAULT_DIFFICULTY = new BigDecimal("1.00");

    /** 已完成状态。 */
    private static final String STATUS_DONE = "done";

    /**
     * 已跳过（软删除）状态：用户删掉「长期任务生成的实例」时打这个标记。
     * <p>
     * 记录留着不删，是为了挡住懒加载重复生成 —— 去重看的是「同模板同一天有没有记录」，不看状态；
     * 今日任务列表与统计都排除这个状态，所以用户看不到它、也影响不到完成度。
     */
    private static final String STATUS_SKIPPED = "skipped";

    /** completion_record.action：标记完成。 */
    private static final String ACTION_DONE = "done";

    /** completion_record.action：取消完成。 */
    private static final String ACTION_UNDONE = "undone";

    /** 来源：AI 拆解后由用户批量确认创建。 */
    private static final String SOURCE_AI = "ai";

    /** 请求里的时间格式：HH:mm（与 docs/api.md §0.5 一致）。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final TaskMapper taskMapper;

    private final CompletionRecordMapper completionRecordMapper;

    /** 长期任务模板：今日任务列表在查询前要先按模板补生成当天该出现的实例（懒加载） */
    private final TaskTemplateService taskTemplateService;

    public TaskService(TaskMapper taskMapper, CompletionRecordMapper completionRecordMapper,
                       TaskTemplateService taskTemplateService) {
        this.taskMapper = taskMapper;
        this.completionRecordMapper = completionRecordMapper;
        this.taskTemplateService = taskTemplateService;
    }

    /**
     * 创建一条手动录入的任务。
     * 服务端可控字段（含 userId，来自 token）在此统一赋值，忽略请求体里传入的同名字段。
     */
    public Task create(Long userId, Task task) {
        return insertTask(userId, task, SOURCE_MANUAL, null);
    }

    /**
     * 批量创建 AI 拆解出来的任务（POST /api/task/batch）。
     * 每条都走与手动创建同一个 insertTask：status、difficulty、duration、时间戳的赋值只有一份逻辑，
     * 只是把 source 固定成 ai、带上请求里的 planBatchId，并用请求里的 planDate（而不是系统当天）。
     * 整个方法在一个事务里：任一条插入失败，这一批已插入的一起回滚。
     * planDate 为空、tasks 为空、条目的 title 为空或时间不是 HH:mm 时抛 IllegalArgumentException，由 Controller 转成 400。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskBatchResult createBatch(Long userId, TaskBatchRequest request) {
        LocalDate planDate = request.getPlanDate();
        if (planDate == null) {
            throw new IllegalArgumentException("planDate 不能为空");
        }
        List<TaskItem> items = request.getTasks();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("tasks 不能为空");
        }

        List<Long> ids = new ArrayList<>(items.size());
        for (TaskItem item : items) {
            Task task = new Task();
            task.setTitle(requireTitle(item));
            task.setStartTime(parseTime(item.getStartTime(), "startTime"));
            task.setEndTime(parseTime(item.getEndTime(), "endTime"));
            task.setPriority(item.getPriority());
            task.setPlanDate(planDate);

            // 插入后主键会写回 task.id，直接用返回值拿
            ids.add(insertTask(userId, task, SOURCE_AI, request.getPlanBatchId()).getId());
        }

        TaskBatchResult result = new TaskBatchResult();
        result.setPlanBatchId(request.getPlanBatchId());
        result.setCreatedCount(ids.size());
        result.setIds(ids);
        return result;
    }

    /**
     * 今日任务列表 + 完成度统计。
     * <p>
     * 查列表之前先做一次「懒加载实例化」：把今天该出现的长期任务补成 task，这样第二天打开今日页
     * 也能看到长期任务（实例化规则在 TaskTemplateService，同模板同一天不会重复生成）。
     * <p>
     * 列表与统计都**排除 `status = 'skipped'`**：那是用户在今日页「删掉」的长期任务实例（软删除），
     * 记录只为挡住懒加载重复生成，不该出现在界面上，也不该算进 total / percent。
     */
    public TodayTasksDTO todayTasks(Long userId) {
        taskTemplateService.instantiateForToday(userId);

        LocalDate today = LocalDate.now();
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId)
                .eq(Task::getPlanDate, today)
                .ne(Task::getStatus, STATUS_SKIPPED)
                // 没填开始时间的任务排在列表最后
                .last("ORDER BY start_time IS NULL, start_time, id"));

        int total = tasks.size();
        int completed = (int) tasks.stream()
                .filter(t -> STATUS_DONE.equals(t.getStatus()))
                .count();
        int percent = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);

        return new TodayTasksDTO(today, total, completed, percent, tasks);
    }

    /**
     * 修改任务，只允许改 title / description / startTime / endTime / priority。
     * 请求里为 null 的字段表示不修改；任务不存在或不属于当前用户时返回 null。
     */
    public Task update(Long userId, Long id, Task request) {
        Task task = taskMapper.selectById(id);
        if (task == null || !userId.equals(task.getUserId())) {
            return null;
        }

        if (request.getTitle() != null) {
            task.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            task.setDescription(request.getDescription());
        }
        if (request.getStartTime() != null) {
            task.setStartTime(request.getStartTime());
        }
        if (request.getEndTime() != null) {
            task.setEndTime(request.getEndTime());
        }
        if (request.getPriority() != null) {
            task.setPriority(request.getPriority());
        }

        // 起止时间有变动才重算 duration
        if (request.getStartTime() != null || request.getEndTime() != null) {
            task.setDuration(calculateDuration(task.getStartTime(), task.getEndTime()));
        }

        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    /**
     * 删除任务。按来源分两种情况：
     * <ul>
     *   <li><b>长期任务生成的实例</b>（`templateId` 非空）：**软删除**，把 `status` 改成 `skipped`，不物理删除。
     *       原因：今日列表每次查询前都会懒加载补生成「今天该出现的长期任务」，去重看的是
     *       「同模板同一天有没有记录」。如果真删掉这条记录，下次打开今日页就会又补出来，用户会觉得「删不掉」；
     *       留下一条 `skipped` 记录，去重就认为今天已经生成过了，不会再补。该状态在列表与统计里都被排除。</li>
     *   <li><b>手动 / AI 任务</b>（`templateId` 为空）：保持原来的物理删除。</li>
     * </ul>
     * completion_record 里的历史流水一律保留，不做级联删除。
     * 任务不存在或不属于当前用户时返回 false。
     */
    public boolean delete(Long userId, Long id) {
        Task task = taskMapper.selectById(id);
        if (task == null || !userId.equals(task.getUserId())) {
            return false;
        }

        if (task.getTemplateId() != null) {
            taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                    .eq(Task::getId, id)
                    .set(Task::getStatus, STATUS_SKIPPED)
                    .set(Task::getUpdatedAt, LocalDateTime.now()));
            log.info("[调试] task {} templateId={} 改成 skipped", id, task.getTemplateId());
            return true;
        }

        boolean deleted = taskMapper.delete(new LambdaQueryWrapper<Task>()
                .eq(Task::getId, id)
                .eq(Task::getUserId, userId)) > 0;
        if (deleted) {
            log.info("[调试] task {} 物理删除", id);
        }
        return deleted;
    }

    /**
     * 标记完成：task.status = done、completed_at = 当前时间，并追加一条 completion_record。
     * 两步在同一事务内，写流水失败则状态变更一起回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskStatusDTO done(Long userId, Long id) {
        Task task = taskMapper.selectById(id);
        if (task == null || !userId.equals(task.getUserId())) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        // 注意：completed_at 必须用 set 显式赋值，updateById 会跳过 null 字段
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, id)
                .set(Task::getStatus, STATUS_DONE)
                .set(Task::getCompletedAt, now)
                .set(Task::getUpdatedAt, now));
        saveCompletionRecord(userId, id, ACTION_DONE, now.toLocalDate());

        return new TaskStatusDTO(id, STATUS_DONE, now);
    }

    /**
     * 按关键词把「今天还没完成的任务」一次性标记为完成（AI 的 complete_task 工具用：用户说「数学作业都做完了」）。
     * <p>
     * 匹配口径（四个条件同时满足，刻意不做精确匹配、不跨天、不碰已完成的）：
     * {@code user_id = userId}、{@code plan_date = 今天}、{@code status = 'pending'}、{@code title LIKE %keyword%}。
     * 匹配到几条就全部处理，而不是只改第一条。
     * <p>
     * 每条都调现成的 {@link #done}：状态改 done、写 completed_at、追加 completion_record 只有那一份逻辑，
     * 这里不重复实现。整个方法在一个事务里，任何一条失败，这一批已改的（含流水）一起回滚，不会「改了一半」。
     * <p>
     * 匹配 0 条**不是错误**：返回空列表，由调用方决定怎么提示用户。
     *
     * @param keyword 关键词（首尾空白会去掉），为空时抛 IllegalArgumentException
     * @return 真正标记为完成的任务 id（按 id 升序）；一条都没匹配到就是空列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> completeByKeyword(Long userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword 不能为空");
        }

        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId)
                .eq(Task::getPlanDate, LocalDate.now())
                .eq(Task::getStatus, STATUS_PENDING)
                .like(Task::getTitle, keyword.trim())
                // id 升序：返回的 id 列表顺序稳定，便于排查
                .orderByAsc(Task::getId));

        List<Long> completedIds = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            // 复用现成的完成逻辑（status / completed_at / completion_record）
            if (done(userId, task.getId()) == null) {
                // 刚查出来又被删掉（并发）：整批回滚，不返回一个其实没改成功的 taskId
                throw new IllegalStateException("完成失败：任务不存在或不属于当前用户（taskId=" + task.getId() + "）");
            }
            completedIds.add(task.getId());
        }

        log.info("[调试] completeByKeyword 处理 {} 条任务：userId={}，keyword={}，taskIds={}",
                completedIds.size(), userId, keyword, completedIds);
        return completedIds;
    }

    /**
     * 取消完成：task.status = pending、completed_at 清空，并追加一条 completion_record。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskStatusDTO undo(Long userId, Long id) {
        Task task = taskMapper.selectById(id);
        if (task == null || !userId.equals(task.getUserId())) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, id)
                .set(Task::getStatus, STATUS_PENDING)
                .set(Task::getCompletedAt, null)
                .set(Task::getUpdatedAt, now));
        saveCompletionRecord(userId, id, ACTION_UNDONE, now.toLocalDate());

        return new TaskStatusDTO(id, STATUS_PENDING, null);
    }

    /**
     * 追加一条完成流水。
     */
    private void saveCompletionRecord(Long userId, Long taskId, String action, LocalDate recordDate) {
        CompletionRecord record = new CompletionRecord();
        record.setTaskId(taskId);
        record.setUserId(userId);
        record.setAction(action);
        record.setRecordDate(recordDate);
        record.setCreatedAt(LocalDateTime.now());
        completionRecordMapper.insert(record);
    }

    /**
     * 计算时长（分钟）。起止时间任一为空时返回 null；
     * endTime 不晚于 startTime 时按跨天处理，相当于 endTime 加 24 小时再算。
     */
    private Integer calculateDuration(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            return null;
        }
        long minutes = ChronoUnit.MINUTES.between(startTime, endTime);
        if (minutes <= 0) {
            minutes += 24 * 60;
        }
        return (int) minutes;
    }

    /**
     * 创建任务的公共实现：手动创建（POST /api/task）与批量创建（POST /api/task/batch）共用这一段，
     * 服务端可控字段统一在这里赋值，避免两处各写一遍。
     *
     * @param source      来源：manual 手动录入、ai AI 拆解确认
     * @param planBatchId 批次号，只有 AI 批量创建才有；手动创建传 null
     */
    private Task insertTask(Long userId, Task task, String source, String planBatchId) {
        task.setId(null);

        // 服务端可控字段
        task.setUserId(userId);
        task.setSource(source);
        task.setStatus(STATUS_PENDING);
        task.setDifficulty(DEFAULT_DIFFICULTY);

        // 模板本次不涉及，保持为 null
        task.setTemplateId(null);
        task.setPlanBatchId(planBatchId);

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
     * 解析请求里 HH:mm 的时间：空白当「没填」（任务允许没有具体时间），
     * 格式不是 HH:mm 时抛 IllegalArgumentException，由 Controller 转成 400。
     */
    private LocalTime parseTime(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(text.trim(), TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " 格式必须是 HH:mm，例如 09:00");
        }
    }

    /**
     * 批量创建时每条任务的 title 必填（表里 title 是必填列，先挡住避免插库时才报 500）。
     */
    private String requireTitle(TaskItem item) {
        if (item == null || item.getTitle() == null || item.getTitle().isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        return item.getTitle().trim();
    }

}
