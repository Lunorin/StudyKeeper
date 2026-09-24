package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/auth/register 的请求体：邮箱 + 验证码 + 密码。
 * <p>
 * 流程：先调 POST /api/auth/send-email-code 拿验证码，再带着 code 调注册。
 * 密码规则不变（8-20 位 + 大小写字母 + 数字）。**用户名不再由前端传**：
 * user.username 是 NOT NULL + UNIQUE，注册时由邮箱 @ 前面的部分自动生成（重名会自动加后缀），
 * 登录时用它或邮箱都行。
 */
@Data
@NoArgsConstructor
public class RegisterRequest {

    /** 邮箱，同时是登录账号；服务端会 trim + 统一小写后入库 */
    private String email;

    /** 收到的 6 位验证码（一次性：校验通过后立即失效） */
    private String code;

    /** 密码，规则同旧版：8-20 位 + 大小写字母 + 数字 */
    private String password;

    /** 可选，为空时默认取邮箱 @ 前面的部分（最长 50 字符） */
    private String nickname;

}
