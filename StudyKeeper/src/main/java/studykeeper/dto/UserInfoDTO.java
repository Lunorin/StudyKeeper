package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户基本信息。注册响应（POST /api/auth/register）与 GET /api/auth/me 都用它。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoDTO {

    private Long id;

    private String username;

    private String nickname;

}
