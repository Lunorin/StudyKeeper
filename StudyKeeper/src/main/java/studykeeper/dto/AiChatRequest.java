package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /ai/chat（Java → Python）的请求体。
 * 字段名与 Python 侧 Pydantic 模型 ChatRequest 完全一致（那边 extra="forbid"，
 * 多传一个字段立刻 422），所以这里只有 message / history / context / persona 四个字段。
 * <p>
 * 四个字段都由 AiService 填充：message 来自前端（POST /api/ai/chat 的请求体）、persona 从 user 表现查（NULL → "default"），
 * history 从 chat_message 查（该用户最近 20 条，正序），context 里的今日任务 / 课程表 /
 * 休息时段 / 用户画像记忆 / 今日空档一律现查 —— 前端传来的 history / context 都不看；
 * context 里另有「主动监督」用的两个字段（{@code isFirstMessageToday} 与 {@code yesterdaySummary}，
 * 见 {@link Context}），也是 Java 侧现算的。
 * <p>
 * 注意：Python 侧 ChatRequest 是 {@code extra="forbid"}（顶层多一个字段就 422），但**嵌套的 ChatContext 不是**
 * （Python 侧对 context 里多出来的字段是「忽略」）—— 所以往 {@link Context} 里加字段不会把聊天打 422，
 * Python 侧到下一步才解析这两个字段。
 */
@Data
public class AiChatRequest {

    /** 用户这句话，最长 2000 字（与 Python 侧 MAX_MESSAGE_LENGTH 一致） */
    private String message;

    /** 历史消息，最多带最近 50 条（与 Python 侧 MAX_HISTORY 一致） */
    private List<ChatMessage> history = new ArrayList<>();

    /** 上下文，放今日任务 / 课程表 / 休息时段 / 空档；Python 侧用它告诉模型「今天有哪些任务、哪些课、哪些休息、还剩哪些空档」 */
    private Context context = new Context();

    /**
     * AI 角色（persona）：default / zeng_guofan / truth_doctor，决定 Python 侧 system prompt 开头的
     * 「人格设定」段（StudyKeeper-ai/app/services/personas.py）。
     * <p>
     * 由 AiService 从 user 表现查后填充（{@code AiService.resolvePersona}）：用户没设置过（NULL / 空白）
     * 时填 {@code "default"}，所以这个字段**永远是三个合法值之一**（兜底值见字段默认值，与
     * user.persona 列默认值、Python 侧 ChatRequest.persona 默认值三处口径一致）。
     * <p>
     * 字段名与 Python 侧 ChatRequest.persona 逐字一致（那边 extra="forbid"，改名会直接 422）；
     * 取值合法性由 Java 侧写入口把关，Python 侧对未知值只会回退默认人格。
     */
    private String persona = "default";

    /**
     * 一条历史消息，对应 Python 侧 ChatMessage。
     */
    @Data
    public static class ChatMessage {

        /** "user" 或 "assistant" */
        private String role;

        /** 消息内容；没有内容时给空串（Python 侧不接受 null） */
        private String content;

    }

    /**
     * /ai/chat 的上下文，对应 Python 侧 ChatContext：
     * 今日任务 / 今日课程表 / 今日休息时段 / 今日空档（今天的时间安排）+ 用户画像记忆（这个用户是谁）
     * + 「主动监督」用的两个字段（今天是不是第一条消息、昨天做得怎么样）。
     */
    @Data
    public static class Context {

        /** 今日任务，最多 200 条（与 Python 侧 MAX_CHAT_TASKS 一致） */
        private List<TodayTask> todayTasks = new ArrayList<>();

        /** 今日课程表（只含当天星期有课的），最多 50 条（与 Python 侧 MAX_CHAT_COURSES 一致） */
        private List<TodayCourse> todayCourses = new ArrayList<>();

        /** 今天生效的休息时段（dayOfWeek 为 null 或等于今天），最多 50 条（与 Python 侧 MAX_CHAT_REST_TIMES 一致） */
        private List<TodayRestTime> todayRestTimes = new ArrayList<>();

        /**
         * 用户画像记忆（user_memory）：每类最多 3 条，最多 15 条左右。
         * 由 AiService 现查 user_memory 填充，Python 侧把它注入 system prompt 里当作「对这个用户的了解」。
         * 没有记忆时是空数组（不是 null）。
         */
        private List<UserMemoryItem> userMemories = new ArrayList<>();

        /**
         * 今日空档（可用时段）：一天总时段（08:00-22:00）减去课程表、休息时间与今日已有任务，
         * 只保留时长 >= 30 分钟的空档；由 AiService 复用 /ai/plan 的那套算法算好后填充，前端传什么都不看。
         * <p>
         * 算不出来（库里有脏数据等）或当天被占满时是空数组（不是 null），也不会让整轮聊天失败。
         * 给模型「今天还能往哪儿塞任务」用，用于对话式排任务。
         */
        private List<AvailableSlot> availableSlots = new ArrayList<>();

        /**
         * 今天（自然日 00:00 起）用户是不是**还没说过话** —— 也就是这次这条消息是不是「今天的第一条」。
         * 由 {@code AiService.isFirstMessageToday} 现查 chat_message 得到（这条消息自己还没落库，所以只算之前已有的）。
         * <p>
         * 类型必须是包装类 {@code Boolean}（不能写 `boolean`）：基本类型的 getter 会变成 {@code isFirstMessageToday()}，
         * Jackson 会把属性名推成 `firstMessageToday`（属性名变成字段名以外的另一个），转给 Python 的 JSON key 就错了；
         * 包装类 + Lombok 生成的是 {@code getIsFirstMessageToday()} → 属性名正好是 `isFirstMessageToday`。
         * <p>
         * 每次都填（一定是 true / false，不会是 null）：前端与 Python 侧靠它判断「要不要主动开场」。
         */
        private Boolean isFirstMessageToday;

