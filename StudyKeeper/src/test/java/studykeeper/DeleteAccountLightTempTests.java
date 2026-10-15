package studykeeper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import studykeeper.controller.UserController;
import studykeeper.entity.ChatMessage;
import studykeeper.entity.CompletionRecord;
import studykeeper.entity.Course;
import studykeeper.entity.Feedback;
import studykeeper.entity.RestTime;
import studykeeper.entity.Task;
import studykeeper.entity.TaskTemplate;
import studykeeper.entity.User;
import studykeeper.entity.UserMemory;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.mapper.ChatMessageMapper;
import studykeeper.mapper.CompletionRecordMapper;
import studykeeper.mapper.CourseMapper;
import studykeeper.mapper.FeedbackMapper;
import studykeeper.mapper.RestTimeMapper;
import studykeeper.mapper.TaskMapper;
import studykeeper.mapper.TaskTemplateMapper;
import studykeeper.mapper.UserMapper;
import studykeeper.mapper.UserMemoryMapper;
import studykeeper.service.UserService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 临时验证（验证完即删）：注销账号（DELETE /api/user/account）的验密码 + 级联物理删除 + 事务边界。
 * <p>
 * 刻意**不连库、不起 Spring 容器、不用 Mockito**（与 {@code PasswordStrengthLightTempTests} 同一套路）：
 * 9 个 Mapper 全用 JDK 动态代理造假实现（只实现 {@code delete}，把「调了哪张表、wrapper 长什么样」记下来），
 * 密码哈希用真的 {@link BCryptPasswordEncoder} 生成 —— BCrypt 那一环是真的在跑。
 * <p>
 * 覆盖不到的一点：{@code @Transactional} 真回滚需要真数据源，这里只能断言「注解在、rollbackFor 是 Exception」
 * 加上「中途失败会往外抛」（见最后两个用例）。
 */
class DeleteAccountLightTempTests {

    private static final Long USER_ID = 7L;

    /** 正确的明文密码（库里存的是它的 BCrypt 哈希） */
    private static final String PASSWORD = "Study2026a";

    private static final String WRONG_PASSWORD = "Wrong2026a";

    /** 删表顺序（断言 callLog 用）：子表在前、user 表最后 */
    private static final List<String> EXPECTED_TABLE_ORDER = List.of(
            "completion_record", "chat_message", "user_memory", "feedback",
            "task", "task_template", "course", "rest_time", "user");

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 所有假 Mapper 共用的「调用顺序表」（存的是一张张表名） */
    private final List<String> callLog = new ArrayList<>();

    private FakeUserMapper userMapper;
    private FakeDeleteMapper<CompletionRecordMapper> completionRecordMapper;
    private FakeDeleteMapper<ChatMessageMapper> chatMessageMapper;
    private FakeDeleteMapper<UserMemoryMapper> userMemoryMapper;
    private FakeDeleteMapper<FeedbackMapper> feedbackMapper;
    private FakeDeleteMapper<TaskMapper> taskMapper;
    private FakeDeleteMapper<TaskTemplateMapper> taskTemplateMapper;
    private FakeDeleteMapper<CourseMapper> courseMapper;
    private FakeDeleteMapper<RestTimeMapper> restTimeMapper;

    private UserService userService;
    private MockMvc mockMvc;

