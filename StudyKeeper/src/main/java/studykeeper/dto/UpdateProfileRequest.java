package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PUT /api/user/profile 的请求体。
 * 所有字段都是可选的：**为 null 表示不修改**（沿用各模块「只传要改的字段」的既有做法）。
 * 注意：password 不在这里，改密码是另一个接口。
 */
@Data
@NoArgsConstructor
public class UpdateProfileRequest {

    private String nickname;

    private String username;

    /** 头像：emoji 或 base64 字符串 */
    private String avatar;

    private String phone;

    private String email;

}
