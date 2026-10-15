package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DELETE /api/user/account 的请求体。
 * <p>
 * 只有一个 password：注销账号是**不可恢复**的操作，必须让用户重新输一遍密码。
 * 二次确认弹窗是前端的事（后端不拦「手滑」），后端只负责「验密码 + 删干净」。
 * <p>
 * 校验都写在 UserService.deleteAccount 里：password 为 null / 空串 → 400「密码不能为空」；
 * BCrypt 校验不通过 → 400「密码错误」（与登录的「不暴露账号是否存在」不同 —— 这里 token 已验证过身份，
 * 说清是密码错更好用）。
 */
@Data
@NoArgsConstructor
public class DeleteAccountRequest {

    /** 登录密码：明文只在这一次请求里出现，不落库、不打日志 */
    private String password;

}
