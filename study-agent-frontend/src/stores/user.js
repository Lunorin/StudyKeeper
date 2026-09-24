import { ref } from 'vue'
import { getProfile } from '../api/user'

/** 没设置头像时用的默认 emoji */
export const DEFAULT_AVATAR = '🎓'

/**
 * 通知开关的默认值(4 个都开)。
 * 后端资料接口(GET /user/profile)暂时不返回这几个字段,所以刷新页面后会回到这里的默认值,
 * 每次保存则由 PUT /user/notifications 落到后端。
 */
const DEFAULT_NOTIFY = {
  notifyTaskReminder: true,
  notifyCourseReminder: true,
  notifyDailyReport: true,
  notifySound: true
}

/** 空资料:退出登录 / 拉取失败时用它兜底,模板里就不用到处判空 */
const EMPTY_USER = {
  id: null,
  username: '',
  nickname: '',
  avatar: '',
  phone: '',
  email: '',
  /**
   * 新用户引导是否已完成。
   * null = 还不知道(资料还没拉到,由 App.vue 补一次 GET /user/onboarding-status);
   * false = 未完成(登录后弹一次引导);true = 已完成(不再弹)。
   * 这里刻意不给 false 兜底 —— 默认 false 会让资料接口失败的用户被误弹引导。
   */
  onboarded: null,
  ...DEFAULT_NOTIFY
}

/**
 * 当前登录用户资料。
 * 模块级的 ref 就是全局单例:各处 import 拿到的是同一个对象,不用引入 Pinia。
 */
export const userInfo = ref({ ...EMPTY_USER })

/** avatar 是不是 base64 图片(是图片就用 <img>,否则当 emoji / 文本渲染) */
export function isImageAvatar(avatar) {
  return typeof avatar === 'string' && avatar.startsWith('data:')
}

/** 局部更新:只覆盖传进来的字段(通知开关、手机号 / 邮箱这类小改动用它) */
export function patchUserInfo(partial) {
  userInfo.value = { ...userInfo.value, ...(partial || {}) }
}

/** 写入资料:后端没返回的字段保留原值,保证模板里字段都存在 */
export function setUserInfo(data) {
  patchUserInfo(data)
}

/** 清空资料(退出登录时调用) */
export function clearUserInfo() {
  userInfo.value = { ...EMPTY_USER }
}

/**
 * 拉取用户资料并写入 store(NavBar 刷新兜底、登录成功后都会调)。
 * 失败时把错误抛给调用方,由调用方决定是否提示(401 已由 request.js 拦截器处理)。
 *
 * @returns {Promise<object>} 拉到的资料
 */
export async function loadProfile() {
  const data = await getProfile()
  setUserInfo(data)
  return userInfo.value
}
