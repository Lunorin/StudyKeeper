/**
 * 表单校验的公共规则(登录 / 注册 / 找回密码共用)。
 * 这里只放「前后端口径一致」的规则,页面里只做业务判断。
 */

/**
 * 邮箱格式:与后端 AuthService 里的 EMAIL_PATTERN 完全一致
 * —— `^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$`
 * 即 @ 前后非空、域名部分不能以点开头 / 结尾,且至少有一个点。
 * 前端先挡一道,避免把后端一定会拒的输入发出去(后端仍会再校验一次)。
 */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/

/** 邮箱是否合法(允许首尾空白,调用方传原值即可) */
export function isValidEmail(email) {
  return EMAIL_PATTERN.test(String(email ?? '').trim())
}

/** 验证码格式:邮箱里收到的 6 位数字 */
export const CODE_PATTERN = /^\d{6}$/

/** 验证码是否合法(6 位数字) */
export function isValidCode(code) {
  return CODE_PATTERN.test(String(code ?? '').trim())
}

/**
 * 密码强度:与后端完全一致的校验正则。
 * 长度 8-20,且必须同时含小写字母、大写字母、数字;字符集只允许字母数字与 @ $ ! % * ? &
 */
export const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[A-Za-z\d@$!%*?&]{8,20}$/

/** 密码是否通过后端同款校验 */
export function isValidPassword(password) {
  return PASSWORD_PATTERN.test(String(password ?? ''))
}

/**
 * 密码框下方实时显示的 4 条要求(顺序与文案固定,与改造前登录页的注册模式完全一致)。
 *
 * @param {string} password 当前输入的密码
 * @returns {Array<{ label: string, ok: boolean }>}
 */
export function getPasswordRules(password) {
  const value = String(password ?? '')

  return [
    { label: '至少 8 位（最多 20 位）', ok: value.length >= 8 && value.length <= 20 },
    { label: '包含小写字母', ok: /[a-z]/.test(value) },
    { label: '包含大写字母', ok: /[A-Z]/.test(value) },
    { label: '包含数字', ok: /\d/.test(value) }
  ]
}

/** 4 条是否全部满足(用于提示「不支持的字符」,避免 4 条全绿却还提交不了) */
export function arePasswordRulesAllOk(password) {
  return getPasswordRules(password).every((rule) => rule.ok)
}
