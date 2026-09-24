package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体，对应数据表 chat_message。
 * 一行是一条消息：用户说的话（role = user）或 AI 的回复（role = assistant）。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** user / assistant，见 docs/api.md §0.6 */
    private String role;

    /** 消息内容；表里是 TEXT，不截断 */
    private String content;

    private LocalDateTime createdAt;

}
