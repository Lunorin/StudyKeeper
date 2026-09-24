package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/auth/login 的响应数据。
 * <p>
 * 刻意**只带这 5 个字段**（不再裹一层 UserProfileDTO）：前端的登录流程认的是 `data.token` 与
 * `data.id / username / nickname`，整体换结构会直接把登录打挂。加字段是向后兼容的。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private Long id;

    private String username;

    private String nickname;

    /**
     * 新用户引导是否已完成（库里 TINYINT 1/0，这里转成 Boolean）。
     * <p>
     * **登录响应直接带上**，前端登录成功后不用再发一次请求就能决定要不要弹引导；
     * 想让引导立刻生效（或刷新状态）时再调 `GET /api/user/onboarding-status`。
     */
    private Boolean onboarded;

}
