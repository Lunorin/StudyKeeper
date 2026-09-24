package studykeeper.dto;

import lombok.Data;

/**
 * AI 拆解出来的单条任务。
 * 字段名与 Python 侧 /ai/plan 响应里 tasks 数组的元素一致（那边已经做过校验与归一化）。
 */
@Data
public class TaskItem {

    /** 任务标题 */
    private String title;

    /** 格式 HH:mm */
    private String startTime;

    /** 格式 HH:mm */
    private String endTime;

    /** 优先级：high / medium / low */
    private String priority;

}
