package studykeeper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.mail.internet.MimeMessage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import studykeeper.common.JwtUtil;
import studykeeper.config.AsyncConfig;
import studykeeper.controller.AuthController;
import studykeeper.dto.LoginRequest;
import studykeeper.dto.LoginResponse;
import studykeeper.dto.RegisterRequest;
import studykeeper.dto.UserInfoDTO;
import studykeeper.entity.User;
import studykeeper.mapper.UserMapper;
import studykeeper.service.AuthService;
import studykeeper.service.EmailCodeService;
import studykeeper.service.EmailSender;

import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 临时验证（验证完即删）：密码强度校验 + 邮箱验证码注册链路 + 用户名登录兼容 + Controller 的错误码映射
 * + 验证码邮件异步发送。
 * 刻意不用 Spring Boot 容器 / 不用 Mockito / 不连库（手写 UserMapper、JavaMailSender 假实现），
 * 这样在机器内存紧张时也能跑起来；验证码不再靠 Mock 猜，而是从「发出去的邮件正文」里抠出来。
 * <p>
 * 唯一的例外是 asyncSend 那个用例：{@code @Async} 必须靠 Spring 代理才生效，
 * 所以它单独起了一个只注册 AsyncConfig + EmailCodeService + EmailSender 的极小 ApplicationContext
 * （不连库、不起 web、不走 Boot 自动装配）。其余用例里 {@code @Async} 不生效 —— 正好当同步调用用。
 */
class PasswordStrengthLightTempTests {

    private static final String MESSAGE = "密码必须包含数字、大小写字母，长度 8-20 位";
    private static final String CODE_INVALID = "验证码错误或已过期";
    private static final String EMAIL_REGISTERED = "该邮箱已被注册";
    private static final String EMAIL_NOT_REGISTERED = "该邮箱未注册";
    private static final String SEND_FAILED = "邮件发送失败，请稍后再试";
    private static final String FROM = "noreply@qq.com";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final FakeUserMapper userMapper = new FakeUserMapper();
    private final FakeMailSender mailSender = new FakeMailSender();
    private final EmailCodeService emailCodeService = new EmailCodeService(mailSender, FROM, 300, 60);
    private final AuthService authService =
            new AuthService(userMapper, new FakeJwtUtil(), encoder, emailCodeService);

