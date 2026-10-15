package studykeeper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studykeeper.common.UsernameExistsException;
import studykeeper.dto.SubmitFeedbackRequest;
import studykeeper.dto.UpdateNotificationsRequest;
import studykeeper.dto.UpdatePersonaRequest;
import studykeeper.dto.UpdateProfileRequest;
import studykeeper.dto.UserProfileDTO;
import studykeeper.entity.ChatMessage;
import studykeeper.entity.CompletionRecord;
import studykeeper.entity.Course;
import studykeeper.entity.Feedback;
import studykeeper.entity.RestTime;
import studykeeper.entity.Task;
import studykeeper.entity.TaskTemplate;
import studykeeper.entity.User;
import studykeeper.entity.UserMemory;
import studykeeper.mapper.ChatMessageMapper;
import studykeeper.mapper.CompletionRecordMapper;
import studykeeper.mapper.CourseMapper;
import studykeeper.mapper.FeedbackMapper;
import studykeeper.mapper.RestTimeMapper;
import studykeeper.mapper.TaskMapper;
import studykeeper.mapper.TaskTemplateMapper;
import studykeeper.mapper.UserMapper;
import studykeeper.mapper.UserMemoryMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户资料 + 通知设置 + 意见反馈 + 新用户引导状态 + 注销账号业务层。
 * <p>
 * 对应接口：GET / PUT /api/user/profile（资料）、PUT /api/user/notifications（通知设置）、PUT /api/user/persona（AI 角色）、
 * GET /api/user/onboarding-status（引导状态）、POST /api/user/onboarding-complete（标记引导完成）、
 * POST /api/user/feedback（意见反馈）、DELETE /api/user/account（注销账号）。
 * <p>
 * 边界：资料只读写 user 表里 nickname / username / avatar / phone / email / persona 这几个字段，
 * **不碰 password**（改密码单独做），也**不管注册 / 登录**（那是 AuthService 的事）；
 * 反馈只往 feedback 表插一条，**不做列表查询 / 不做已读未读 / 不做回复**（docs/api.md §12.4）；
 * 引导**只记「完成 / 未完成」一格**（user.onboarded），不做步骤记录、不做引导内容（那是前端的事）；
 * AI 角色（persona）**只做取值白名单校验**（default / zeng_guofan / truth_doctor），
 * 不存人格文案、不做人格管理（人格内容在 Python 侧 app/services/personas.py）；
 * 注销（{@link #deleteAccount}）是**唯一会删整行用户**的地方：验密码 → 物理删掉该用户 9 张表的全部数据
 * （**不用软删**，表里也没有 deleted 列），整个删除在一个事务里。
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

    /**
     * 默认人格：user.persona 的列默认值，也是用户没设置过 / 库里是脏值时统一按它算的那个值。
     * <p>
     * 与 AiService.DEFAULT_PERSONA、Python 侧 ChatRequest.persona 的默认值三处口径一致；
     * 都只有一行，各写一份（与 AI_SERVICE_ERROR_MESSAGE、jsonEntity 的做法一致，暂不抽公共类）。
     */
    private static final String DEFAULT_PERSONA = "default";

    /**
     * AI 角色（persona）白名单：与 Python 侧 app/services/personas.py 的 3 个人格一一对应 ——
     * default（默认）/ zeng_guofan（曾国藩）/ truth_doctor（直言不讳的大夫）。
     * <p>
     * 只有这 3 个值能写进 user.persona，其它一律 400。Python 侧**不校验**取值（未知值只回退默认人格），
     * 所以这里是唯一的把关点。
     */
    private static final List<String> PERSONAS = List.of(DEFAULT_PERSONA, "zeng_guofan", "truth_doctor");

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

    /**
     * 注销账号要删的那 7 张「用户数据表」的 Mapper（不算 user 与 feedback，那两个上面 / 下面各有字段）。
     * <p>
     * 顺序与 {@link #deleteAccount} 里删表的顺序一致：completion_record → chat_message → user_memory
     * → task → task_template → course → rest_time。
     */
    private final TaskMapper taskMapper;

    private final TaskTemplateMapper taskTemplateMapper;

    private final CourseMapper courseMapper;

    private final RestTimeMapper restTimeMapper;

    private final CompletionRecordMapper completionRecordMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final UserMemoryMapper userMemoryMapper;

    /**
     * BCrypt 校验器：与 AuthService 用的是**同一个 `@Bean` 单例**（`WebConfig.passwordEncoder`）。
     * 注销时用 `matches(明文, user.password)` 验密码，**不做任何明文比较**，也**不把密码写进日志**。
     */
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, FeedbackMapper feedbackMapper,
                       TaskMapper taskMapper, TaskTemplateMapper taskTemplateMapper,
                       CourseMapper courseMapper, RestTimeMapper restTimeMapper,
                       CompletionRecordMapper completionRecordMapper, ChatMessageMapper chatMessageMapper,
                       UserMemoryMapper userMemoryMapper, BCryptPasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.feedbackMapper = feedbackMapper;
        this.taskMapper = taskMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.courseMapper = courseMapper;
        this.restTimeMapper = restTimeMapper;
        this.completionRecordMapper = completionRecordMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.userMemoryMapper = userMemoryMapper;
        this.passwordEncoder = passwordEncoder;
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
     * 切换当前用户的 AI 角色（PUT /api/user/persona）。
     * <p>
     * 只改 user.persona 一格：入参必须是 {@link #PERSONAS} 里的 3 个值之一（trim 后比较，大小写敏感），
     * 非法值抛 {@link IllegalArgumentException}（由 Controller 转成 400）；用户不存在返回 null
     * （由 Controller 转成 404，与 updateProfile / updateNotifications 同一口径）。
     * <p>
     * 与另外两个写接口同一套路：非 null 字段整体写回（含没改的原值），**不碰 password**。
     */
    public UserProfileDTO updatePersona(Long userId, String persona) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        user.setPersona(requirePersona(persona));
        user.setUpdatedAt(LocalDateTime.now());

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
     * 注销账号（DELETE /api/user/account）：先验密码，再把该用户的**全部数据物理删除**，没有返回值。
     * <p>
     * 步骤：查 user → 不存在就抛 → 验密码 → 按「子表 → 主表」删 9 张表 → 删 user 自己。
     * 每张表删完都记一条 `[注销] 删除表 {}，影响 {} 行` 的日志（只记表名与行数，**不记密码、不记内容**），
     * 出事时对着日志就能看出删到第几张表、每张表删了多少行。
     * <p>
     * 失败一律抛 {@link IllegalArgumentException}（Controller 转成 400）：用户不存在 → 「用户不存在」；
     * password 为 null / 空串 → 「密码不能为空」（先拦一层，避免把 null 丢给 BCrypt）；
     * BCrypt 校验不通过 → 「密码错误」。**三种情况都不动数据库**。
     * <p>
     * <b>物理删除，不做软删</b>（表里也没有 deleted 列）；也**不是**只删 user 表 —— 那样会留下一堆孤儿数据，
     * 统计 / 记忆 / 聊天历史会继续算在「已经注销的人」头上。删除顺序（与 docs/decisions.md §16 一致）：
     * completion_record → chat_message → user_memory → feedback → task → task_template → course → rest_time → user。
     * 现在是「子表在前、主表在后」（本库只有索引、没有外键，顺序不吃亏；留着这个顺序是为了将来真加外键时也能过）。
     * <p>
     * 整个方法在一个事务里（`rollbackFor = Exception.class`）：任何一步抛异常，**前面已经删掉的表全部回滚**，
     * 不会出现「删了一半」的账号。注销后 token 仍可能没过期，但后续接口都会因为「用户不存在」返回 404，
     * 前端拿到成功响应后自己清 token 跳登录页。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAccount(Long userId, String password) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("密码错误");
        }

        // 走到这里说明密码没问题，后面 9 条 delete 要么全成功、要么全回滚
        log.info("[注销] 用户 {} 开始注销，准备删除其全部数据", userId);

        int completionRecordRows = completionRecordMapper.delete(new LambdaQueryWrapper<CompletionRecord>()
                .eq(CompletionRecord::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "completion_record", completionRecordRows);

        int chatMessageRows = chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "chat_message", chatMessageRows);

        int userMemoryRows = userMemoryMapper.delete(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "user_memory", userMemoryRows);

        int feedbackRows = feedbackMapper.delete(new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "feedback", feedbackRows);

        int taskRows = taskMapper.delete(new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "task", taskRows);

        int taskTemplateRows = taskTemplateMapper.delete(new LambdaQueryWrapper<TaskTemplate>()
                .eq(TaskTemplate::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "task_template", taskTemplateRows);

        int courseRows = courseMapper.delete(new LambdaQueryWrapper<Course>()
                .eq(Course::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "course", courseRows);

        int restTimeRows = restTimeMapper.delete(new LambdaQueryWrapper<RestTime>()
                .eq(RestTime::getUserId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "rest_time", restTimeRows);

        int userRows = userMapper.delete(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId));
        log.info("[注销] 删除表 {}，影响 {} 行", "user", userRows);

        log.info("[注销] 用户 {} 注销完成", userId);
    }

    /**
     * 校验 persona：只接受 {@link #PERSONAS} 里的 3 个值（先 trim，再逐字比较，大小写敏感）。
     * <p>
     * null / 空串 / 未知值都抛 {@link IllegalArgumentException}（由 Controller 转成 400）——
     * 「未知值回退默认人格」只发生在读侧（{@link #toPersona} / AiService.resolvePersona），写侧必须拦住。
     */
    private String requirePersona(String persona) {
        String value = persona == null ? "" : persona.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("persona 不能为空");
        }
        if (!PERSONAS.contains(value)) {
            throw new IllegalArgumentException("无效的 persona");
        }
        return value;
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
     * 实体 → DTO。只取资料字段（含 persona）+ 引导标记 + 4 个通知开关，password 永远不出现在响应里；
     * 通知开关与引导标记都在这里由 Integer（TINYINT 1/0）转成 Boolean，**转换只在这一处做**。
     */
    private UserProfileDTO toDTO(User user) {
        return new UserProfileDTO(user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatar(), user.getPhone(), user.getEmail(),
                toOnboarded(user.getOnboarded()),
                toBoolean(user.getNotifyTaskReminder()), toBoolean(user.getNotifyCourseReminder()),
                toBoolean(user.getNotifyDailyReport()), toBoolean(user.getNotifySound()),
                toPersona(user.getPersona()));
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
     * persona：NULL / 空白统一兜底成 {@link #DEFAULT_PERSONA}，保证响应里永远是非空字符串
     * （与 AiService.resolvePersona 同一口径）。列有 DEFAULT 'default'，正常查出来不会是 NULL，
     * 这里兜的是「老数据被显式写成 NULL / 脏数据」的情况。
     */
    private String toPersona(String persona) {
        return persona == null || persona.isBlank() ? DEFAULT_PERSONA : persona.trim();
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
