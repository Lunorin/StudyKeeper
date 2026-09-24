package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studykeeper.dto.TaskTemplateDTO;
import studykeeper.entity.Task;
import studykeeper.entity.TaskTemplate;
import studykeeper.mapper.TaskMapper;
import studykeeper.mapper.TaskTemplateMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 长期任务业务层：列表、新增、修改、删除。
 */
@Service
public class TaskTemplateService {

    private static final Logger log = LoggerFactory.getLogger(TaskTemplateService.class);

    /** 优先级为空时的兜底值，与表默认值保持一致。 */
    private static final String DEFAULT_PRIORITY = "medium";

    /** 难度默认值，与表默认值保持一致。 */
    private static final BigDecimal DEFAULT_DIFFICULTY = new BigDecimal("1.00");

    /** 默认启用。 */
    private static final int ACTIVE_YES = 1;

    /** 星期取值范围：1~7。 */
    private static final int MIN_DAY_OF_WEEK = 1;
    private static final int MAX_DAY_OF_WEEK = 7;

    /** 待完成状态：删模板时只清理这个状态的实例任务。 */
    private static final String STATUS_PENDING = "pending";

    /** 来源：由长期任务实例化生成。 */
    private static final String SOURCE_TEMPLATE = "template";

    private final TaskTemplateMapper taskTemplateMapper;

    private final TaskMapper taskMapper;

    public TaskTemplateService(TaskTemplateMapper taskTemplateMapper, TaskMapper taskMapper) {
        this.taskTemplateMapper = taskTemplateMapper;
        this.taskMapper = taskMapper;
    }

    /**
     * 当前用户的全部长期任务（含已停用的），按 id 升序。
     */
    public List<TaskTemplateDTO> list(Long userId) {
        List<TaskTemplate> templates = taskTemplateMapper.selectList(
                new LambdaQueryWrapper<TaskTemplate>()
                        .eq(TaskTemplate::getUserId, userId)
                        .orderByAsc(TaskTemplate::getId));

        List<TaskTemplateDTO> result = new ArrayList<>(templates.size());
        for (TaskTemplate template : templates) {
            result.add(toDTO(template));
        }
        return result;
    }

