package studykeeper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studykeeper.dto.AiChatRequest;
import studykeeper.entity.ChatMessage;
import studykeeper.entity.Task;
import studykeeper.mapper.ChatMessageMapper;
import studykeeper.mapper.TaskMapper;
import studykeeper.service.AiService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时验证（验证完即删）：AI 主动监督 Step 1 —— Java 侧「今天第一条消息 + 昨天的完成情况」。
 * <p>
 * 与既有临时测试同一套路：**不连库、不起 Spring 容器、不用 Mockito**，两个 Mapper 用 JDK 动态代理造假实现
 * （只实现本步用到的 selectCount / selectList），只打 {@code AiService.isFirstMessageToday} 与
 * {@code AiService.buildYesterdaySummary} 这两个新方法 —— 所以构造 AiService 时其余依赖（课程 / 休息 /
 * 任务 / 聊天 / 记忆服务、RestTemplate）故意给 null：这两个方法碰不到它们，真被碰到就会 NPE 报出来。
 */
class AiYesterdayContextLightTempTests {

    private static final long USER_ID = 7L;

    private static final String BASE_URL = "http://localhost:8000";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FakeCountMapper chatMessageMapper;
    private FakeTaskMapper taskMapper;

    private AiService aiService;

    /**
     * MP 的 lambda wrapper 是懒解析列名的：不先注册 TableInfo，{@code getSqlSegment()} 会抛
     * 「can not find lambda cache for this entity」。真跑时由 MP 启动扫 Mapper 完成，这里手工来一遍。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(ChatMessage.class, Task.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        chatMessageMapper = new FakeCountMapper();
        taskMapper = new FakeTaskMapper();
        aiService = new AiService(null, null, null, null, null, null, null,
                taskMapper.mapper(), chatMessageMapper.mapper(), null, objectMapper, BASE_URL);
    }

    /** 造一条昨天的任务：只关心状态与时间，其余字段不影响本步的统计 */
    private static Task task(String status, String startTime, String endTime) {
        Task task = new Task();
        task.setPlanDate(LocalDate.now().minusDays(1));
        task.setStatus(status);
        task.setStartTime(startTime == null ? null : LocalTime.parse(startTime));
        task.setEndTime(endTime == null ? null : LocalTime.parse(endTime));
        return task;
    }

    /** MP 的参数值是懒塞进 paramNameValuePairs 的：得先调 getSqlSegment() 把 SQL 拼出来 */
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
    void firstMessageTodayIsTrueWhenNoUserMessageToday() {
        // 一条都没查着（selectCount 给 null 也当 0 处理）
        chatMessageMapper.count = null;
        assertTrue(aiService.isFirstMessageToday(USER_ID), "今天还没说过话 → 是今天第一条");

        chatMessageMapper.count = 0L;
        assertTrue(aiService.isFirstMessageToday(USER_ID));

        chatMessageMapper.count = 3L;
        assertFalse(aiService.isFirstMessageToday(USER_ID), "今天已经说过话 → 不是第一条");
    }

    @Test
    void firstMessageQueryLooksAtTodayUserMessagesOnly() {
        chatMessageMapper.count = 0L;
        aiService.isFirstMessageToday(USER_ID);

        String sql = sqlOf(chatMessageMapper.lastWrapper);
        List<Object> params = paramsOf(chatMessageMapper.lastWrapper);
        assertTrue(sql.contains("user_id ="), "只查当前用户：" + sql);
        assertTrue(sql.contains("role ="), "只看 user 角色：" + sql);
        assertTrue(sql.contains("created_at >="), "只看今天（>= 今天 00:00）：" + sql);
        assertTrue(params.contains(USER_ID), "参数里有 userId");
        assertTrue(params.contains("user"), "参数里有 role = user");
        assertTrue(params.contains(LocalDate.now().atStartOfDay()), "参数里有今天 00:00");
    }

