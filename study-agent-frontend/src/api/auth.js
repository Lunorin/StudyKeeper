import request from './request'

/* ==================== 注册(邮箱 + 验证码) ==================== */

/**
 * 发送注册验证码(不需要 token)
 * POST /api/auth/send-email-code(见 docs/api.md §1.4)
 *
 * 说明:同一邮箱 60 秒内只能发一次(前端配合做 60 秒倒计时),验证码 5 分钟有效、只进邮箱不返回;
 *       这里不校验邮箱是否已注册,注册时才会返回 1001。
 *
 * @param {{ email: string }} data
 * @returns {Promise<null>}
 */
export function sendEmailCode(data) {
  return request.post('/auth/send-email-code', data)
}

/**
 * 注册(不需要 token)
 * POST /api/auth/register(见 docs/api.md §1.1)
 *
 * 必须先用 sendEmailCode 拿到验证码;username 不再由前端传,后端自动取邮箱 @ 前缀。
 *
 * @param {{ email: string, code: string, password: string, nickname?: string }} data
 * @returns {Promise<{ id: number, username: string, nickname: string }>}
 *          该邮箱已注册时后端返回 code 1001,验证码 / 密码不合格返回 400,拦截器会 reject(Error.message = 中文文案)
 */
export function register(data) {
  return request.post('/auth/register', data)
}

/* ==================== 登录 / 退出 / 当前用户 ==================== */

/**
 * 登录(不需要 token)
 * POST /api/auth/login(见 docs/api.md §1.2)
 *
 * @param {{ account: string, password: string }} data
 *        account: 邮箱或用户名 —— 含 @ 按邮箱查(不区分大小写),否则按用户名查
 * @returns {Promise<{ token: string, id: number, username: string, nickname: string }>}
 *          注意:接口文档写的是 userId,后端实际返回的字段名是 id
 *          账号或密码错误时后端返回 code 1002(不区分账号不存在与密码错)
 */
export function login(data) {
  return request.post('/auth/login', data)
}

/**
 * 退出登录(需要 token)
 * POST /api/auth/logout
 * JWT 是无状态的,后端不做任何事,前端清掉本地 token 即可
 *
 * @returns {Promise<null>}
 */
export function logout() {
  return request.post('/auth/logout')
}

/**
 * 当前登录用户(需要 token),用于校验 token 是否还有效
 * GET /api/auth/me
 *
 * @returns {Promise<{ id: number, username: string, nickname: string }>}
 */
export function getMe() {
  return request.get('/auth/me')
}

/* ==================== 找回密码(邮箱 + 验证码) ==================== */

/**
 * 找回密码 · 发送验证码(不需要 token)
 * POST /api/auth/forgot-password/send-code(见 docs/api.md §1.5)
 *
 * 与注册发码共用同一套发码逻辑(主题 / 60 秒间隔 / 5 分钟有效);
 * 区别是这里要求邮箱已注册,没注册过直接返回 400「该邮箱未注册」。
 *
 * @param {{ email: string }} data
 * @returns {Promise<null>}
 */
export function sendForgotCode(data) {
  return request.post('/auth/forgot-password/send-code', data)
}

/**
 * 找回密码 · 重置密码(不需要 token)
 * POST /api/auth/forgot-password/reset(见 docs/api.md §1.6)
 *
 * 校验顺序是「邮箱格式 → 邮箱是否注册 → 新密码强度 → 验证码」,只有最后一步成功才消耗验证码;
 * 成功只更新 password 列,已签发的 token 在过期前仍然可用(前端重置完引导去登录页)。
 *
 * @param {{ email: string, code: string, newPassword: string }} data
 * @returns {Promise<null>}
 */
export function resetPassword(data) {
  return request.post('/auth/forgot-password/reset', data)
}