    /**
     * 新增长期任务。userId 来自 token，active 默认启用，difficulty 默认 1.00。
     * 如果今天正好命中 repeatDays，就顺手生成一条今日任务。
     * 模板和今日任务在同一事务内，要么都成功，要么都失败。
     * repeatDays 不合法时抛 IllegalArgumentException，由 Controller 转成 400。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskTemplateDTO create(Long userId, TaskTemplateDTO request) {
        // 先校验并规范化，非法时不会产生任何写入
        String repeatDays = formatRepeatDays(request.getRepeatDays());

        TaskTemplate template = new TaskTemplate();
        template.setUserId(userId);
        template.setTitle(request.getTitle());
        template.setDescription(request.getDescription());
        template.setDuration(request.getDuration());
        template.setPriority(request.getPriority() == null || request.getPriority().isBlank()
                ? DEFAULT_PRIORITY
                : request.getPriority());
        template.setRepeatDays(repeatDays);
        template.setDifficulty(DEFAULT_DIFFICULTY);
        template.setActive(ACTIVE_YES);

        LocalDateTime now = LocalDateTime.now();
        template.setCreatedAt(now);
        template.setUpdatedAt(now);

        taskTemplateMapper.insert(template);

        // 今天命中 repeatDays 就立刻生成今日任务（复用单模板实例化逻辑，同模板同一天不会重复）
        instantiateForToday(userId, template, LocalDate.now());

        return toDTO(template);
    }

    /**
     * 懒加载实例化：把这个用户今天该出现的长期任务都补生成成 task。
     * <p>
     * 由 GET /api/task/today 在查询前调用：取该用户 active=1 的模板（id 升序），逐个走单模板实例化规则。
     * 今天没命中 repeatDays、或当天已有该模板实例的都会跳过，所以重复调用安全。
     * <p>
     * 去重**不看状态**：pending / done / missed / 用户在今日页删掉后留下的 skipped 都算「今天已有」。
     * 因此用户删掉的长期任务实例不会被重新补生成（`TaskService.delete` 对模板实例用的是软删除 skipped）。
     * <p>
     * 整个方法在一个事务里：这批实例要么都生成，要么都不生成。
     *
     * @return 本次真正生成的任务条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int instantiateForToday(Long userId) {
        LocalDate today = LocalDate.now();

        List<TaskTemplate> templates = taskTemplateMapper.selectList(
                new LambdaQueryWrapper<TaskTemplate>()
                        .eq(TaskTemplate::getUserId, userId)
                        .eq(TaskTemplate::getActive, ACTIVE_YES)
                        .orderByAsc(TaskTemplate::getId));

        int created = 0;
        for (TaskTemplate template : templates) {
            if (instantiateForToday(userId, template, today)) {
                created++;
            }
        }

        log.info("[调试] 懒加载实例化：用户 {} 生成了 {} 条今日任务", userId, created);
        return created;
    }

    /**
     * 单个模板的实例化：今天（周一=1 …… 周日=7）命中 repeatDays，且当天还没有该模板的实例
     * （**任何状态**都算已有，含被软删除的 skipped）时，复制模板字段生成一条 pending 的今日任务。
     * <p>
     * 新增长期任务（create）与懒加载走的是同一份规则，避免两处各写一遍。
     * repeat_days 里的脏 token 由 parseRepeatDays 忽略，一条脏数据不会让今日任务接口失败。
     *
     * @param today 目标日期由调用方传入，保证一轮里「今天」只有一个取值
     * @return 是否真的生成了一条
     */
    private boolean instantiateForToday(Long userId, TaskTemplate template, LocalDate today) {
        int todayOfWeek = today.getDayOfWeek().getValue();

        if (!parseRepeatDays(template.getRepeatDays()).contains(todayOfWeek)) {
            return false;
        }

        // 去重：同模板同一天只要有一行记录就不再生成 —— **不过滤状态**。
        // pending / done / missed / 用户删掉后留下的 skipped 都算「今天已经生成过」，
        // 否则用户在今日页删掉的实例，下次打开今日页会被重新补出来（就是「删不掉」的原因）。
        Long exists = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId)
                .eq(Task::getTemplateId, template.getId())
                .eq(Task::getPlanDate, today));
        if (exists != null && exists > 0) {
            return false;
        }

        Task task = new Task();
        task.setUserId(userId);
        task.setTemplateId(template.getId());
        task.setTitle(template.getTitle());
        task.setDescription(template.getDescription());
        task.setDuration(template.getDuration());
        task.setPriority(template.getPriority());
        task.setPlanDate(today);
        task.setStatus(STATUS_PENDING);
        task.setSource(SOURCE_TEMPLATE);
        task.setDifficulty(template.getDifficulty());

        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        taskMapper.insert(task);
        return true;
    }

    /**
     * 修改长期任务，只允许改 title / description / duration / priority / repeatDays。
     * userId、active、difficulty 不通过本接口修改。
     * repeatDays 为 null 表示不修改，为空数组视为非法。
     * 任务不存在或不属于当前用户时返回 null。
     */
    public TaskTemplateDTO update(Long userId, Long id, TaskTemplateDTO request) {
        TaskTemplate template = taskTemplateMapper.selectById(id);
        if (template == null || !userId.equals(template.getUserId())) {
            return null;
        }

        // 先校验，避免校验失败前已经改了内存对象
        String repeatDays = request.getRepeatDays() == null
                ? null
                : formatRepeatDays(request.getRepeatDays());

        if (request.getTitle() != null) {
            template.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            template.setDescription(request.getDescription());
        }
        if (request.getDuration() != null) {
            template.setDuration(request.getDuration());
        }
        if (request.getPriority() != null) {
            template.setPriority(request.getPriority());
        }
        if (repeatDays != null) {
            template.setRepeatDays(repeatDays);
        }

        template.setUpdatedAt(LocalDateTime.now());
        taskTemplateMapper.updateById(template);
        return toDTO(template);
    }

    /**
     * 删除长期任务，同时清理该模板生成的、仍处于 pending 状态的实例任务，
     * 避免出现"删了长期任务，今日任务里还有"。
     * done / missed 的实例任务属于历史记录，保留不动。
     * 两步在同一事务内，任一步失败则一起回滚。
     * 模板不存在或不属于当前用户时返回 false，且不做任何删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long userId, Long id) {
        TaskTemplate template = taskTemplateMapper.selectById(id);
        if (template == null || !userId.equals(template.getUserId())) {
            return false;
        }

        // 1) 先删该模板生成的待完成任务（再加 userId 条件作为保险，实际与 templateId 一一对应）
        taskMapper.delete(new LambdaQueryWrapper<Task>()
                .eq(Task::getTemplateId, id)
                .eq(Task::getUserId, userId)
                .eq(Task::getStatus, STATUS_PENDING));

        // 2) 再删模板本身
        taskTemplateMapper.deleteById(id);
        return true;
    }

    /**
     * 实体 → DTO：repeatDays 由 "1,3,5" 转成 [1,3,5]，active 由 1/0 转成 true/false。
     */
    private TaskTemplateDTO toDTO(TaskTemplate template) {
        TaskTemplateDTO dto = new TaskTemplateDTO();
        dto.setId(template.getId());
        dto.setTitle(template.getTitle());
        dto.setDescription(template.getDescription());
        dto.setDuration(template.getDuration());
        dto.setPriority(template.getPriority());
        dto.setRepeatDays(parseRepeatDays(template.getRepeatDays()));
        dto.setDifficulty(template.getDifficulty());
        dto.setActive(template.getActive() != null && template.getActive() == ACTIVE_YES);
        return dto;
    }

    /**
     * [1,3,5] → "1,3,5"：去重 + 升序，并校验取值范围。
     */
    private String formatRepeatDays(List<Integer> days) {
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("repeatDays 至少选择一天");
        }

        Set<Integer> distinct = new LinkedHashSet<>();
        for (Integer day : days) {
            if (day == null || day < MIN_DAY_OF_WEEK || day > MAX_DAY_OF_WEEK) {
                throw new IllegalArgumentException("repeatDays 只能是 1-7 的数字");
            }
            distinct.add(day);
        }

        List<Integer> sorted = new ArrayList<>(distinct);
        sorted.sort(Comparator.naturalOrder());

        StringBuilder text = new StringBuilder();
        for (Integer day : sorted) {
            if (!text.isEmpty()) {
                text.append(",");
            }
            text.append(day);
        }
        return text.toString();
    }

    /**
     * "1,3,5" → [1,3,5]。宽容解析：空串、非数字、越界的 token 直接跳过，不抛异常，
     * 避免库里一条脏数据导致整个列表接口失败。
     */
    private List<Integer> parseRepeatDays(String raw) {
        List<Integer> days = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return days;
        }

        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int day = Integer.parseInt(trimmed);
                if (day >= MIN_DAY_OF_WEEK && day <= MAX_DAY_OF_WEEK && !days.contains(day)) {
                    days.add(day);
                }
            } catch (NumberFormatException ignored) {
                // 脏数据跳过
            }
        }

        days.sort(Comparator.naturalOrder());
        return days;
    }

}
