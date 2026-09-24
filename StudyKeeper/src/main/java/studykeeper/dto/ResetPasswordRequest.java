package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/auth/forgot-password/reset 的请求体：用邮箱 + 验证码把密码换成新密码。
 * <p>
 * 验证码是 {@code /api/auth/forgot-password/send-code} 发出去的那一条，**一次性**：
 * 重置成功（或校验通过）后立刻失效。新密码规则与注册一致（8-20 位 + 大小写字母 + 数字）。
 */
@Data
@NoArgsConstructor
public class ResetPasswordRequest {

    /** 注册时用的邮箱 */
    private String email;

    /** 邮箱里收到的 6 位验证码 */
    private String code;

    /** 新密码：8-20 位，必须同时含小写字母、大写字母、数字 */
    private String newPassword;

}
