package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/user/feedback 的请求体。
 * <p>
 * 只有一个 content：**不能为空 / 不能是纯空白，最多 5000 字**，
 * 校验手工写在 UserService 里（decisions §2.4），Controller 把
 * IllegalArgumentException 转成 400。
 */
@Data
@NoArgsConstructor
public class SubmitFeedbackRequest {

    /** 反馈内容 */
    private String content;

}