    @Test
    void yesterdaySummaryCountsDoneMissedAndOverduePending() {
        // 昨天 10 条：2 条 done、2 条 missed、6 条 pending（昨天的任务都过点了 → 也算漏掉），
        // 再加 1 条脏数据（plan_date 为 null，判断不了日期 → 不算漏），一共 11 条
        List<Task> tasks = new ArrayList<>(List.of(
                task("09:00 语文", "done", "09:00", "10:00"),
                task("10:00 数学", "done", "10:00", "11:00"),
                task("11:00 英语", "missed", "11:00", "12:00"),
                task("12:00 物理", "missed", "12:00", "13:00"),
                task("13:00 化学", "pending", "13:00", "14:00"),
                task("14:00 生物", "pending", "14:00", "15:00"),
                task("15:00 背单词", "pending", "15:00", "16:00"),
                task("16:00 刷题", "pending", "16:00", "17:00"),
                task("17:00 复习", "pending", "17:00", "18:00"),
                task("18:00 没填时间", "pending", null, null)));
        Task dirty = new Task();
        dirty.setTitle("脏数据");
        dirty.setStatus("pending");
        dirty.setStartTime(LocalTime.parse("16:30"));
        dirty.setEndTime(LocalTime.parse("17:30"));
        tasks.add(dirty);
        taskMapper.tasks = tasks;

        AiChatRequest.YesterdaySummary summary = aiService.buildYesterdaySummary(USER_ID);

        assertEquals(LocalDate.now().minusDays(1).toString(), summary.getDate(), "date 是昨天");
        assertEquals(11, summary.getTotal(), "total = 查到的行数");
        assertEquals(2, summary.getCompleted(), "completed 只算 done");
        assertEquals(8, summary.getMissed(), "2 条 missed + 6 条过点的 pending");
        // missedTasks 只带前 5 条（按开始时间从早到晚），第 6 条之后（含没填时间的）不进来
        assertEquals(5, summary.getMissedTasks().size(), "最多 5 条明细");
        assertEquals(List.of("11:00 英语", "12:00 物理", "13:00 化学", "14:00 生物", "15:00 背单词"),
                summary.getMissedTasks().stream().map(AiChatRequest.MissedTask::getTitle).toList(),
                "按开始时间从早到晚");
        assertEquals("11:00", summary.getMissedTasks().get(0).getStartTime());
        assertEquals("12:00", summary.getMissedTasks().get(0).getEndTime());
    }

    @Test
    void yesterdaySummaryIsEmptyWhenNoTasks() {
        taskMapper.tasks = List.of();

        AiChatRequest.YesterdaySummary summary = aiService.buildYesterdaySummary(USER_ID);

        assertEquals(LocalDate.now().minusDays(1).toString(), summary.getDate());
        assertEquals(0, summary.getTotal());
        assertEquals(0, summary.getCompleted());
        assertEquals(0, summary.getMissed());
        assertNotNull(summary.getMissedTasks(), "没任务时是空数组，不是 null");
        assertTrue(summary.getMissedTasks().isEmpty());
    }

    @Test
    void missedTasksGiveEmptyStringsForMissingTitleAndTimes() {
        taskMapper.tasks = List.of(task(null, "pending", null, null));

        AiChatRequest.MissedTask brief = aiService.buildYesterdaySummary(USER_ID).getMissedTasks().get(0);

        assertEquals("", brief.getTitle(), "标题为空给空串");
        assertEquals("", brief.getStartTime(), "时间没有给空串（Python 侧是 str，给 null 会 422）");
        assertEquals("", brief.getEndTime());
    }

    @Test
    void yesterdayQueryFiltersByDateAndExcludesSkipped() {
        aiService.buildYesterdaySummary(USER_ID);

        String sql = sqlOf(taskMapper.lastWrapper);
        List<Object> params = paramsOf(taskMapper.lastWrapper);
        assertTrue(sql.contains("user_id ="), "只查当前用户：" + sql);
        assertTrue(sql.contains("plan_date ="), "只看昨天：" + sql);
        assertTrue(sql.contains("status <>"), "排除软删掉的长期任务实例：" + sql);
        assertTrue(params.contains(USER_ID));
        assertTrue(params.contains(LocalDate.now().minusDays(1)), "参数里是昨天的日期");
        assertTrue(params.contains("skipped"), "参数里是 skipped");
    }

