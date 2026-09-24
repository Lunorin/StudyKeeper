package studykeeper.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.common.UsernameExistsException;
import studykeeper.dto.OnboardingStatusDTO;
import studykeeper.dto.SubmitFeedbackRequest;
import studykeeper.dto.UpdateNotificationsRequest;
import studykeeper.dto.UpdateProfileRequest;
import studykeeper.dto.UserProfileDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.UserService;

/**
 * 用户资料接口（右上角头像那套）：资料、通知设置、意见反馈，外加新用户引导的两个状态接口。
 * <p>
 * 只做这几件事：不涉及改密码、不做头像文件上传（前端直接传 emoji 或 base64 字符串）、
 * 反馈**只提交**（不做列表查询 / 已读未读 / 回复），引导**只记完成 / 未完成**
 * （引导内容与走到第几步都是前端的事）。
 * 手机号与邮箱的绑定就直接走 {@link #updateProfile}，不另开接口。
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

}
