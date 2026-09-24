package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 意见反馈实体，对应数据表 feedback。
 * <p>
 * 一行 = 用户提交的一条反馈，**只写不读**：没有列表查询、没有已读 / 未读状态、没有回复
 * （见 docs/api.md §12.4）。
 */
@Data
@TableName("feedback")
public class Feedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 反馈内容；表里是 TEXT（上限 65535 字节），接口层限 ≤ 5000 字，见 UserService */
    private String content;

    /** 提交时间：由应用显式赋值（不依赖表上的 CURRENT_TIMESTAMP 默认值） */
    private LocalDateTime createdAt;

}
