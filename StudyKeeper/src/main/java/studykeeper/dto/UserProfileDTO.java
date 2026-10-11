package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户资料。GET /api/user/profile 与 PUT /api/user/profile 都用它。
 * 只含资料字段，**不含 password**（改密码单独做）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {

    private Long id;

    private String username;

    private String nickname;

    /** 头像：emoji 或 base64 字符串 */
    private String avatar;

    private String phone;

    private String email;

    /**
     * 新用户引导是否已完成。
     * 库里是 Integer（TINYINT 1/0），**转成 Boolean 是 Service 的活**（UserService.toOnboarded），
     * 所以 GET / PUT /api/user/profile 的响应也带它 —— 前端登录后只看这一个值决定要不要弹引导，
     * 引导的具体内容与走到第几步都由前端自己管（后端只记完成 / 未完成）。
     */
    private Boolean onboarded;

    /**
     * 通知设置（4 项，前端做开关展示）。
     * 库里是 Integer（TINYINT 1/0），**转成 Boolean 是 Service 的活**（UserService.toDTO），
     * 所以 GET / PUT /api/user/profile 与 PUT /api/user/notifications 的响应都带这四项。
     */
    private Boolean notifyTaskReminder;

    private Boolean notifyCourseReminder;

    private Boolean notifyDailyReport;

    private Boolean notifySound;

    /**
     * AI 角色（persona）：default / zeng_guofan / truth_doctor，聊天接口据此决定 AI 用哪个人格。
     * <p>
     * 库里是 VARCHAR(50)，NULL / 空白由 Service 统一兜底成 'default'（{@code UserService.toPersona}），
     * 所以前端拿到的**永远是一个非空字符串**；改它走 PUT /api/user/persona。
     */
    private String persona;

}
