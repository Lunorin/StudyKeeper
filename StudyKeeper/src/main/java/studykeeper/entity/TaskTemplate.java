package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长期任务模板实体，对应数据表 task_template。
 */
@Data
@TableName("task_template")
public class TaskTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String description;

    private Integer duration;

    private String priority;

    /** 重复的星期，存成 "1,3,5" 这样的字符串；对外接口转成数组 */
    private String repeatDays;

    private BigDecimal difficulty;

    /** 是否启用：1 启用，0 停用 */
    private Integer active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