    @Test
    void contextJsonUsesExpectedFieldNames() throws Exception {
        AiChatRequest.Context context = new AiChatRequest.Context();
        context.setIsFirstMessageToday(true);
        AiChatRequest.YesterdaySummary summary = new AiChatRequest.YesterdaySummary();
        summary.setDate("2026-10-14");
        summary.setTotal(3);
        summary.setCompleted(1);
        summary.setMissed(2);
        AiChatRequest.MissedTask missedTask = new AiChatRequest.MissedTask();
        missedTask.setTitle("背单词");
        missedTask.setStartTime("09:00");
        missedTask.setEndTime("09:30");
        summary.setMissedTasks(new ArrayList<>(List.of(missedTask)));
        context.setYesterdaySummary(summary);

        String json = objectMapper.writeValueAsString(context);

        // 关键：Boolean 包装类 + Lombok 生成 getIsFirstMessageToday()，JSON key 必须正好是 isFirstMessageToday
        assertTrue(json.contains("\"isFirstMessageToday\":true"), json);
        assertTrue(json.contains("\"yesterdaySummary\":{"), json);
        assertTrue(json.contains("\"date\":\"2026-10-14\""), json);
        assertTrue(json.contains("\"total\":3"), json);
        assertTrue(json.contains("\"completed\":1"), json);
        assertTrue(json.contains("\"missed\":2"), json);
        assertTrue(json.contains("\"missedTasks\":[{\"title\":\"背单词\",\"startTime\":\"09:00\","
                + "\"endTime\":\"09:30\"}]"), json);

        // 不是今天第一条消息时：false + 整个 yesterdaySummary 为 null（省 token）
        context.setIsFirstMessageToday(false);
        context.setYesterdaySummary(null);
        String laterMessageJson = objectMapper.writeValueAsString(context);
        assertTrue(laterMessageJson.contains("\"isFirstMessageToday\":false"), laterMessageJson);
        assertTrue(laterMessageJson.contains("\"yesterdaySummary\":null"), laterMessageJson);
    }


    /** 同上，顺便带上标题（断言明细顺序用） */
    private static Task task(String title, String status, String startTime, String endTime) {
        Task task = task(status, startTime, endTime);
        task.setTitle(title);
        return task;
    }

    /**
     * 假 ChatMessageMapper（JDK 动态代理，不连库）：selectCount 返回预置条数并记下 wrapper；
     * 其余方法一律抛异常 —— 本步只该查一次 count。
     */
    private static final class FakeCountMapper implements InvocationHandler {

        /** 预置的「今天 user 消息条数」；null = 一条没有 */
        Long count = 0L;

        /** 最后一次 selectCount 的 wrapper（断言查询条件用） */
        Wrapper<?> lastWrapper;

        ChatMessageMapper mapper() {
            return (ChatMessageMapper) Proxy.newProxyInstance(ChatMessageMapper.class.getClassLoader(),
                    new Class<?>[]{ChatMessageMapper.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "selectCount" -> {
                    lastWrapper = (Wrapper<?>) args[0];
                    yield count;
                }
                case "toString" -> "FakeChatMessageMapper";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(
                        "FakeChatMessageMapper 没实现 " + method.getName());
            };
        }
    }

    /**
     * 假 TaskMapper（JDK 动态代理，不连库）：selectList 返回预置的「昨天的任务」并记下 wrapper；
     * 其余方法一律抛异常 —— 本步只该查一次列表（写任务仍走 TaskService，本类不碰）。
     */
    private static final class FakeTaskMapper implements InvocationHandler {

        /** 预置的昨天任务列表 */
        List<Task> tasks = List.of();

        /** 最后一次 selectList 的 wrapper（断言查询条件用） */
        Wrapper<?> lastWrapper;

        TaskMapper mapper() {
            return (TaskMapper) Proxy.newProxyInstance(TaskMapper.class.getClassLoader(),
                    new Class<?>[]{TaskMapper.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "selectList" -> {
                    lastWrapper = (Wrapper<?>) args[0];
                    yield tasks;
                }
                case "toString" -> "FakeTaskMapper";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("FakeTaskMapper 没实现 " + method.getName());
            };
        }
    }
}

