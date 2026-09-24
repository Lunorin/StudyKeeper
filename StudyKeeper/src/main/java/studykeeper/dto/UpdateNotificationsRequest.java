package studykeeper.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PUT /api/user/notifications 的请求体：4 个通知开关。
 * <p>
 * 每个字段都是可选：**null / 不传 = 不改**，true = 开、false = 关
 * （沿用资料接口「只传要改的字段」的做法，见 decisions §12.5）。
 * 库里存 TINYINT 1/0，Boolean → 1/0 的转换在 Service 里做。
 */
@Data
@NoArgsConstructor
public class UpdateNotificationsRequest {

    /** 任务提醒 */
    private Boolean taskReminder;

    /** 课程提醒 */
    private Boolean courseReminder;

    /** 每日报告 */
    private Boolean dailyReport;

    /** 提示音 */
    private Boolean sound;

}
