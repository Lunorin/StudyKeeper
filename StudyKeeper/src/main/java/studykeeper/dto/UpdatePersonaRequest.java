package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PUT /api/user/persona 的请求体：`{ "persona": "zeng_guofan" }`。
 * <p>
 * 只有一个字段 —— 取值必须是 default / zeng_guofan / truth_doctor，
 * **校验在 UserService 里做**（非法值抛 IllegalArgumentException，Controller 转 400），
 * 这里只管接参数（与 UpdateProfileRequest / UpdateNotificationsRequest 同一套做法）。
 */
@Data
@NoArgsConstructor
public class UpdatePersonaRequest {

    /** AI 角色：default / zeng_guofan / truth_doctor */
    private String persona;

}
