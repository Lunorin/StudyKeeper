package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET /api/user/onboarding-status 返回的业务数据：`{ "onboarded": true/false }`。
 * <p>
 * 只有一个字段 —— 前端（登录后或刷新页面时）拿它决定要不要弹新用户引导。
 * 后端**只记「完成 / 未完成」**：不记引导走到第几步、不记弹过几次，引导的具体内容全在前端。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingStatusDTO {

    /** true = 已完成引导；false = 未完成（新注册用户，以及加了列之后没标记过的老用户） */
    private Boolean onboarded;

}
