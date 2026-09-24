package studykeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /ai/plan（Java → Python）的请求体。
 * 字段名与 Python 侧 Pydantic 模型 PlanRequest 完全一致：那边 extra="forbid"，
 * 多传一个字段就会被 FastAPI 判 422，所以这里只保留 goal / planDate / availableSlots 三个字段。
 * 时间字段用 String（planDate 为 yyyy-MM-dd，时段为 HH:mm），不经过 java.time 序列化器，格式稳定。
 */
@Data
public class AiPlanRequest {

    /** 用户目标，如「复习高数第三章 + 背 50 个单词」 */
    private String goal;

    /** 计划日期，格式 yyyy-MM-dd */
    private String planDate;

    /** 可用时段，左闭右开；由 AiService 按课程表 + 休息时间 + 今日已有任务算好后填充，不使用前端传来的值 */
    private List<AvailableSlot> availableSlots = new ArrayList<>();

    /**
     * 单个可用时段，对应 Python 侧的 TimeSlot。
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

}
