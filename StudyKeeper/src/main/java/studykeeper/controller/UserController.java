package studykeeper.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.common.UsernameExistsException;
import studykeeper.dto.DeleteAccountRequest;
import studykeeper.dto.OnboardingStatusDTO;
import studykeeper.dto.SubmitFeedbackRequest;
import studykeeper.dto.UpdateNotificationsRequest;
import studykeeper.dto.UpdatePersonaRequest;
import studykeeper.dto.UpdateProfileRequest;
import studykeeper.dto.UserProfileDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.UserService;

/**
 * 用户资料接口（右上角头像那套）：资料、通知设置、AI 角色（persona）、意见反馈、注销账号，外加新用户引导的两个状态接口。
 * <p>
 * 只做这几件事：不涉及改密码、不做头像文件上传（前端直接传 emoji 或 base64 字符串）、
 * 反馈**只提交**（不做列表查询 / 已读未读 / 回复），引导**只记完成 / 未完成**
 * （引导内容与走到第几步都是前端的事）。
 * 注销账号（{@link #deleteAccount}）走 `DELETE /api/user/account`：要带请求体（密码），
 * 验过密码后物理删掉该用户的全部数据 —— **二次确认弹窗是前端的事，后端只管验密码 + 删干净**。
 * 手机号与邮箱的绑定就直接走 {@link #updateProfile}，不另开接口；AI 角色（persona）单独一个接口
 * （{@link #updatePersona}），因为它是固定白名单取值、与资料字段的校验规则完全不同。
 * <p>
 * 这几个路径都被 {@code JwtInterceptor} 的 `/api/**` 覆盖，**{@code WebConfig} 不用改**：
 * 两个引导接口和资料接口一样需要 token，userId 一律从 token 取（`@RequestAttribute`），
 * 所以不存在「操作别人」的可能，也不需要额外的归属校验。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 查当前用户资料。userId 由 JwtInterceptor 校验通过后放进 request attribute。
     */
    @GetMapping("/profile")
    public Result<UserProfileDTO> profile(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        UserProfileDTO profile = userService.getProfile(userId);
        if (profile == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(profile);
    }

    /**
     * 改当前用户资料，只传要改的字段（null 表示不改）。
     * 参数不合法返回 400；username 与其他用户重复返回 1001；用户不存在返回 404。
     */
    @PutMapping("/profile")
    public Result<UserProfileDTO> updateProfile(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                                @RequestBody UpdateProfileRequest request) {
        try {
            UserProfileDTO updated = userService.updateProfile(userId, request);
            if (updated == null) {
                return Result.error(404, "用户不存在");
            }
            return Result.success(updated);
        } catch (UsernameExistsException e) {
            return Result.error(1001, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 改当前用户的通知设置。请求体 4 个开关都可选（null / 不传 = 不改），返回**更新后**的完整资料
     * （与 profile 接口同一个 UserProfileDTO，通知项是 Boolean）。
     * 用户不存在返回 404。
     */
    @PutMapping("/notifications")
    public Result<UserProfileDTO> updateNotifications(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                                      @RequestBody UpdateNotificationsRequest request) {
        UserProfileDTO updated = userService.updateNotifications(userId, request);
        if (updated == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(updated);
    }

    /**
     * 切换当前用户的 AI 角色（PUT /api/user/persona）。
     * <p>
     * 请求体只有 persona 一个字段，取值必须是 default / zeng_guofan / truth_doctor
     * （校验在 UserService 里做：非法值 400「无效的 persona」，空值 400「persona 不能为空」）。
     * 成功返回**更新后**的完整资料（与 profile 接口同一个 UserProfileDTO，persona 是其中一项），
     * 前端切完角色可以直接拿它刷新本地资料；用户不存在返回 404。
     */
    @PutMapping("/persona")
    public Result<UserProfileDTO> updatePersona(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                                @RequestBody UpdatePersonaRequest request) {
        try {
            UserProfileDTO updated = userService.updatePersona(userId, request.getPersona());
            if (updated == null) {
                return Result.error(404, "用户不存在");
            }
            return Result.success(updated);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 查当前用户是否已完成新用户引导（`{ "onboarded": true/false }`）。
     * <p>
     * 登录响应（POST /api/auth/login）里已经带了这个值，前端**登录后不用再发一次**；
     * 这个接口留给「刷新页面 / 点完引导想确认一下」的场景。
     * 不需要任何参数：userId 由 JwtInterceptor 从 token 取后放进 request attribute。
     * 用户不存在（token 还有效但人被删了）返回 404。
     */
    @GetMapping("/onboarding-status")
    public Result<OnboardingStatusDTO> onboardingStatus(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        Boolean onboarded = userService.getOnboardingStatus(userId);
        if (onboarded == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(new OnboardingStatusDTO(onboarded));
    }

    /**
     * 标记当前用户「已完成」新用户引导，返回 `Result.success()`（data 为 null）。
     * <p>
     * 幂等、无请求体：重复调用不会报错，前端走完引导最后一步调一次即可。
     * 只把 user.onboarded 置 1，**不记录引导步骤 / 完成时间**。
     */
    @PostMapping("/onboarding-complete")
    public Result<Void> completeOnboarding(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        userService.markOnboarded(userId);
        return Result.success();
    }

    /**
     * 提交意见反馈：content 不能为空 / 纯空白，最多 5000 字（校验在 Service 里，不合法返回 400）。
     * 只写库、不回显内容，成功时 data 为 null。
     */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                       @RequestBody SubmitFeedbackRequest request) {
        try {
            userService.submitFeedback(userId, request);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 注销账号（DELETE /api/user/account）：验密码 → 物理删除该用户的全部数据 → 返回 `Result.success()`。
     * <p>
     * 这是本项目**唯一带请求体的 DELETE**（其它 DELETE 都只有路径参数）：注销是不可恢复的高危操作，
     * 必须让用户重新输一遍密码再删（前端负责「真的要注销吗」的二次确认弹窗，后端不替它拦手滑）。
     * <p>
     * 请求体只有一个 `password`（见 {@link DeleteAccountRequest}），userId 由 JwtInterceptor 从 token 取，
     * **接口没有任何 userId 入参**，所以不存在「注销别人账号」的可能，也没有额外归属校验。
     * <p>
     * 失败一律 400：没带 / 空密码 → 「密码不能为空」；密码不对 → 「密码错误」；用户不存在 → 「用户不存在」。
     * 删除范围与顺序由 Service 的事务保证（9 张表全删或全不删，见 {@code UserService.deleteAccount}）——
     * **不是**软删、**不是**只删 user 表留一堆孤儿数据。
     */
    @DeleteMapping("/account")
    public Result<Void> deleteAccount(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                      @RequestBody DeleteAccountRequest request) {
        try {
            userService.deleteAccount(userId, request.getPassword());
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

}
