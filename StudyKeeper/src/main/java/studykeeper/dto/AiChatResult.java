package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/ai/chat 返回给前端的业务数据。
 * 只暴露结果：回复文案 + 这轮改到的任务 id；工具名与参数属于执行细节，不外传。
 */
@Data
public class AiChatResult {

    /** 模型给用户的回复；只有工具调用时可能是空串 */
    private String reply;

    /** 这轮实际改动的任务 id，按工具调用顺序；新增的是新 id（前端据此刷新任务列表） */
    private List<Long> affectedTaskIds = new ArrayList<>();

}
