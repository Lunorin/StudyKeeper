import request from './request'

/**
 * 今日统计
 * GET /api/stats/today
 *
 * @returns {Promise<{ date: string, total: number, completed: number, percent: number }>}
 *          completed 只统计 status = done 的任务,percent 为 0~100 的整数
 */
export function getTodayStats() {
  return request.get('/stats/today')
}

/**
 * 本周统计(周一 ~ 周日)
 * GET /api/stats/week
 *
 * @returns {Promise<{ weekStart: string, weekEnd: string, total: number, completed: number, percent: number }>}
 */
export function getWeekStats() {
  return request.get('/stats/week')
}

/**
 * 近 N 天完成数趋势
 * GET /api/stats/trend?days=7
 *
 * @param {number} [days] 1-30,默认 7;越界后端返回 400
 * @returns {Promise<Array<{ date: string, completed: number }>>}
 *          含今天在内的连续 N 天,按日期升序,没有任务的日子 completed = 0(后端已补齐)
 */
export function getTrendStats(days = 7) {
  return request.get('/stats/trend', { params: { days } })
}
