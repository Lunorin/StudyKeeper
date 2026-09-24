package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 课程实体，对应数据表 course。
 */
@Data
@TableName("course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String courseName;

    /** 1=周一 …… 7=周日 */
    private Integer dayOfWeek;

    private LocalTime startTime;

    private LocalTime endTime;

    private String location;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
