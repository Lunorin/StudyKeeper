package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /ai/chat（Java → Python）的请求体。
 * 字段名与 Python 侧 Pydantic 模型 ChatRequest 完全一致（那边 extra="forbid"，
 * 多传一个字段立刻 422），所以这里只有 message / history / context 三个字段。
 * <p>
 * 三个字段都由 AiService 填充：message 来自前端（POST /api/ai/chat 的请求体），
 * history 从 chat_message 查（该用户最近 20 条，正序），context 里的今日任务 / 课程表 /
 * 休息时段 / 用户画像记忆 / 今日空档一律现查 —— 前端传来的 history / context 都不看。
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
     * 今日任务 / 今日课程表 / 今日休息时段 / 今日空档（今天的时间安排）+ 用户画像记忆（这个用户是谁）。
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