    /**
     * 裸 new 出来的 EmailSender：单测里没有 Spring 代理，{@code @Async} 不生效 → sendAsync 就是同步调用，
     * 断言才能立刻读到「发出来的邮件」。真异步（换线程 + 不阻塞调用方）由 asyncSend... 那个用例单独验证。
     */
    private final EmailSender emailSender = new EmailSender(emailCodeService);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(authService, emailCodeService, emailSender))
            .build();

    /**
     * MP 的 lambda 缓存（User::getEmail → email 列名）平时由 MyBatis 启动时装配；
     * 纯单测（不起 Spring、不连库）里必须手动装一次，否则 wrapper.getSqlSegment() 会抛
     * 「can not find lambda cache for this entity」。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    /** 请求体：邮箱 + 验证码 + 密码（用户名不再传，由邮箱前缀自动生成） */
    private RegisterRequest request(String email, String code, String password) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setCode(code);
        request.setPassword(password);
        return request;
    }

    /** 真发一封（走 FakeMailSender）再把 6 位验证码从正文里抠出来 —— 生成 / 发信两步都走到 */
    private String sendCode(String email) {
        String code = emailCodeService.generateCode(email);
        emailSender.sendAsync(email, code);
        return mailSender.lastCode();
    }

    @Test
    void rejectsWeakPasswordsWithoutTouchingDatabase() {
        List<String> weak = List.of(
                "", "123456", "12345678", "abcdefgh", "ABCDEFGH",
                "Abcdefg", "Abcdefghijklmnopqrstu1",
                "Abcdefg1#", "Abcdefg1 ", "Abc 1234", "密码Abc123");

        int index = 0;
        for (String password : weak) {
            final String email = "weak_" + index + "@example.com";
            index++;
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request(email, "000000", password)),
                    "应被判为弱密码：" + password);
            assertEquals(MESSAGE, e.getMessage(), "文案要固定：" + password);
        }

        // 一个都不该写库，也不该查库 / 烧验证码（说明密码校验排在邮箱查重、验证码校验之前）
        userMapper.selectCountCalls = 0;
        assertThrows(IllegalArgumentException.class,
                () -> authService.register(request("weak_x@example.com", "000000", "abc123")));
        assertEquals(0, userMapper.selectCountCalls, "密码校验要先于邮箱查重 / 验证码校验");
        assertNull(userMapper.inserted, "弱密码不该写库");
        assertEquals(11, weak.size());
    }

    @Test
    void acceptsStrongPasswordsAndKeepsBcryptHashing() {
        List<String> strong = List.of("Abcdefg1", "Abcdefghij1234567890", "Abcd1234@", "A1b2C3d4$");

        int index = 0;
        for (String password : strong) {
            index++;
            String email = "strong_" + index + "@example.com";
            String code = sendCode(email);
            UserInfoDTO dto = authService.register(request(email, code, password));

            assertNotNull(dto, "强密码 + 正确验证码应该注册成功：" + password);
            // username / nickname 都由邮箱 @ 前面的部分自动生成
            assertEquals("strong_" + index, dto.getUsername());
            assertEquals("strong_" + index, dto.getNickname());
            User saved = userMapper.inserted;
            assertNotNull(saved);
            assertEquals(email, saved.getEmail(), "入库的是归一化（小写）后的邮箱");
            assertNotEquals(password, saved.getPassword(), "库里不能存明文");
            assertTrue(saved.getPassword().startsWith("$2a$"), "加密方式要还是 BCrypt：" + saved.getPassword());
            assertTrue(encoder.matches(password, saved.getPassword()), "BCrypt 哈希应能校验原密码");
            assertFalse(encoder.matches(password + "x", saved.getPassword()));
            assertEquals("strong_" + index, userMapper.lastCountedValue, "最后一次查重查的是生成的 username");
        }
    }

    @Test
    void emailCodeIsOneTimeAndRejectsWrongMissingOrExpired() {
        String email = "one_time@example.com";
        String code = sendCode(email);

        assertTrue(emailCodeService.verify(email, code), "刚发的验证码应该能过");
        assertFalse(emailCodeService.verify(email, code), "一次性：同一个码第二次必须失败");
        assertFalse(emailCodeService.verify("never_sent@example.com", code), "没发过码的邮箱直接失败");
        assertFalse(emailCodeService.verify(null, code));
        assertFalse(emailCodeService.verify(email, null));

        // 过期：有效期配成 0 秒 → 发出去就已经过期（不用 sleep，跑得快）
        EmailCodeService expiring = new EmailCodeService(mailSender, FROM, 0, 60);
        String expiredCode = expiring.generateCode("expired@example.com");
        expiring.sendEmail("expired@example.com", expiredCode); // 过期跟发信无关：这里直接调「只发信」那一步
        assertEquals(expiredCode, mailSender.lastCode(), "邮件正文里的码就是内存里存的那个");
        assertFalse(expiring.verify("expired@example.com", expiredCode), "过期验证码必须失败");
    }

    @Test
    void emailCodeRateLimitAndAsyncSendFailureOnlyLogs() {
        // 60 秒内不能重复发
        String email = "limit@example.com";
        sendCode(email);
        IllegalArgumentException tooFast = assertThrows(IllegalArgumentException.class,
                () -> emailCodeService.generateCode(email));
        assertTrue(tooFast.getMessage().contains("过于频繁"), "文案应提示太频繁：" + tooFast.getMessage());

        // 发信失败（SMTP 连不上）：异步路径只记日志、不往外抛 —— 接口那边早就返回成功了
        int sentBefore = mailSender.sent.size();
        mailSender.failNext = true;
        String failedCode = emailCodeService.generateCode("fail@example.com");
        emailSender.sendAsync("fail@example.com", failedCode); // 不抛异常（裸 new 时等价于同步调一次）
        assertEquals(sentBefore, mailSender.sent.size(), "发信失败不该被记成已发送");

        // 代价：异步失败不回滚内存里那条记录（刻意不做重试 / 回滚，见 docs/decisions.md §13.6），
        // 所以用户看到的是「发送成功」，但 60 秒限流照样拦着他 —— 只能等一会儿再重发
        mailSender.failNext = false;
        IllegalArgumentException stillLimited = assertThrows(IllegalArgumentException.class,
                () -> emailCodeService.generateCode("fail@example.com"),
                "异步失败不撤记录：限流窗口还在（与旧版「撤掉后能立刻重试」不同）");
        assertTrue(stillLimited.getMessage().contains("过于频繁"));

        // 没配发件人（环境变量 MAIL_USERNAME 为空）：同步阶段就报同一句固定文案，而且不占限流窗口
        EmailCodeService noSender = new EmailCodeService(mailSender, "  ", 300, 60);
        assertEquals(SEND_FAILED, assertThrows(IllegalArgumentException.class,
                () -> noSender.generateCode("no_sender@example.com")).getMessage());
        assertEquals(SEND_FAILED, assertThrows(IllegalArgumentException.class,
                        () -> noSender.generateCode("no_sender@example.com")).getMessage(),
                "配置错当场报错、不进限流：第二次也不该变成「太频繁」");
    }

    /**
     * 修复的正题：接口不该等 SMTP。
     * <p>
     * 「500 毫秒的慢发信」代表真实的 10 秒级 SMTP：sendAsync 必须立刻返回，邮件稍后由
     * emailExecutor（线程名前缀 email-send-）真的发出去。@Async 靠代理生效，所以这里起极小容器。
     */
    @Test
    void asyncSendReturnsImmediatelyAndRunsOnEmailExecutorThread() throws Exception {
        FakeMailSender slowMail = new FakeMailSender();
        slowMail.delayMillis = 500;
        EmailCodeService slowCodeService = new EmailCodeService(slowMail, FROM, 300, 60);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class);
            context.registerBean("emailCodeService", EmailCodeService.class, () -> slowCodeService);
            context.registerBean("emailSender", EmailSender.class);
            context.refresh();

            EmailSender asyncSender = context.getBean(EmailSender.class);
            String code = slowCodeService.generateCode("async@example.com");

            long startedAt = System.nanoTime();
            asyncSender.sendAsync("async@example.com", code);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertTrue(elapsedMillis < 200, "sendAsync 不该等 SMTP，应该立刻返回（实际 " + elapsedMillis + " 毫秒）");
            assertTrue(slowMail.awaitSent(5, TimeUnit.SECONDS), "邮件最终仍要发出去，只是不占请求线程");
            assertTrue(slowMail.firstSendThread != null && slowMail.firstSendThread.startsWith("email-send-"),
                    "发信要跑在 emailExecutor 的线程上，实际：" + slowMail.firstSendThread);
            assertEquals(code, slowMail.lastCode(), "发出去的码就是内存里存的那个");
        }
    }

    @Test
    void validationOrderAndCodeConsumedWhenEmailAlreadyRegistered() {
        userMapper.existingEmailCount = 1L; // 模拟「这个邮箱已经注册过」

        // 弱密码 + 已注册邮箱 → 先报密码：不查库、不烧验证码
        assertThrows(IllegalArgumentException.class,
                () -> authService.register(request("taken@example.com", "000000", "123456")));
        assertEquals(0, userMapper.selectCountCalls);
        assertNull(userMapper.inserted);

        // 强密码 + 正确验证码 + 邮箱已注册 → 返回 null（Controller 转 1001）
        String code = sendCode("taken@example.com");
        assertNull(authService.register(request("taken@example.com", code, "Abcdefg1")));
        assertEquals(1, userMapper.selectCountCalls, "只查了邮箱，没走到 username 查重");
        assertNull(userMapper.inserted);

        // 上面那次已经把验证码消耗掉了：同一个码再来 → 验证码错误（这是「验证码先于查重」的已知代价）
        IllegalArgumentException consumed = assertThrows(IllegalArgumentException.class,
                () -> authService.register(request("taken@example.com", code, "Abcdefg1")));
        assertEquals(CODE_INVALID, consumed.getMessage());
    }

    @Test
    void duplicateUsernameGetsNumericSuffix() {
        userMapper.takenUsernames.add("zhang"); // 库里已经有叫 zhang 的老用户

        String email = "zhang@example.com";
        String code = sendCode(email);
        UserInfoDTO dto = authService.register(request(email, code, "Abcdefg1"));

        assertNotNull(dto);
        assertEquals("zhang2", dto.getUsername(), "用户名撞车要自动加后缀，不能撞唯一索引报 500");
        assertEquals("zhang", dto.getNickname(), "昵称仍取邮箱前缀，不带后缀");
    }

    @Test
    void loginAcceptsUsernameForLegacyUsersAndEmailForNewOnes() {
        User legacy = new User();
        legacy.setId(7L);
        legacy.setUsername("student01");
        legacy.setPassword(encoder.encode("123456"));
        legacy.setNickname("老用户");
        userMapper.loginUser = legacy;

        // 老用户：account 不含 @ → 按 username 查（现有 5 个没有邮箱的账号就是这么登）
        LoginRequest byUsername = new LoginRequest();
        byUsername.setAccount("student01");
        byUsername.setPassword("123456");
        LoginResponse response = authService.login(byUsername);

        assertNotNull(response, "老用户的弱密码必须还能登录");
        assertEquals("fake-token", response.getToken());
        assertEquals(7L, response.getId());
        assertEquals("student01", userMapper.lastLoginValue, "不含 @ 时按 username 查");

        // 新用户：account 含 @ → 按 email 查，大小写不敏感（查的是归一化后的小写）
        LoginRequest byEmail = new LoginRequest();
        byEmail.setAccount("Student01@Example.com");
        byEmail.setPassword("123456");
        assertNotNull(authService.login(byEmail));
        assertEquals("student01@example.com", userMapper.lastLoginValue, "含 @ 时按 email 查（转小写）");

        // 空 account → null（不查库、不报错）
        LoginRequest blank = new LoginRequest();
        blank.setAccount("   ");
        blank.setPassword("123456");
        assertNull(authService.login(blank));
    }

    @Test
    void controllerMapsEmailRegisterErrorsAndSendsCode() throws Exception {
        // 弱密码 → 400（密码校验在最前面，不需要验证码）
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ctrl@example.com\",\"code\":\"000000\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertNull(userMapper.inserted);

        // 邮箱格式不对 → 400
        mockMvc.perform(post("/api/auth/send-email-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("邮箱格式不正确"));

        // 正常发码 → 0，且响应里不带验证码（码只在邮件正文里）
        mockMvc.perform(post("/api/auth/send-email-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ctrl@example.com\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        String code = mailSender.lastCode();
        assertNotNull(code, "应该真的发了一封带 6 位验证码的邮件");

        // 60 秒内重复发 → 400
        mockMvc.perform(post("/api/auth/send-email-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ctrl@example.com\"}"))
                .andExpect(jsonPath("$.code").value(400));

        // 带正确验证码注册 → 0，username / nickname 取邮箱前缀
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ctrl@example.com\",\"code\":\"" + code + "\",\"password\":\"Abcdefg1\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("ctrl"))
                .andExpect(jsonPath("$.data.nickname").value("ctrl"));
        assertEquals("ctrl@example.com", userMapper.inserted.getEmail());

        // 验证码错误 / 没发过 → 400
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ctrl3@example.com\",\"code\":\"000000\",\"password\":\"Abcdefg1\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(CODE_INVALID));
    }

    @Test
    void controllerReturns1001ForRegisteredEmailAndMapsLogin() throws Exception {
        // 邮箱已注册 → 1001
        String code = sendCode("ctrl2@example.com");
        userMapper.existingEmailCount = 1L;
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ctrl2@example.com\",\"code\":\"" + code + "\",\"password\":\"Abcdefg1\"}"))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(EMAIL_REGISTERED));
        assertNull(userMapper.inserted, "已注册邮箱不该写库");

        // 登录：老用户用用户名 → 0 + token；密码错 → 1002
        User legacy = new User();
        legacy.setId(7L);
        legacy.setUsername("student01");
        legacy.setPassword(encoder.encode("123456"));
        legacy.setNickname("老用户");
        userMapper.loginUser = legacy;
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"student01\",\"password\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("fake-token"));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"student01\",\"password\":\"wrong\"}"))
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    void forgotPasswordSendCodeRejectsUnregisteredEmailAndRateLimits() throws Exception {
        // 邮箱没注册过 → 400「该邮箱未注册」，且不发信（体验优先：明确告知）
        userMapper.existingEmailCount = 0L;
        int sentBefore = mailSender.sent.size();
        mockMvc.perform(post("/api/auth/forgot-password/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(EMAIL_NOT_REGISTERED));
        assertEquals(sentBefore, mailSender.sent.size(), "未注册邮箱不发信");

        // 邮箱格式不对 → 400
        mockMvc.perform(post("/api/auth/forgot-password/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad-email\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("邮箱格式不正确"));

        // 已注册 → 0，真的发了一封带验证码的信；响应里不带验证码
        userMapper.existingEmailCount = 1L;
        mockMvc.perform(post("/api/auth/forgot-password/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reset@example.com\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertEquals(sentBefore + 1, mailSender.sent.size(), "注册过的邮箱要发重置验证码");
        assertNotNull(mailSender.lastCode());

        // 60 秒内重复请求 → 400（文案来自 EmailCodeService，带剩余秒数）
        mockMvc.perform(post("/api/auth/forgot-password/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reset@example.com\"}"))
                .andExpect(jsonPath("$.code").value(400));

        // 旧路径 /api/auth/forgot-password 是同一个方法的别名 → 行为完全一致
        userMapper.existingEmailCount = 0L;
        mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(EMAIL_NOT_REGISTERED));
    }

    @Test
    void resetPasswordHappyPathAndGuards() throws Exception {
        String email = "reset@example.com";

        // 1) 邮箱没注册过 → 400（在验码之前就拦下，不消耗验证码）
        mockMvc.perform(post("/api/auth/forgot-password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"code\":\"000000\",\"newPassword\":\"NewPass1\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(EMAIL_NOT_REGISTERED));
        assertNull(userMapper.updated, "未注册邮箱不写库");

        // 2) 准备一个已注册用户（selectOne 走假实现，返回它）
        User registered = new User();
        registered.setId(9L);
        registered.setUsername("resetuser");
        registered.setEmail(email);
        registered.setPassword(encoder.encode("OldPass1"));
        userMapper.loginUser = registered;

        String code = sendCode(email);
        String wrongCode = "000000".equals(code) ? "000001" : "000000";

        // 3) 弱新密码 → 400 密码强度，且验证码没被消耗、不写库
        mockMvc.perform(post("/api/auth/forgot-password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code
                                + "\",\"newPassword\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(MESSAGE));
        assertNull(userMapper.updated, "弱密码不写库");

        // 4) 验证码不对 → 400「验证码错误或已过期」，同样不写库
        mockMvc.perform(post("/api/auth/forgot-password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + wrongCode
                                + "\",\"newPassword\":\"NewPass1\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(CODE_INVALID));
        assertNull(userMapper.updated, "验证码不对不写库");

        // 5) 正确验证码 + 强密码 → 0；密码换成 BCrypt(NewPass1)，且只带 id / password / updatedAt
        mockMvc.perform(post("/api/auth/forgot-password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code
                                + "\",\"newPassword\":\"NewPass1\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        User updated = userMapper.updated;
        assertNotNull(updated, "重置成功要写库");
        assertEquals(9L, updated.getId());
        assertTrue(updated.getPassword().startsWith("$2a$"), "加密方式仍是 BCrypt：" + updated.getPassword());
        assertTrue(encoder.matches("NewPass1", updated.getPassword()), "新密码应能通过 BCrypt 校验");
        assertFalse(encoder.matches("OldPass1", updated.getPassword()), "旧密码必须失效");
        assertNull(updated.getUsername(), "只更新 password：其它列不该被带上");
        assertNull(updated.getEmail(), "只更新 password：其它列不该被带上");
        assertNotNull(updated.getUpdatedAt());

        // 6) 验证码一次性：同一个码再来 → 400
        mockMvc.perform(post("/api/auth/forgot-password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code
                                + "\",\"newPassword\":\"Another1\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(CODE_INVALID));
    }

    /** JwtUtil 只用来签 token，登录逻辑不依赖它，这里避开真实密钥。 */
    private static class FakeJwtUtil extends JwtUtil {
        @Override
        public String generateToken(Long userId, String username) {
            return "fake-token";
        }
    }

    /**
     * 手写的 UserMapper 假实现：只实现 register / login 真正用得到的方法
     * （selectCount / insert / selectList），其余给空实现。
     * <p>
     * selectCount 兼了两种查重：参数里带 @ 当「查邮箱」，否则当「查用户名」——
     * 不用解析 wrapper 的列名也能分辨查的是哪一列。
     */
    private static class FakeUserMapper implements UserMapper {

        /** 已在库里的用户名：命中就返回 1（用来测「重名自动加后缀」） */
        final Set<String> takenUsernames = new HashSet<>();

        /** 便捷开关：> 0 表示「所有邮箱都算已注册」（用来测 1001） */
        long existingEmailCount = 0;

        /** 便捷开关：> 0 表示「所有用户名都被占用」 */
        long existingUsernameCount = 0;

        /** 最后一次查重用的值（断言查的是邮箱还是自动生成的 username） */
        String lastCountedValue;

        int selectCountCalls;

        /** insert 进来的用户（弱密码 / 邮箱已注册时应该一直是 null） */
        User inserted;

        /** updateById 传进来的实体（测「重置密码」用：应只带 id / password / updatedAt） */
        User updated;

        /** login 时 selectOne（MP 默认实现走 selectList）返回的用户 */
        User loginUser;

        /** login 查库用的值（断言「含 @ 按邮箱、否则按用户名」） */
        String lastLoginValue;

        @Override
        public Long selectCount(Wrapper<User> queryWrapper) {
            selectCountCalls++;
            String value = firstStringParam(queryWrapper);
            lastCountedValue = value;
            if (value == null) {
                return 0L;
            }
            if (value.contains("@")) {
                return existingEmailCount;
            }
            return takenUsernames.contains(value) ? 1L : existingUsernameCount;
        }

        @Override
        public int insert(User entity) {
            entity.setId(100L);
            this.inserted = entity;
            return 1;
        }

        @Override
        public List<User> selectList(Wrapper<User> queryWrapper) {
            lastLoginValue = firstStringParam(queryWrapper);
            return loginUser == null ? List.of() : List.of(loginUser);
        }

        /**
         * MP 的 {@code selectOne(Wrapper)} 默认实现要拿真实 MyBatis 代理（手写假实现会抛
         * MybatisPlusException），所以这里直接覆盖：记录查询条件 + 返回预设用户。
         */
        @Override
        public User selectOne(Wrapper<User> queryWrapper) {
            lastLoginValue = firstStringParam(queryWrapper);
            return loginUser;
        }

        /**
         * 取 wrapper 里第一个 String 参数（这些查询条件都只有一个值，够用了）。
         * <p>
         * 坑：MP 的参数值是**懒**放进 paramNameValuePairs 的 —— 不先调 getSqlSegment() 把 SQL 片段拼出来，
         * 那个 map 一直是空的（真跑 MyBatis 时是生成 SQL 的那一刻才填进去）。
         */
        private String firstStringParam(Wrapper<User> queryWrapper) {
            if (queryWrapper instanceof AbstractWrapper<?, ?, ?> wrapper) {
                wrapper.getSqlSegment();
                for (Object value : wrapper.getParamNameValuePairs().values()) {
                    if (value instanceof String text) {
                        return text;
                    }
                }
            }
            return null;
        }

        @Override
        public int deleteById(User entity) {
            return 0;
        }

        @Override
        public int delete(Wrapper<User> queryWrapper) {
            return 0;
        }

        @Override
        public int updateById(User entity) {
            this.updated = entity;
            return 1;
        }

        @Override
        public int update(User entity, Wrapper<User> updateWrapper) {
            return 0;
        }

        @Override
        public User selectById(Serializable id) {
            return null;
        }

        @Override
        public List<User> selectByIds(Collection<? extends Serializable> idList) {
            return List.of();
        }

        @Override
        public void selectByIds(Collection<? extends Serializable> idList, ResultHandler<User> resultHandler) {
            // 测试不会用到
        }

        @Override
        public Cursor<User> selectWithCursor(Wrapper<User> queryWrapper) {
            return null;
        }

        @Override
        public void selectList(Wrapper<User> queryWrapper, ResultHandler<User> resultHandler) {
            // 测试不会用到
        }

        @Override
        public List<User> selectList(IPage<User> page, Wrapper<User> queryWrapper) {
            return List.of();
        }

        @Override
        public void selectList(IPage<User> page, Wrapper<User> queryWrapper, ResultHandler<User> resultHandler) {
            // 测试不会用到
        }

        @Override
        public List<Map<String, Object>> selectMaps(Wrapper<User> queryWrapper) {
            return List.of();
        }

        @Override
        public void selectMaps(Wrapper<User> queryWrapper, ResultHandler<Map<String, Object>> resultHandler) {
            // 测试不会用到
        }

        @Override
        public List<Map<String, Object>> selectMaps(IPage<? extends Map<String, Object>> page,
                                                    Wrapper<User> queryWrapper) {
            return List.of();
        }

        @Override
        public void selectMaps(IPage<? extends Map<String, Object>> page, Wrapper<User> queryWrapper,
                               ResultHandler<Map<String, Object>> resultHandler) {
            // 测试不会用到
        }

        @Override
        public <E> List<E> selectObjs(Wrapper<User> queryWrapper) {
            return List.of();
        }

        @Override
        public <E> void selectObjs(Wrapper<User> queryWrapper, ResultHandler<E> resultHandler) {
            // 测试不会用到
        }
    }

    /**
     * 手写的 JavaMailSender 假实现：只记下发出去的邮件（不连真 SMTP），
     * 并且能把最近一封正文里的 6 位验证码抠出来 —— 所以测试不用 Mockito，也不用真的发信。
     */
    private static class FakeMailSender implements JavaMailSender {

        /** 正文里的第一串 6 位数字就是验证码 */
        private static final Pattern CODE_PATTERN = Pattern.compile("(\\d{6})");

        final List<SimpleMailMessage> sent = new ArrayList<>();

        /** true = 下一次发送失败（模拟 SMTP 连不上 / 授权码不对） */
        boolean failNext;

        /** 模拟真实 SMTP 的耗时（毫秒）：0（默认）= 立刻返回；只有异步用例会设成正数 */
        long delayMillis;

        /** 第一封邮件真的发出去时 countDown：异步用例靠它等结果 */
        private final CountDownLatch sentLatch = new CountDownLatch(1);

        /** 发第一封邮件时的线程名（异步用例断言它跑在 email-send-* 线程上） */
        volatile String firstSendThread;

        /** 最近一封邮件里的验证码；没发过或抠不出来返回 null */
        String lastCode() {
            if (sent.isEmpty()) {
                return null;
            }
            Matcher matcher = CODE_PATTERN.matcher(String.valueOf(sent.get(sent.size() - 1).getText()));
            return matcher.find() ? matcher.group(1) : null;
        }

        /** 等第一封邮件真的发出去（异步用例用；超时给得宽松，机器慢也不假失败） */
        boolean awaitSent(long timeout, TimeUnit unit) throws InterruptedException {
            return sentLatch.await(timeout, unit);
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            if (failNext) {
                throw new MailSendException("假失败：SMTP 连不上");
            }
            if (delayMillis > 0) {
                // 模拟 SMTP 那 10 秒级往返（真发信用不到，只有异步用例设它）
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MailSendException("假发信被中断");
                }
            }
            sent.add(simpleMessage);
            // 两个都是给异步用例读的：写完再 countDown，awaitSent 返回后就一定能看见（happens-before）
            firstSendThread = Thread.currentThread().getName();
            sentLatch.countDown();
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            for (SimpleMailMessage message : simpleMessages) {
                send(message);
            }
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            throw new UnsupportedOperationException("测试只走 SimpleMailMessage");
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException("测试只走 SimpleMailMessage");
        }

        @Override
        public MimeMessage createMimeMessage() {
            throw new UnsupportedOperationException("测试只走 SimpleMailMessage");
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            throw new UnsupportedOperationException("测试只走 SimpleMailMessage");
        }
    }
}
