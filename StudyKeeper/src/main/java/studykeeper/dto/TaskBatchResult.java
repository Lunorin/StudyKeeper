package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/task/batch 的响应体：{planBatchId, createdCount, ids}。
 */
@Data
public class TaskBatchResult {

    /** 原样回显请求里的批次号 */
    private String planBatchId;

    /** 实际创建的任务条数，等于 ids 的长度 */
    private int createdCount;

    /** 新建任务的 id，顺序与请求里的 tasks 一致 */
    private List<Long> ids = new ArrayList<>();

}
