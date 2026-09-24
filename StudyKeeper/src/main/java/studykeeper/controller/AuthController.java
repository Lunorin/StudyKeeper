package studykeeper.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.dto.ForgotPasswordSendRequest;
import studykeeper.dto.LoginRequest;
import studykeeper.dto.LoginResponse;
import studykeeper.dto.RegisterRequest;
import studykeeper.dto.ResetPasswordRequest;
import studykeeper.dto.SendEmailCodeRequest;
import studykeeper.dto.UserInfoDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.AuthService;
import studykeeper.service.EmailCodeService;
import studykeeper.service.EmailSender;

/**
 * 认证接口。
 * <p>
 * 注册 / 登录 / 发验证码 / 找回密码四条路径在 WebConfig 里放行（不需要 token），
 * logout 与 me 仍要 token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /** 发验证码只用到「生成 + 发信」，不碰用户表，所以直接注入它而不是绕 AuthService */
    private final EmailCodeService emailCodeService;

    /** 发信走它（内部是 @Async + emailExecutor 线程池）：SMTP 那 10 秒不能占着请求线程 */
    private final EmailSender emailSender;

    public AuthController(AuthService authService, EmailCodeService emailCodeService, EmailSender emailSender) {
        this.authService = authService;
        this.emailCodeService = emailCodeService;
        this.emailSender = emailSender;
    }

    /**
     * 给邮箱发注册验证码（注册流程第 1 步）。
     * <p>
     * 同步部分只有「校验 + 生成验证码 + 存内存」（毫秒级），**发信是异步的**：
     * 接口不等 SMTP，所以前端再也不会因为发信慢而触发 10 秒超时。
     * <p>
     * 邮箱格式不对 / 发得太频繁 / 没配发件人返回 400；发信失败（SMTP 连不上等）只写后端日志、
     * 用户侧仍是成功 —— 没收到就等 60 秒限流过后重发一次（见 docs/decisions.md §13）。
     * 响应里**不带验证码**。
     */
    @PostMapping("/send-email-code")
    public Result<Void> sendEmailCode(@RequestBody SendEmailCodeRequest request) {
        try {
            // 1. 生成 + 存内存：会抛异常的都在这一步（格式 / 太频繁 / 没配发件人），当场转 400
            String code = emailCodeService.generateCode(request.getEmail());
            // 2. 发信丢给 emailExecutor 线程池，立刻返回，不等 SMTP
            emailSender.sendAsync(request.getEmail(), code);
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 三类失败（格式 / 太频繁 / 没配发件人）文案都由 EmailCodeService 给，统一转 400
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 注册（邮箱 + 验证码 + 密码）。
     * 密码不满足强度要求、邮箱格式不对、验证码错误或已过期都返回 400；邮箱已被注册返回 1001。
     */
    @PostMapping("/register")
    public Result<UserInfoDTO> register(@RequestBody RegisterRequest request) {
        try {
            UserInfoDTO user = authService.register(request);
            if (user == null) {
                return Result.error(1001, "该邮箱已被注册");
            }
            return Result.success(user);
        } catch (IllegalArgumentException e) {
            // 校验写在 Service（docs/decisions.md §4.6）：这里只把密码强度 / 邮箱 / 验证码问题转成 400
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 登录。account 传邮箱（新用户）或用户名（老用户），账号或密码错误返回 1002。
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse loginUser = authService.login(request);
        if (loginUser == null) {
            return Result.error(1002, "账号或密码错误");
        }
        return Result.success(loginUser);
    }

    /**
     * 找回密码第 1 步：给**已注册**的邮箱发一条重置验证码。
     * <p>
     * 邮箱格式不对 / 该邮箱未注册 / 发得太频繁 / 没配发件人都返回 400；响应里**不带验证码**。
     * <p>
     * 与注册发码（1.4）同一套异步套路：AuthService 只负责「校验 + 生成 + 存内存」，发信交给
     * {@link EmailSender} 异步做，接口不等 SMTP（发信失败只写后端日志，用户侧仍是成功）。
     * <p>
     * 路径说明：{@code POST /api/auth/forgot-password} 是本接口的别名 —— 第 1 步先上过一版
     * 「未注册也静默成功」的占位实现，现在两处统一成同一行为（同一个方法，不再有两套语义）。
     * 前端新代码请直接用 {@code /api/auth/forgot-password/send-code}。
     */
    @PostMapping({"/forgot-password/send-code", "/forgot-password"})
    public Result<Void> sendForgotPasswordCode(@RequestBody ForgotPasswordSendRequest request) {
        try {
            // AuthService 那边只管「邮箱格式 / 有没有注册过 / 太频繁」，发信同样丢给线程池
            String code = authService.generateForgotPasswordCode(request.getEmail());
            emailSender.sendAsync(request.getEmail(), code);
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 邮箱格式 / 未注册 / 太频繁 / 没配发件人：文案都由 Service 给，统一转 400
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 找回密码第 2 步：校验验证码后重置密码。
     * <p>
     * 邮箱格式不对 / 该邮箱未注册 / 新密码不满足强度要求 / 验证码错误或已过期都返回 400。
     * 成功后密码换成新值（BCrypt），**不强制下线**：JWT 无状态，旧 token 到过期前仍可用。
     */
    @PostMapping("/forgot-password/reset")
    public Result<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            authService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword());
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 退出登录。JWT 是无状态的，服务端不需要做任何事，前端删掉本地 token 即可。
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success();
    }

    /**
     * 当前登录用户，用于前端启动时校验 token 是否还有效。
     * userId 由 JwtInterceptor 校验通过后放进 request attribute。
     */
    @GetMapping("/me")
    public Result<UserInfoDTO> me(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        UserInfoDTO user = authService.getUserById(userId);
        if (user == null) {
            return Result.error(401, "未登录");
        }
        return Result.success(user);
    }

}
