package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import studykeeper.common.JwtUtil;
import studykeeper.dto.LoginRequest;
import studykeeper.dto.LoginResponse;
import studykeeper.dto.RegisterRequest;
import studykeeper.dto.UserInfoDTO;
import studykeeper.entity.User;
import studykeeper.mapper.UserMapper;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 认证业务层：注册（邮箱 + 验证码 + 密码）、登录（邮箱或用户名）、按 id 查用户。
 * <p>
 * 注册：密码强度（8-20 位 + 大小写字母 + 数字）→ 邮箱格式 → 邮箱验证码（{@link EmailCodeService}）
 * → 邮箱是否已被注册 → 落库。user.username 是 **NOT NULL + UNIQUE**，而新流程前端不再传用户名，
 * 所以由邮箱 @ 前面的部分自动生成（重名自动加后缀），登录时邮箱和用户名都能用。
 * <p>
 * 登录：account 含 @ 按 email 查、否则按 username 查 —— **现有老用户（只有用户名、没有邮箱）照旧用
 * 用户名登录，不需要任何迁移**。登录**不校验密码强度**（库里可能存着老用户的弱密码，只做 BCrypt matches）。
 * 密码入库一律走 BCrypt（{@link BCryptPasswordEncoder}），本类没有任何明文落库的路径。
 * <p>
 * 验证码的错误（没发过 / 过期 / 不匹配）统一抛 {@link IllegalArgumentException}，文案固定
 * 「验证码错误或已过期」，由 Controller 转成 400；邮箱已被注册则返回 null（Controller 转成 1001）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * 密码强度：长度 8-20 位，且必须同时包含小写字母、大写字母、数字。
     * <p>
     * 拆开看正则的三段：
     * <ul>
     *   <li>{@code (?=.*[a-z])} 至少一个小写字母；</li>
     *   <li>{@code (?=.*[A-Z])} 至少一个大写字母；</li>
     *   <li>{@code (?=.*\d)} 至少一个数字；</li>
     *   <li>{@code [A-Za-z\d@$!%*?&]{8,20}} 整串只能是这些字符，长度 8-20。</li>
     * </ul>
     * 因为最后一段限死了字符集，用 {@code matches()} 整串匹配即可：含空格、中文、`#` 等
     * 其它特殊字符的密码都会被判不合法。
     */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,20}$");

    /** 密码不合规时给前端看的固定文案（400）：只说规则，不暴露是具体哪一项没满足。 */
    private static final String PASSWORD_INVALID_MESSAGE = "密码必须包含数字、大小写字母，长度 8-20 位";

    /** 验证码没发过 / 已过期 / 不匹配时的固定文案（400）：三种原因不区分，别让人猜到具体是哪一种。 */
    private static final String CODE_INVALID_MESSAGE = "验证码错误或已过期";

    /**
     * 找回密码时邮箱没注册过的固定文案（400）。
     * 这里按「体验优先」**明确告知**（不做「静默成功」）：用户填错邮箱能立刻发现。
     * 代价是接口可以被拿来探测某个邮箱是否注册过 —— 个人项目接受。
     */
    private static final String EMAIL_NOT_REGISTERED_MESSAGE = "该邮箱未注册";

    /** 邮箱格式：与 UserService、EmailCodeService 同一口径（有 @、有域名、有点），不追求 RFC 完整 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /** email 列是 varchar(100) */
    private static final int MAX_EMAIL_LENGTH = 100;

    /** username / nickname 列都是 varchar(50) */
    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MAX_NICKNAME_LENGTH = 50;

    /** 自动生成用户名时最多试到 base + 这个数字（99）；再撞就退回带时间戳的名字 */
    private static final int MAX_USERNAME_SUFFIX = 99;

    /** 新用户引导已完成时 user.onboarded 的值（TINYINT；0 = 未完成，是加列时的默认值） */
    private static final int ONBOARDED_DONE = 1;

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 邮箱验证码：注册时校验；找回密码时发码（发信细节都在那边） */
    private final EmailCodeService emailCodeService;

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil, BCryptPasswordEncoder passwordEncoder,
                       EmailCodeService emailCodeService) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.emailCodeService = emailCodeService;
    }

    /**
     * 注册：邮箱 + 验证码 + 密码。
     * <p>
     * 顺序刻意是「密码强度 → 邮箱格式 → 验证码 → 邮箱是否已注册」：把验证码放在查重之前，
     * 是因为它同时证明了「这个邮箱确实归你」—— 否则谁都能拿注册接口探测某个邮箱是否已注册
     * （和登录不区分「用户不存在 / 密码错」是同一个考虑）。代价：邮箱已注册时会消耗掉一次验证码，
     * 用户重新发一次即可。
     * <p>
     * 密码不符合强度 / 邮箱不合法 / 验证码不对 → 抛 {@link IllegalArgumentException}（Controller 转 400）；
     * 邮箱已注册 → 返回 null（Controller 转 1001）。
     * 密码用 BCrypt 加密后入库；nickname 为空时默认取邮箱 @ 前面的部分；
     * username 由邮箱前缀自动生成（user.username 是 NOT NULL + UNIQUE，登录时照样能用它）。
     */
    public UserInfoDTO register(RegisterRequest request) {
        // 先校验格式（密码强度 → 邮箱格式），再动业务规则：格式不对就不必查库、也不必烧掉验证码
        requireStrongPassword(request.getPassword());
        String email = requireEmail(request.getEmail());

        // 验证码是一次性的：校验通过立刻失效。没发过 / 过期 / 不匹配三种原因对外都用同一句话
        if (!emailCodeService.verify(email, request.getCode())) {
            throw new IllegalArgumentException(CODE_INVALID_MESSAGE);
        }

        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email));
        if (exists != null && exists > 0) {
            return null;
        }

        String nickname = requireNickname(request.getNickname(), email);

        User user = new User();
        user.setEmail(email);
        user.setUsername(availableUsername(emailPrefix(email)));
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(nickname);

        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        userMapper.insert(user);
        return new UserInfoDTO(user.getId(), user.getUsername(), user.getNickname());
    }

    /**
     * 校验注册密码强度（{@link #PASSWORD_PATTERN}）：null / 空串 / 不满足规则都抛
     * {@link IllegalArgumentException}，文案固定是 {@link #PASSWORD_INVALID_MESSAGE}。
     * <p>
     * 只在注册时调用 —— 登录<b>不</b>走这里：老用户的弱密码必须还能登进来（改密码走另一条路）。
     */
    private void requireStrongPassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException(PASSWORD_INVALID_MESSAGE);
        }
    }

    /**
     * 登录。account 含 @ 按邮箱查、否则按用户名查 —— 老用户（student01 等只有用户名、没有邮箱的）
     * 继续用用户名登录，新用户用邮箱登录，前端只传一个 account 字段即可。
     * <p>
     * 用户不存在或密码不匹配都返回 null（由 Controller 转成 1002，不区分具体原因）。
     */
    public LoginResponse login(LoginRequest request) {
        String account = request.getAccount() == null ? null : request.getAccount().trim();
        // 密码为 null 时 passwordEncoder.matches 会抛 IllegalArgumentException，所以先挡掉
        if (account == null || account.isEmpty() || request.getPassword() == null) {
            return null;
        }

        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        if (account.contains("@")) {
            // 邮箱不区分大小写：库里存的是小写，这里也降一次，别让大小写把老用户挡在门外
            query.eq(User::getEmail, account.toLowerCase(Locale.ROOT));
        } else {
            query.eq(User::getUsername, account);
        }
        User user = userMapper.selectOne(query);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return null;
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        // 引导状态随登录一起返回（方案 A）：前端登录后直接知道要不要弹引导，不用再补一次请求。
        // user.onboarded 是 TINYINT（1 = 已完成 / 0 = 未完成），列被写成 NULL 也按「未完成」算。
        // 注意：这里只多带一个字段，token 的生成与校验（上面的 generateToken）一行没动。
        boolean onboarded = user.getOnboarded() != null && user.getOnboarded() == ONBOARDED_DONE;
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getNickname(), onboarded);
    }

    /**
     * 找回密码第 1 步（POST /api/auth/forgot-password/send-code）：给该邮箱生成一条重置验证码。
     * <p>
     * 只做「校验 + 生成 + 存内存」，**不发信**：发信由 Controller 交给 {@link EmailSender} 异步做 ——
     * SMTP 是 10 秒级的，不能让它占着这个请求线程（见 docs/decisions.md §13）。
     * <p>
     * 邮箱格式不对 / **邮箱没注册过** / 发得太频繁 / 没配发件人都抛 {@link IllegalArgumentException}（400，直接给前端看）。
     * 验证码本身复用 {@link EmailCodeService}：6 位数字、5 分钟有效、同一邮箱 60 秒内不能重复发。
     *
     * @return 生成的 6 位验证码，由调用方转给 {@link EmailSender#sendAsync(String, String)} 去发
     */
    public String generateForgotPasswordCode(String email) {
        String target = requireEmail(email);

        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, target));
        if (exists == null || exists == 0) {
            log.info("[找回密码] 邮箱未注册，拒绝发码：{}", target);
            throw new IllegalArgumentException(EMAIL_NOT_REGISTERED_MESSAGE);
        }

        return emailCodeService.generateCode(target);
    }

    /**
     * 找回密码第 2 步（POST /api/auth/forgot-password/reset）：校验验证码后把密码换成 newPassword。
     * <p>
     * 顺序：邮箱格式 → 邮箱是否注册 → 新密码强度 → 验证码。
     * 前三步任一失败都**不会消耗验证码**（它只在 {@code verify} 成功时才被删掉），用户改完输入可以直接重试。
     * <p>
     * 写库只动 password（+ updated_at）两列，照旧 BCrypt 加密；**已有的 token 不作废**
     * （JWT 无状态、不做黑名单，等它自己过期），所以别的设备不会被踢下线。
     *
     * @throws IllegalArgumentException 邮箱不合法 / 邮箱未注册 / 新密码不合规 / 验证码错误或已过期
     */
    public void resetPassword(String email, String code, String newPassword) {
        String target = requireEmail(email);

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, target));
        if (user == null) {
            throw new IllegalArgumentException(EMAIL_NOT_REGISTERED_MESSAGE);
        }

        requireStrongPassword(newPassword);

        // 一次性：verify 成功即删除，所以重置成功后同一个码再也用不了
        if (!emailCodeService.verify(target, code)) {
            throw new IllegalArgumentException(CODE_INVALID_MESSAGE);
        }

        // updateById 默认跳过 null 字段：这里只带 password / updated_at，其它列一个都不碰
        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);

        // 日志里不带验证码、不带密码，只留一条「谁改了密码」便于排查
        log.info("[找回密码] 用户 {} 重置密码成功", user.getId());
    }

    /**
     * 按 id 查用户，供 GET /api/auth/me 使用。用户不存在返回 null。
     */
    public UserInfoDTO getUserById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return null;
        }
        return new UserInfoDTO(user.getId(), user.getUsername(), user.getNickname());
    }

    /**
     * 校验并归一化邮箱：空 / 超长 / 格式不对抛 {@link IllegalArgumentException}；返回 trim + 小写。
     * 归一化后的小写值**直接入库**（email 列是 ci 排序规则，本来就不区分大小写），
     * 这样「邮箱查重」「登录查找」「验证码 Map 的 key」三处口径完全一致。
     */
    private String requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        String value = email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("邮箱不能超过 " + MAX_EMAIL_LENGTH + " 个字符");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return value;
    }

    /**
     * 昵称：没传（null / 空白）就用邮箱 @ 前面的部分；传了则不能超过 varchar(50)。
     */
    private String requireNickname(String nickname, String email) {
        if (nickname == null || nickname.isBlank()) {
            return emailPrefix(email);
        }
        String value = nickname.trim();
        if (value.length() > MAX_NICKNAME_LENGTH) {
            throw new IllegalArgumentException("nickname 不能超过 " + MAX_NICKNAME_LENGTH + " 个字符");
        }
        return value;
    }

    /**
     * 取邮箱 @ 前面的部分（当默认昵称 / 默认用户名），并截断到 varchar(50)。
     */
    private String emailPrefix(String email) {
        int at = email.indexOf('@');
        String prefix = at > 0 ? email.substring(0, at) : email;
        return prefix.length() > MAX_USERNAME_LENGTH ? prefix.substring(0, MAX_USERNAME_LENGTH) : prefix;
    }

    /**
     * 给 username 挑一个没被占用的值：先用邮箱前缀，重名就依次试 base2、base3 …… base99，
     * 都不行再退回 base + 时间戳（几乎不可能走到）。
     * <p>
     * 为什么必须查重：user.username 是 NOT NULL + UNIQUE，而邮箱注册流程前端不再传用户名 ——
     * 不自动避让的话，第二个 zhang@xx.com 会直接撞唯一索引报 500。
     */
    private String availableUsername(String base) {
        if (!usernameExists(base)) {
            return base;
        }
        for (int i = 2; i <= MAX_USERNAME_SUFFIX; i++) {
            String candidate = withSuffix(base, String.valueOf(i));
            if (!usernameExists(candidate)) {
                return candidate;
            }
        }
        return withSuffix(base, String.valueOf(System.currentTimeMillis()));
    }

    /** 拼用户名后缀并保证总长不超过 varchar(50)（base 太长时从头截） */
    private String withSuffix(String base, String suffix) {
        int maxBaseLength = MAX_USERNAME_LENGTH - suffix.length();
        String head = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base;
        return head + suffix;
    }

    /** username 是否已被占用 */
    private boolean usernameExists(String username) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        return count != null && count > 0;
    }

}
