import request from './request'

/**
 * 当前登录用户的资料(需要 token)
 * GET /api/user/profile(见 docs/api.md §12.1)
 *
 * @returns {Promise<{ id: number, username: string, nickname: string, avatar: string|null, phone: string|null, email: string|null }>}
 *          avatar 是 emoji(如 "🎓")或 base64 字符串,没设置过是 null;
 *          phone 入库前已去掉空格与短横线;用户不存在时后端返回 404
 */
export function getProfile() {
  return request.get('/user/profile')
}

/**
 * 修改个人资料(需要 token)
 * PUT /api/user/profile(见 docs/api.md §12.2)
 *
 * 语义:五个字段全部可选,**不传 / null 表示不修改,传空串 "" 表示清空**。
 * 所以本项目的「编辑资料」只传 nickname / username / avatar,不会碰到手机号与邮箱。
 *
 * @param {{ nickname?: string, username?: string, avatar?: string, phone?: string, email?: string }} data
 *        nickname / username 不能是空白,最长 50 字符(username 与别人重复返回 1001);
 *        avatar 是 emoji 或 base64,UTF-8 字节数必须 ≤ 500KB,超出返回 400;
 *        phone 5-20 位数字(可带前导 +),email 需通过简单格式校验
 * @returns {Promise<object>} 返回更新后的完整资料(结构同 getProfile)
 */
export function updateProfile(data) {
  return request.put('/user/profile', data)
}

/**
 * 保存通知开关(需要 token)
 * PUT /api/user/notifications
 *
 * 说明:docs/api.md 目前还没有这一节(§12 里写的是「通知设置与意见反馈放到第 2 步」),
 * 这里按约定实现 —— 接口字段名不带 notify 前缀(前端 store 里才是 notifyXxx)。
 *
 * @param {{ taskReminder: boolean, courseReminder: boolean, dailyReport: boolean, sound: boolean }} data
 * @returns {Promise<object>} 后端返回保存后的设置;若返回体为空,调用方用表单值兜底
 */
export function updateNotifications(data) {
  return request.put('/user/notifications', data)
}

/**
 * 提交意见反馈(需要 token)
 * POST /api/user/feedback
 *
 * @param {{ content: string }} data content 去首尾空白后不能为空,最长 5000 字(为空后端会判 400)
 * @returns {Promise<object>} 后端返回受理结果;若返回体为空,调用方只按成功处理
 */
export function submitFeedback(data) {
  return request.post('/user/feedback', data)
}

/**
 * 查询新用户引导是否已完成(需要 token)
 * GET /api/user/onboarding-status(见 docs/api.md §12.5)
 *
 * 说明:登录响应与 GET /user/profile 里都已经带了 onboarded,正常流程不用调这个接口;
 *       它给「刷新页面时 store 里还没有这个字段,补一次查询」用。
 *
 * @returns {Promise<{ onboarded: boolean }>} true = 已完成(不用再弹),false = 未完成(该弹一次)
 */
export function getOnboardingStatus() {
  return request.get('/user/onboarding-status')
}

/**
 * 标记新用户引导已完成(需要 token)
 * POST /api/user/onboarding-complete(见 docs/api.md §12.6)
 *
 * 无请求体;后端只把 user.onboarded 置 1,**幂等**(重复调用仍返回成功,前端可以放心重试),
 * 也不记录引导走到第几步 / 完成时间 —— 引导只记「完成 / 未完成」。
 *
 * @returns {Promise<null>}
 */
export function completeOnboarding() {
  return request.post('/user/onboarding-complete')
}
