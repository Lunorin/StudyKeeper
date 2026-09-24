package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 任务实体，对应数据表 task。
 */
@Data
@TableName("task")
public class Task {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long templateId;

    private String planBatchId;

    private String title;

    private String description;

    private LocalDate planDate;

    private LocalTime startTime;

    private LocalTime endTime;

    private Integer duration;

    private String priority;

    private BigDecimal difficulty;

    private String status;

    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
