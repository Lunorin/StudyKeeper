package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户画像记忆实体，对应数据表 user_memory。
 * 一行是一条「值得长期记住」的用户信息，由 Python /ai/extract-memory 提炼、Java 异步落库。
 */
@Data
@TableName("user_memory")
public class UserMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** habit / emotion / event / preference / goal（Python 侧只返回这 5 类） */
    private String category;

    /** 记忆内容；表里是 VARCHAR(500) */
    private String content;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