        /**
         * 昨天的情况（排了几条、完成几条、漏掉哪几条；字段见 {@link YesterdaySummary}）。
         * <p>
         * **只在 {@link #isFirstMessageToday} = true（今天第一条消息）时才有值**，其它时候是 null ——
         * 主动监督只需要在每天第一次对话时提一次，后面每轮都带上会白烧 token。
         * 由 {@code AiService.buildYesterdaySummary} 现查 task 表算出。
         */
        private YesterdaySummary yesterdaySummary;

    }

    /**
     * 昨天的情况，给 AI 做「主动监督」用：昨天一共排了几条、完成几条、漏了几条、漏掉的是哪几条。
     * 由 {@code AiService.buildYesterdaySummary} 现查 task 表算出（plan_date = 昨天）。
     * <p>
     * 口径：
     * <ul>
     *   <li>三个计数都**排除 status = 'skipped'**（用户在今日页删掉的长期任务实例，与 TaskService / StatsService 一致）；</li>
     *   <li>completed = status = 'done' 的条数；missed = status = 'missed'，或 status = 'pending' 且已过计划结束时间；</li>
     *   <li>昨天没任务时：total / completed / missed 都是 0，missedTasks 是空数组（不是 null）。</li>
     * </ul>
     */
    @Data
    public static class YesterdaySummary {

        /** 昨天，格式 yyyy-MM-dd */
        private String date;

        /** 昨天一共排了几条任务（不含 skipped） */
        private Integer total;

        /** 其中已完成的条数（status = 'done'） */
        private Integer completed;

        /** 其中漏掉的条数（status = 'missed'，或 pending 且已过点）；可能大于 `missedTasks.size()` */
        private Integer missed;

        /** 漏掉的任务明细：按开始时间从早到晚最多 5 条（避免上下文太长），时间没有值时给空串 */
        private List<MissedTask> missedTasks = new ArrayList<>();

    }

    /**
     * 昨天漏掉的一条任务，只给模型看 title / startTime / endTime 三个字段。
     * 时间格式与 {@link TodayTask} 一致（HH:mm），没有值时是空串（不给 null）。
     */
    @Data
    public static class MissedTask {

        private String title;

        /** 格式 HH:mm，没有则空串 */
        private String startTime;

        /** 格式 HH:mm，没有则空串 */
        private String endTime;

    }

    /**
     * 展示给模型看的一个今日空档，与 /ai/plan 的 TimeSlot 同形（startTime / endTime）。
     * 时间由 Java 侧统一格式化成 HH:mm 的字符串，不会是 null（Python 侧这两个字段是 str）。
     */
    @Data
    public static class AvailableSlot {

        /** 格式 HH:mm */
        private String startTime;

        /** 格式 HH:mm */
        private String endTime;

        /**
         * 便捷构造（不写构造器是为了保留 Lombok 生成的无参构造，Jackson 反序列化要用）。
         */
        public static AvailableSlot of(String startTime, String endTime) {
            AvailableSlot slot = new AvailableSlot();
            slot.setStartTime(startTime);
            slot.setEndTime(endTime);
            return slot;
        }

    }

    /**
     * 展示给模型看的一条今日任务，对应 Python 侧 TaskBrief。
     * startTime / endTime 没有值时必须是空串：Python 侧这两个字段是 str，给 null 会被判 422。
     */
    @Data
    public static class TodayTask {

        private Long id;

        private String title;

        /** pending / done / missed */
        private String status;

        /** 格式 HH:mm，没有则空串 */
        private String startTime;

        /** 格式 HH:mm，没有则空串 */
        private String endTime;

    }

    /**
     * 展示给模型看的一门今日课程，对应 Python 侧 CourseBrief。
     * 只给模型参考，不参与任何时间计算；时间没有值时给空串（Python 侧是 str，给 null 会被判 422）。
     */
    @Data
    public static class TodayCourse {

        private String courseName;

        /** 格式 HH:mm，没有则空串 */
        private String startTime;

        /** 格式 HH:mm，没有则空串 */
        private String endTime;

    }

    /**
     * 展示给模型看的一个今日休息时段，对应 Python 侧 RestTimeBrief。
     * 只给模型参考，不参与任何时间计算；时间 / label 没有值时给空串（Python 侧是 str，给 null 会被判 422）。
     */
    @Data
    public static class TodayRestTime {

        /** 格式 HH:mm，没有则空串 */
        private String startTime;

        /** 格式 HH:mm，没有则空串 */
        private String endTime;

        /** 如「午休」，没有则空串 */
        private String label;

    }

    /**
     * 展示给模型看的一条用户画像记忆，对应 Python 侧 MemoryBrief。
     * 来源是 user_memory 表（Python /ai/extract-memory 提炼、MemoryService 异步落库）。
     * <p>
     * category 在这里不限定取值：库里是什么就传什么，Python 侧对未知类别直接用英文原值展示。
     * 没有 category / content 的脏数据在 AiService 里就被过滤掉，不会传到这里。
     */
    @Data
    public static class UserMemoryItem {

        /** habit / emotion / event / preference / goal */
        private String category;

        /** 一条记忆（第三人称描述），最长 500 字（与 user_memory.content 的列长一致） */
        private String content;

    }

}
