package studykeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应数据表 user。
 * 注意：password 存的是 BCrypt 哈希，不是明文。
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希 */
    private String password;

    private String nickname;

    /** 头像：emoji 字符或 base64 字符串，由前端直接传，不做文件上传 */
    private String avatar;

    private String phone;

    private String email;

    /**
     * 通知设置：任务提醒。列类型 TINYINT，**1 = 开 / 0 = 关**（表上默认 1）。
     * <p>
     * 四个通知字段在实体里都是 Integer（镜像表结构，decisions §2.10），
     * 只有对外响应（UserProfileDTO）才转成 Boolean。
     */
    private Integer notifyTaskReminder;

    /** 通知设置：课程提醒。TINYINT，1 = 开 / 0 = 关（表上默认 1）。 */
    private Integer notifyCourseReminder;

    /** 通知设置：每日报告。TINYINT，1 = 开 / 0 = 关（表上默认 0）。 */
    private Integer notifyDailyReport;

    /** 通知设置：提示音。TINYINT，1 = 开 / 0 = 关（表上默认 1）。 */
    private Integer notifySound;

    /**
     * 新用户引导是否已完成：列类型 TINYINT，**1 = 已完成 / 0 = 未完成**（表上默认 0）。
     * <p>
     * 与 4 个通知字段同一套路（decisions §2.10）：实体镜像表结构用 Integer，
     * 对外响应（{@link studykeeper.dto.UserProfileDTO} / {@link studykeeper.dto.LoginResponse}）
     * 才转成 Boolean，转换只在 Service 里做。
     * <p>
     * 列由 {@code ALTER TABLE user ADD COLUMN onboarded TINYINT DEFAULT 0;} 加出，
     * 注册流程不显式写这一列（MyBatis-Plus insert 跳过 null 字段）→ 新用户拿到库里的默认值 0。
     * 这里**只记「完成 / 未完成」**，不记引导走到第几步（那是前端的事）。
     */
    private Integer onboarded;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
