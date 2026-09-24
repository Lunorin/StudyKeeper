package studykeeper.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/task/batch 的请求体：用户确认 AI 拆解结果后，一次性提交多条任务。
 * tasks 的元素复用 TaskItem（title / startTime / endTime / priority），
 * 与 Python 侧 /ai/plan 返回的 tasks 结构一致，前端可以把 AI 结果（或用户改过的）直接回传。
 */
@Data
public class TaskBatchRequest {

    /** 批次号，由前端或 AI 服务生成，用于后续撤销 / 重排（原样存进 task.plan_batch_id） */
    private String planBatchId;

    /** 计划日期，格式 yyyy-MM-dd；这一批任务都排在这一天 */
    private LocalDate planDate;

    /** 要创建的任务列表，至少一条 */
    private List<TaskItem> tasks = new ArrayList<>();

}