    /**
     * MP 的 lambda wrapper 是**懒**解析列名的：不先把 TableInfo 注册进缓存，{@code getSqlSegment()}
     * 会抛「can not find lambda cache for this entity」。真跑时这一步由 MP 启动扫 Mapper 完成，
     * 纯单元测试里得手工来一遍（与既有临时测试同一做法）。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(CompletionRecord.class, ChatMessage.class, UserMemory.class, Feedback.class,
                Task.class, TaskTemplate.class, Course.class, RestTime.class, User.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        callLog.clear();
        userMapper = new FakeUserMapper(callLog);
        completionRecordMapper = new FakeDeleteMapper<>(CompletionRecordMapper.class, "completion_record", callLog);
        chatMessageMapper = new FakeDeleteMapper<>(ChatMessageMapper.class, "chat_message", callLog);
        userMemoryMapper = new FakeDeleteMapper<>(UserMemoryMapper.class, "user_memory", callLog);
        feedbackMapper = new FakeDeleteMapper<>(FeedbackMapper.class, "feedback", callLog);
        taskMapper = new FakeDeleteMapper<>(TaskMapper.class, "task", callLog);
        taskTemplateMapper = new FakeDeleteMapper<>(TaskTemplateMapper.class, "task_template", callLog);
        courseMapper = new FakeDeleteMapper<>(CourseMapper.class, "course", callLog);
        restTimeMapper = new FakeDeleteMapper<>(RestTimeMapper.class, "rest_time", callLog);

        userService = new UserService(userMapper.mapper(), feedbackMapper.mapper(), taskMapper.mapper(),
                taskTemplateMapper.mapper(), courseMapper.mapper(), restTimeMapper.mapper(),
                completionRecordMapper.mapper(), chatMessageMapper.mapper(), userMemoryMapper.mapper(), encoder);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();
    }

    /** 造一条「库里已有的用户」：密码存 BCrypt 哈希，与真库口径一致 */
    private User existingUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("student01");
        user.setPassword(encoder.encode(PASSWORD));
        return user;
    }

    /** 8 张业务表（user 表之外）的假 Mapper，循环断言用 */
    private List<FakeDeleteMapper<?>> dataTableMappers() {
        return List.of(completionRecordMapper, chatMessageMapper, userMemoryMapper, feedbackMapper,
                taskMapper, taskTemplateMapper, courseMapper, restTimeMapper);
    }

    /** MP 的参数值是**懒**塞进 paramNameValuePairs 的：得先调 getSqlSegment() 把 SQL 拼出来 */
    private static String sqlOf(Wrapper<?> wrapper) {
        return wrapper instanceof AbstractWrapper<?, ?, ?> lambda ? lambda.getSqlSegment() : String.valueOf(wrapper);
    }

    private static List<Object> paramsOf(Wrapper<?> wrapper) {
        if (wrapper instanceof AbstractWrapper<?, ?, ?> lambda) {
            lambda.getSqlSegment();
            return new ArrayList<>(lambda.getParamNameValuePairs().values());
        }
        return List.of();
    }

    @Test
    void deleteAccountDeletesAllNineTablesInOrderByUserId() {
        userMapper.user = existingUser();

        userService.deleteAccount(USER_ID, PASSWORD);

        assertEquals(USER_ID, userMapper.lastSelectedId, "先用 token 里的 userId 查 user 表");
        assertEquals(EXPECTED_TABLE_ORDER, callLog, "删表顺序：子表在前、user 表最后");

        // 8 张业务表：各删一次，条件都是 user_id = 当前用户
        for (FakeDeleteMapper<?> fake : dataTableMappers()) {
            assertEquals(1, fake.wrappers.size(), "每张表只删一次");
            assertTrue(sqlOf(fake.wrappers.get(0)).contains("user_id ="), "按 user_id 删");
            assertTrue(paramsOf(fake.wrappers.get(0)).contains(USER_ID), "删的是当前用户的数据");
        }

        // user 表：按主键 id 删（表里没有 user_id 列，写成 User::getUserId 会生成非法 SQL）
        assertNotNull(userMapper.lastDelete, "user 表也要删掉，不能留孤儿数据");
        assertTrue(sqlOf(userMapper.lastDelete).contains("id ="), "user 表按 id 删");
        assertFalse(sqlOf(userMapper.lastDelete).contains("user_id"), "user 表没有 user_id 列");
        assertTrue(paramsOf(userMapper.lastDelete).contains(USER_ID), "删的正是当前用户");
    }


    @Test
    void wrongPasswordDeletesNothing() {
        userMapper.user = existingUser();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> userService.deleteAccount(USER_ID, WRONG_PASSWORD));

        assertEquals("密码错误", error.getMessage());
        assertTrue(callLog.isEmpty(), "密码不对时一张表都不能动");
    }

    @Test
    void blankPasswordDeletesNothing() {
        userMapper.user = existingUser();

        // 不传 password（null）
        assertEquals("密码不能为空", assertThrows(IllegalArgumentException.class,
                () -> userService.deleteAccount(USER_ID, null)).getMessage());
        // 传空串
        assertEquals("密码不能为空", assertThrows(IllegalArgumentException.class,
                () -> userService.deleteAccount(USER_ID, "")).getMessage());

        assertTrue(callLog.isEmpty(), "空密码时一张表都不能动");
    }

    @Test
    void unknownUserDeletesNothing() {
        userMapper.user = null;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> userService.deleteAccount(USER_ID, PASSWORD));

        assertEquals("用户不存在", error.getMessage());
        assertNull(userMapper.lastDelete, "用户不存在时连 user 表都不该删");
        assertTrue(callLog.isEmpty());
    }

    @Test
    void failureInTheMiddlePropagatesSoTheTransactionCanRollBack() {
        userMapper.user = existingUser();
        // 第 5 张表（task）删失败：异常必须往外抛，@Transactional(rollbackFor = Exception.class) 才会回滚
        taskMapper.failWith = new IllegalStateException("假失败：task 删不动");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> userService.deleteAccount(USER_ID, PASSWORD));

        assertEquals("假失败：task 删不动", error.getMessage());
        assertEquals(List.of("completion_record", "chat_message", "user_memory", "feedback", "task"), callLog,
                "失败前的表按顺序删过（生产里会被事务回滚），user 表没轮到");
    }

    @Test
    void deleteAccountIsTransactionalWithRollbackForException() throws Exception {
        Method method = UserService.class.getMethod("deleteAccount", Long.class, String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional, "整个删除必须在事务里");
        assertArrayEquals(new Class<?>[]{Exception.class}, transactional.rollbackFor(),
                "必须 rollbackFor = Exception.class（默认只回滚 RuntimeException）");
    }


    @Test
    void endpointReturnsSuccessAndTakesUserIdFromToken() throws Exception {
        userMapper.user = existingUser();

        mockMvc.perform(delete("/api/user/account")
                        .requestAttr(JwtInterceptor.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertEquals(USER_ID, userMapper.lastSelectedId, "userId 只能来自 token（@RequestAttribute）");
        assertEquals(EXPECTED_TABLE_ORDER, callLog, "走完接口后 9 张表全删");
    }

    @Test
    void endpointMapsWrongPasswordTo400() throws Exception {
        userMapper.user = existingUser();

        mockMvc.perform(delete("/api/user/account")
                        .requestAttr(JwtInterceptor.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + WRONG_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertTrue(callLog.isEmpty(), "密码错时接口返回 400，且不删任何数据");
    }

    @Test
    void endpointMapsMissingPasswordAndUnknownUserTo400() throws Exception {
        userMapper.user = existingUser();
        // 请求体里没有 password → 400「密码不能为空」（不会把 null 丢给 BCrypt）
        mockMvc.perform(delete("/api/user/account")
                        .requestAttr(JwtInterceptor.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码不能为空"));

        // token 还有效但用户已被删 → 400「用户不存在」
        userMapper.user = null;
        mockMvc.perform(delete("/api/user/account")
                        .requestAttr(JwtInterceptor.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户不存在"));

        assertTrue(callLog.isEmpty());
    }


    /**
     * 假 UserMapper（JDK 动态代理，不连库）：{@code selectById} 返回预置的那条用户，
     * {@code delete} 记下 wrapper 与顺序；其余方法一律抛异常 —— 真被调用说明业务代码碰了不该碰的 API。
     */
    private static final class FakeUserMapper implements InvocationHandler {

        /** 库里那条用户记录；null = 查不到（测「用户不存在」） */
        User user;

        /** selectById 收到的 id（断言 userId 确实来自 token） */
        Long lastSelectedId;

        /** 最后一次 delete 的 wrapper（断言删的是 user 表的 id 列） */
        Wrapper<User> lastDelete;

        private final List<String> callLog;

        FakeUserMapper(List<String> callLog) {
            this.callLog = callLog;
        }

        UserMapper mapper() {
            return (UserMapper) Proxy.newProxyInstance(UserMapper.class.getClassLoader(),
                    new Class<?>[]{UserMapper.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "selectById" -> {
                    lastSelectedId = args == null || args.length == 0 ? null : (Long) args[0];
                    yield user;
                }
                case "delete" -> {
                    callLog.add("user");
                    lastDelete = (Wrapper<User>) args[0];
                    yield 1;
                }
                case "toString" -> "FakeUserMapper";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("FakeUserMapper 没实现 " + method.getName());
            };
        }
    }

    /**
     * 通用假 Mapper（JDK 动态代理，不连库）：只实现 {@code delete} —— 顺序记进 callLog、wrapper 留档、
     * 返回配置的行数；{@code failWith} 非 null 时抛异常（测「中途失败 → 事务回滚」）。
     */
    private static final class FakeDeleteMapper<T> implements InvocationHandler {

        private final Class<T> mapperInterface;
        private final String table;
        private final List<String> callLog;

        /** delete 返回的行数：随便给个数，验证「影响 N 行」那条日志有值可打 */
        int rows = 3;

        /** 非 null = 这次 delete 抛异常 */
        RuntimeException failWith;

        final List<Wrapper<?>> wrappers = new ArrayList<>();

        FakeDeleteMapper(Class<T> mapperInterface, String table, List<String> callLog) {
            this.mapperInterface = mapperInterface;
            this.table = table;
            this.callLog = callLog;
        }

        T mapper() {
            return mapperInterface.cast(Proxy.newProxyInstance(mapperInterface.getClassLoader(),
                    new Class<?>[]{mapperInterface}, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "delete" -> {
                    callLog.add(table);
                    wrappers.add((Wrapper<?>) args[0]);
                    if (failWith != null) {
                        throw failWith;
                    }
                    yield rows;
                }
                case "toString" -> "Fake" + mapperInterface.getSimpleName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(
                        mapperInterface.getSimpleName() + " 假实现只支持 delete，被调用的是 " + method.getName());
            };
        }
    }
}

