import axios from 'axios'

/** localStorage 里存 token 的 key(登录页、路由守卫、导航栏都用它) */
export const TOKEN_KEY = 'token'

/** localStorage 里存用户信息的 key(JSON 字符串) */
export const USER_INFO_KEY = 'userInfo'

/**
 * 清掉本地登录态:token + 用户信息。
 * 401 处理和退出登录都会用到。
 */
export function clearAuthStorage() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_INFO_KEY)
}

/** 多个请求同时 401 时,只跳转一次 */
let redirecting = false

/**
 * 清掉本地登录态并回登录页。
 * 用整页跳转(window.location.href)而不是 import router,
 * 避免 request.js 反向依赖 router 造成循环依赖。
 */
function redirectToLogin() {
  clearAuthStorage()

  if (redirecting || window.location.pathname === '/login') {
    return
  }

  redirecting = true
  window.location.href = '/login'
}

/**
 * 统一 axios 实例。
 * baseURL 为 /api,由 vite dev server 代理到 http://localhost:8080。
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

/** 请求拦截器:本地有 token 就带上 Authorization: Bearer <token> */
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

/**
 * 响应拦截器。
 * 兼容两种后端返回:
 * 1) 统一包装体 { code, message, data }:code !== 0 视为业务失败并 reject,code === 0 时已解包返回 data;
 * 2) 非包装体(例如后端直接返回实体对象或空响应):原样返回。
 * 另外:401(未登录 / token 失效)统一清本地登录态并回登录页。
 */
request.interceptors.response.use(
  (response) => {
    const body = response.data

    if (body === null || body === undefined || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    if (body.code !== 0) {
      // 少数情况后端会用 HTTP 200 + code 401 表示未登录(例如 /auth/me 查不到用户)
      if (body.code === 401) {
        redirectToLogin()
      }

      const error = new Error(body.message || '请求失败')
      error.code = body.code
      error.data = body.data
      return Promise.reject(error)
    }

    return body.data
  },
  (error) => {
    // HTTP 401:后端 JwtInterceptor 在 token 缺失 / 失效时直接写回 401
    if (error.response && error.response.status === 401) {
      redirectToLogin()
    }

    // 其他错误(4xx / 5xx / 超时)保持原样抛出,由调用方 catch
    return Promise.reject(error)
  }
)

export default request

