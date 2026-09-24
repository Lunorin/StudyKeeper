package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/auth/forgot-password/send-code 的请求体：给这个邮箱发一条重置密码的验证码。
 * <p>
 * 与注册用的 {@link SendEmailCodeRequest} 字段一样（都只有一个 email），
 * 单独一个类是为了让「注册发码」和「找回发码」两条链路在文档 / 校验口径上能分开演进
 * （例如以后找回发码要加图形验证码，不用动注册）。
 */
@Data
@NoArgsConstructor
public class ForgotPasswordSendRequest {

    /** 注册时用的邮箱；没注册过的邮箱会被拒（400 该邮箱未注册） */
    private String email;

}
