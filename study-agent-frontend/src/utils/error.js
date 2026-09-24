import { ElMessage } from 'element-plus'
import { clearAuthStorage } from '../api/request'

/** 各类异常对应的友好文案(集中在这里,以后想改文案只动这一处) */
const NETWORK_TEXT = '网络好像不太好，稍后再试'
const EXPIRED_TEXT = '登录过期了，请重新登录'
const AI_BUSY_TEXT = 'AI 现在有点忙，稍后再试'
const BAD_REQUEST_TEXT = '请求有问题，检查一下输入'
const DEFAULT_TEXT = '出了点小问题，稍后再试'

/** 已经在登录页时不再跳转(与 request.js 里 401 的判断保持一致) */
function isOnLoginPage() {
  return window.location.pathname === '/login'
}

/**
 * 清掉登录态并回登录页。
 * 用整页跳转而不是 import router:utils 里不做路由依赖,也不会和 request.js 互相引用。
 */
function redirectToLogin() {
  clearAuthStorage()
  if (!isOnLoginPage()) {
    window.location.href = '/login'
  }
}

/**
 * 统一的错误提示(替代各页面里零散的 ElMessage.error(error?.message || 'xxx失败'))。
 * 兼容三类 error:
 *   1) 业务错误 —— request.js 拦截器抛出的 Error,code 是数字(400 / 401 / 500 / 1001 / 3001 ...);
 *   2) HTTP 错误 —— axios 抛出的 Error,response.status 是 HTTP 状态码,code 是 'ERR_BAD_REQUEST' 这类字符串;
 *   3) 网络异常 / 超时 —— code 为 'ERR_NETWORK' / 'ECONNABORTED',message 形如 "Network Error"。
 *
 * @param {Error|object} error 调用方 catch 到的错误对象
 */
export function showError(error) {
  const code = error?.code
  const status = error?.response?.status
  const message = String(error?.message || '')

  // 网络不通 / 超时
  if (code === 'ERR_NETWORK' || code === 'ECONNABORTED' || /network|timeout/i.test(message)) {
    ElMessage.error(NETWORK_TEXT)
    return
  }

  // 登录态失效:清 token 并回登录页。
  // 登录页自己收到 401 不可能是「登录过期」,原样展示后端文案(如「用户名或密码错误」)
  if (code === 401 || status === 401) {
    if (isOnLoginPage()) {
      ElMessage.error(message || EXPIRED_TEXT)
      return
    }
    redirectToLogin()
    ElMessage.error(EXPIRED_TEXT)
    return
  }

  // AI 服务异常 / 服务端故障
  if (code === 500 || status >= 500 || message.includes('AI 服务')) {
    ElMessage.error(AI_BUSY_TEXT)
    return
  }

  // 参数问题:后端的文案更具体,优先展示
  if (code === 400 || status === 400) {
    ElMessage.error(message || BAD_REQUEST_TEXT)
    return
  }

  // 认证类业务错误:后端带的是能直接给用户看的中文文案(1001 该邮箱已被注册 / 1002 账号或密码错误),
  // 登录 / 注册 / 找回密码页最需要的就是这一句,不能笼统成「出了点小问题」
  if (code === 1001 || code === 1002) {
    ElMessage.error(message || DEFAULT_TEXT)
    return
  }

  ElMessage.error(DEFAULT_TEXT)
}
