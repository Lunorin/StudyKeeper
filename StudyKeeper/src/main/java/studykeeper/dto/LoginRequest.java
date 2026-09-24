package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/auth/login 的请求体。
 * <p>
 * account 一个字段两种含义：新用户传邮箱，老用户传用户名（如 student01）。
 * 服务端按「含 @ 就当邮箱，否则当用户名」自动判断 —— 所以**现有用户不用做任何迁移**，照旧登录。
 */
@Data
@NoArgsConstructor
public class LoginRequest {

    /** 邮箱或用户名；含 @ 按 email 查（忽略大小写），否则按 username 查 */
    private String account;

    private String password;

}
