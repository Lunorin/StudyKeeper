package studykeeper.dto;

import lombok.Data;

import java.util.List;

/**
 * POST /ai/plan（Python → Java）的响应体：{"tasks": [...]}。
 * Python 侧不套 code / message / data，只返回 tasks 数组，所以这里也只有一个字段。
 */
@Data
public class AiPlanResponse {

    /** 拆解出的任务列表，可能为空数组 */
    private List<TaskItem> tasks;

}
