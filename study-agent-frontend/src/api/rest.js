import request from './request'

/**
 * 休息时段列表(后端已排好序:每天生效的在前,再按周几、开始时间)
 * GET /api/rest
 *
 * @returns {Promise<Array<{ id: number, dayOfWeek: number|null, startTime: string, endTime: string, label: string|null }>>}
 *          dayOfWeek 为 null 表示每天生效;startTime / endTime 已经是 HH:mm 字符串(如 "10:00")
 */
export function listRestTimes() {
  return request.get('/rest')
}

/**
 * 新增休息时段
 * POST /api/rest
 *
 * @param {{ dayOfWeek: number|null, startTime: string, endTime: string, label?: string }} data
 *        dayOfWeek 传 null 表示每天;startTime 必须早于 endTime,否则后端返回 400
 * @returns {Promise<object>} 后端返回的休息时段对象
 */
export function createRestTime(data) {
  return request.post('/rest', data)
}

/**
 * 修改休息时段(只传要改的字段)
 * PUT /api/rest/{id}
 *
 * 注意:后端把 null 当成「不修改」,而 dayOfWeek 的 null 又表示「每天」,
 * 所以本接口无法把已有记录改回「每天」——要改成每天请 createRestTime 新建 + deleteRestTime 删除旧的。
 *
 * @param {number} id 休息时段 id
 * @param {{ dayOfWeek?: number, startTime?: string, endTime?: string, label?: string }} data
 * @returns {Promise<object>} 修改后的休息时段对象
 */
export function updateRestTime(id, data) {
  return request.put(`/rest/${id}`, data)
}

/**
 * 删除休息时段
 * DELETE /api/rest/{id}
 *
 * @param {number} id 休息时段 id
 * @returns {Promise<null>}
 */
export function deleteRestTime(id) {
  return request.delete(`/rest/${id}`)
}
