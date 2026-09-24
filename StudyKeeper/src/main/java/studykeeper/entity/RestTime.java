package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 休息时段实体，对应数据表 rest_time。
 */
@Data
@TableName("rest_time")
public class RestTime {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1=周一 …… 7=周日；null 表示每天生效 */
    private Integer dayOfWeek;

    private LocalTime startTime;

    private LocalTime endTime;

    private String label;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
