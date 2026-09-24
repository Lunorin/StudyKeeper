package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务完成流水实体，对应数据表 completion_record。
 * 每次"完成 / 取消完成"都会追加一条，用于后续统计。
 */
@Data
@TableName("completion_record")
public class CompletionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long userId;

    /** 动作：done / undone */
    private String action;

    private LocalDate recordDate;

    private LocalDateTime createdAt;

}
