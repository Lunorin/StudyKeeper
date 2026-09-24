package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studykeeper.common.UsernameExistsException;
import studykeeper.dto.SubmitFeedbackRequest;
import studykeeper.dto.UpdateNotificationsRequest;
import studykeeper.dto.UpdateProfileRequest;
import studykeeper.dto.UserProfileDTO;
import studykeeper.entity.Feedback;
import studykeeper.entity.User;
import studykeeper.mapper.FeedbackMapper;
import studykeeper.mapper.UserMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 用户资料 + 通知设置 + 意见反馈 + 新用户引导状态业务层。
 * <p>
 * 对应接口：GET / PUT /api/user/profile（资料）、PUT /api/user/notifications（通知设置）、
 * GET /api/user/onboarding-status（引导状态）、POST /api/user/onboarding-complete（标记引导完成）、
 * POST /api/user/feedback（意见反馈）。
 * <p>
 * 边界：资料只读写 user 表里 nickname / username / avatar / phone / email 这几个字段，
 * **不碰 password**（改密码单独做），也**不管注册 / 登录**（那是 AuthService 的事）；
 * 反馈只往 feedback 表插一条，**不做列表查询 / 不做已读未读 / 不做回复**（docs/api.md §12.4）；
 * 引导**只记「完成 / 未完成」一格**（user.onboarded），不做步骤记录、不做引导内容（那是前端的事）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * avatar 大小上限：500KB = 512000 字节（按 UTF-8 字节数算，不是字符数）。
     * 前端传的是 emoji 或 base64 字符串，正常不会到这个量级，这里只是拦住恶意超长入参。
     * <p>
     * 列类型是 MEDIUMTEXT（上限 16MB），远大于 500KB，所以这条限制就是实际生效的天花板，
     * 写库不会再被 MySQL 拒。前端上传本地图片时应先压缩（建议 ≤ 400x400）再转 base64，
     * 别把几 MB 的原图直接传上来（见 docs/decisions.md §12.10 / §12.15）。
     */
    private static final int MAX_AVATAR_BYTES = 500 * 1024;

    /** nickname 对应 varchar(50)，先按字符数拦一层，避免直接撞库报 500 */
    private static final int MAX_NICKNAME_LENGTH = 50;

    /** username 对应 varchar(50)（不加最小长度限制：库里现有账号就有 2 个字的） */
    private static final int MAX_USERNAME_LENGTH = 50;

    /** phone 对应 varchar(20)：允许可选的前导 +，其余是数字（空格 / 短横线先被去掉） */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{5,20}$");

    private static final int MAX_PHONE_LENGTH = 20;

    /** email 对应 varchar(100)：只做「有 @、有域名、有点」的简单校验，不追求 RFC 完整 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private static final int MAX_EMAIL_LENGTH = 100;

    /** feedback.content 是 TEXT，接口层限 5000 字（反馈是短文本，不需要更长） */
    private static final int MAX_FEEDBACK_LENGTH = 5000;

    /** 通知开关在库里的两个取值（TINYINT） */
    private static final int NOTIFY_ON = 1;
    private static final int NOTIFY_OFF = 0;

    /**
     * 引导已完成时 user.onboarded 的值（TINYINT）。
     * 0 = 未完成，是加列时的默认值，所以常量里只声明「已完成」这一个。
     */
    private static final int ONBOARDED_DONE = 1;

    private final UserMapper userMapper;

    private final FeedbackMapper feedbackMapper;

    public UserService(UserMapper userMapper, FeedbackMapper feedbackMapper) {
        this.userMapper = userMapper;
        this.feedbackMapper = feedbackMapper;
    }

    /**
     * 查当前用户资料。用户不存在返回 null（由 Controller 转成 404）。
     */
    public UserProfileDTO getProfile(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? null : toDTO(user);
    }

    /**
     * 改当前用户资料，只允许改 nickname / username / avatar / phone / email，请求里为 null 的字段表示不改。
     * <p>
     * 先把所有要改的值校验完再写库，任何一项不合法都抛异常、**不产生半截写入**。
     * 用户不存在返回 null（404）；username 与他人重名抛 {@link UsernameExistsException}（1001）；
     * 其他参数不合法抛 {@link IllegalArgumentException}（400）。
     */
    public UserProfileDTO updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        // 先校验、后赋值：这样"某字段不合法"时不会出现前几个字段已改、后几个没改的情况
        String username = requireUsername(request.getUsername(), userId, user.getUsername());
        String nickname = request.getNickname() == null ? null : requireNickname(request.getNickname());
        String avatar = request.getAvatar() == null ? null : requireAvatar(request.getAvatar());
        String phone = request.getPhone() == null ? null : requirePhone(request.getPhone());
        String email = request.getEmail() == null ? null : requireEmail(request.getEmail());

        if (username != null) {
            user.setUsername(username);
        }
        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        if (phone != null) {
            user.setPhone(phone);
        }
        if (email != null) {
            user.setEmail(email);
        }

        user.setUpdatedAt(LocalDateTime.now());

        // updateById 默认只更新非 null 字段；这里字段要么是原值要么是新值，password 不会被改动
        userMapper.updateById(user);
        return toDTO(user);
    }

    /**
     * 改当前用户的通知设置（PUT /api/user/notifications）。
     * <p>
     * 4 个开关都是可选的：**null = 不改**，true → 1、false → 0。四个都是 null 时等于「什么都不改」，
     * 直接返回当前资料（照样刷新一次 updated_at，口径与资源接口的幂等 PUT 一致）。
     * 用户不存在返回 null（由 Controller 转成 404）。
     */
    public UserProfileDTO updateNotifications(Long userId, UpdateNotificationsRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        if (request.getTaskReminder() != null) {
            user.setNotifyTaskReminder(toFlag(request.getTaskReminder()));
        }
        if (request.getCourseReminder() != null) {
            user.setNotifyCourseReminder(toFlag(request.getCourseReminder()));
        }
        if (request.getDailyReport() != null) {
            user.setNotifyDailyReport(toFlag(request.getDailyReport()));
        }
        if (request.getSound() != null) {
            user.setNotifySound(toFlag(request.getSound()));
        }

        user.setUpdatedAt(LocalDateTime.now());

        // 同 updateProfile：非 null 字段整体写回（含刚改的开关与未改的原值），不碰 password
        userMapper.updateById(user);
        return toDTO(user);
    }

    /**
     * 查当前用户是否已完成新用户引导（GET /api/user/onboarding-status）。
     * <p>
     * 只读 user.onboarded 一格：库里是 TINYINT（1 = 已完成 / 0 = 未完成），这里转成 Boolean 给前端。
     * 用户不存在返回 null（由 Controller 转成 404）。
     */
    public Boolean getOnboardingStatus(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? null : toOnboarded(user.getOnboarded());
    }

    /**
     * 把当前用户的引导标记置为「已完成」（POST /api/user/onboarding-complete）。
     * <p>
     * 幂等：已经完成过再调一次也还是 1，不报错、不返回错误码 —— 前端可以放心重试。
     * 只写 onboarded / updated_at 两列（updateById 跳过 null 字段），其它列一个都不碰。
     * <p>
     * 用户不存在（token 还有效但人被删了）时**不报错**：这是幂等写，前端拿到成功即可，
     * 只留一条 WARN 日志便于排查。
     */
    public void markOnboarded(Long userId) {
        User update = new User();
        update.setId(userId);
        update.setOnboarded(ONBOARDED_DONE);
        update.setUpdatedAt(LocalDateTime.now());

        int rows = userMapper.updateById(update);
        if (rows == 0) {
            log.warn("[新用户引导] 用户 {} 不存在，标记完成未生效", userId);
        }
    }

    /**
     * 提交一条意见反馈（POST /api/user/feedback），只往 feedback 表插一条，没有返回值。
     * <p>
     * 校验：content 不能为 null、不能是纯空白，trim 后 ≤ {@link #MAX_FEEDBACK_LENGTH} 字，
     * 不合法抛 {@link IllegalArgumentException}（由 Controller 转成 400）。
     * 入库存 trim 后的值；created_at 由应用显式赋值，不依赖表默认值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitFeedback(Long userId, SubmitFeedbackRequest request) {
        String content = requireFeedbackContent(request.getContent());

        Feedback feedback = new Feedback();
        feedback.setUserId(userId);
        feedback.setContent(content);
        feedback.setCreatedAt(LocalDateTime.now());
        feedbackMapper.insert(feedback);
    }

    /**
     * 校验反馈内容：不能为空 / 纯空白，trim 后最长 5000 字。
     */
    private String requireFeedbackContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }

        String value = content.trim();
        if (value.length() > MAX_FEEDBACK_LENGTH) {
            throw new IllegalArgumentException("反馈内容不能超过 " + MAX_FEEDBACK_LENGTH + " 个字符");
        }
        return value;
    }

    /**
     * 实体 → DTO。只取资料字段 + 引导标记 + 4 个通知开关，password 永远不出现在响应里；
     * 通知开关与引导标记都在这里由 Integer（TINYINT 1/0）转成 Boolean，**转换只在这一处做**。
     */
    private UserProfileDTO toDTO(User user) {
        return new UserProfileDTO(user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatar(), user.getPhone(), user.getEmail(),
                toOnboarded(user.getOnboarded()),
                toBoolean(user.getNotifyTaskReminder()), toBoolean(user.getNotifyCourseReminder()),
                toBoolean(user.getNotifyDailyReport()), toBoolean(user.getNotifySound()));
    }

    /**
     * 引导标记：Integer（1/0）→ Boolean。
     * 与通知开关的 {@link #toBoolean(Integer)} **刻意不同**：列被显式写成 null 时按「未完成」算，
     * 因为对前端来说「缺省」必须落在要弹引导这一侧（弹过才知道用户想跳过）。
     */
    private Boolean toOnboarded(Integer flag) {
        return flag != null && flag == ONBOARDED_DONE;
    }

    /**
     * 通知开关：Integer（1/0）→ Boolean。null（理论上不会出现，列有默认值）原样返回 null。
     */
    private Boolean toBoolean(Integer flag) {
        return flag == null ? null : flag != NOTIFY_OFF;
    }

    /**
     * 通知开关：Boolean → Integer（true → 1、false → 0）。
     */
    private Integer toFlag(Boolean enabled) {
        return enabled ? NOTIFY_ON : NOTIFY_OFF;
    }

    /**
     * 校验 username（可选字段，没传返回 null）。
     * 重名判断：username = 新值 AND id != 当前 userId —— 排除自己，所以「原样提交」不算重名。
     */
    private String requireUsername(String username, Long userId, String currentUsername) {
        if (username == null) {
            return null;
        }

        String value = username.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        if (value.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("username 不能超过 " + MAX_USERNAME_LENGTH + " 个字符");
        }
        // 和现在一样就不查库了，省一次 SQL
        if (value.equals(currentUsername)) {
            return value;
        }

        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, value)
                .ne(User::getId, userId));
        if (exists != null && exists > 0) {
            throw new UsernameExistsException("用户名已存在");
        }
        return value;
    }

    /**
     * 校验 nickname：不能是空白，不能超过 varchar(50)。
     */
    private String requireNickname(String nickname) {
        String value = nickname.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("nickname 不能为空");
        }
        if (value.length() > MAX_NICKNAME_LENGTH) {
            throw new IllegalArgumentException("nickname 不能超过 " + MAX_NICKNAME_LENGTH + " 个字符");
        }
        return value;
    }

    /**
     * 校验 avatar：只限制大小（UTF-8 字节数），内容不做限制 —— 空串表示清掉头像。
     */
    private String requireAvatar(String avatar) {
        if (avatar.getBytes(StandardCharsets.UTF_8).length > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("avatar 不能超过 500KB");
        }
        return avatar;
    }

    /**
     * 校验 phone：允许清空（空串）；否则去掉空格 / 短横线后必须匹配 {@link #PHONE_PATTERN}。
     * 入库存归一化后的值（如 "138-0013-8000" → "13800138000"）。
     */
    private String requirePhone(String phone) {
        String value = phone.trim();
        if (value.isEmpty()) {
            return "";
        }

        String normalized = value.replace(" ", "").replace("-", "");
        if (normalized.length() > MAX_PHONE_LENGTH || !PHONE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("phone 格式不正确，只能是 5-20 位数字（可带前导 +）");
        }
        return normalized;
    }

    /**
     * 校验 email：允许清空（空串）；否则做简单格式校验（有 @、有域名、有点）。
     */
    private String requireEmail(String email) {
        String value = email.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("email 不能超过 " + MAX_EMAIL_LENGTH + " 个字符");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("email 格式不正确");
        }
        return value;
    }

}
