/** 补零成两位 */
function pad(value) {
  return String(value).padStart(2, '0')
}

/** 是否同一天(按本地时区的年月日比较) */
function isSameDay(a, b) {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

/**
 * 把后端返回的时间解析成本地时间对象,解析不出来返回 null。
 * 兼容两种后端格式:
 *   1) "2026-09-17 08:30:00"(接口文档里的日期时间格式,聊天历史 createdAt 就是它)
 *   2) ISO 格式 "2026-09-17T08:30:05" / 带毫秒 / 带 Z 或 +08:00 时区
 */
function parseTime(value) {
  if (!value) return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value

  const text = String(value).trim()
  if (!text) return null

  // 带空格的 "yyyy-MM-dd HH:mm:ss" 换成 T 再交给 Date(Safari 不接受空格写法)
  const date = new Date(text.includes('T') ? text : text.replace(' ', 'T'))
  if (!Number.isNaN(date.getTime())) return date

  // 兜底:手动拆字段(按本地时间),覆盖 "2026/09/17 08:30" 之类的变体
  const matched = text.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?/)
  if (!matched) return null

  return new Date(
    Number(matched[1]),
    Number(matched[2]) - 1,
    Number(matched[3]),
    Number(matched[4]),
    Number(matched[5]),
    Number(matched[6] || 0)
  )
}

/**
 * 聊天消息时间戳文案:
 *   1 分钟内  -> 刚刚
 *   今天      -> HH:mm
 *   昨天      -> 昨天 HH:mm
 *   更早      -> MM-DD HH:mm
 *
 * @param {string|Date} isoString 后端时间字符串("yyyy-MM-dd HH:mm:ss" 或 ISO),也可直接传 Date
 * @returns {string} 时间无法解析时返回空串,调用方可以直接 v-if 判断
 */
export function formatMessageTime(isoString) {
  const date = parseTime(isoString)
  if (!date) return ''

  const now = new Date()
  const diff = now.getTime() - date.getTime()
  if (diff >= 0 && diff < 60 * 1000) return '刚刚'

  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  if (isSameDay(date, now)) return time

  // 昨天:用 setDate 往前推一天,跨月 / 跨年交给 Date 自己处理
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (isSameDay(date, yesterday)) return `昨天 ${time}`

  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${time}`
}
