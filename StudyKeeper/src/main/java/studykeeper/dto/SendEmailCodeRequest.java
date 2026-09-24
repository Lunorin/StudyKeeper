package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/auth/send-email-code 的请求体：给这个邮箱发一条注册验证码。
 * <p>
 * 接口只回「发出去了没有」，**不把验证码返回给前端**（只进用户邮箱）。
 */
@Data
@NoArgsConstructor
public class SendEmailCodeRequest {

    /** 收件邮箱 */
    private String email;

}
