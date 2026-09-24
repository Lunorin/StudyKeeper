import request from './request'

/**
 * 创建任务
 * POST /api/task
 *
 * @param {{ title: string, planDate: string, startTime: string, endTime: string, priority: string }} data
 * @returns {Promise<object>} 后端返回的任务对象
 */
export function createTask(data) {
  return request.post('/task', data)
}

/**
 * 今日任务列表 + 完成度统计
 * GET /api/task/today
 *
 * @returns {Promise<{ date: string, total: number, completed: number, percent: number, tasks: object[] }>}
 */
export function getTodayTasks() {
  return request.get('/task/today')
}

/**
 * 修改任务(只允许改 title / description / startTime / endTime / priority)
 * PUT /api/task/{id}
 *
 * @param {number} id 任务 id
 * @param {{ title?: string, description?: string, startTime?: string, endTime?: string, priority?: string }} data
 * @returns {Promise<object>} 修改后的任务对象
 */
export function updateTask(id, data) {
  return request.put(`/task/${id}`, data)
}

/**
 * 删除任务
 * DELETE /api/task/{id}
 *
 * @param {number} id 任务 id
 * @returns {Promise<null>}
 */
export function deleteTask(id) {
  return request.delete(`/task/${id}`)
}

/**
 * 标记任务完成
 * POST /api/task/{id}/done
 *
 * @param {number} id 任务 id
 * @returns {Promise<{ id: number, status: string, completedAt: string|null }>}
 */
export function markDone(id) {
  return request.post(`/task/${id}/done`)
}

/**
 * 取消任务完成
 * POST /api/task/{id}/undo
 *
 * @param {number} id 任务 id
 * @returns {Promise<{ id: number, status: string, completedAt: string|null }>}
 */
export function markUndo(id) {
  return request.post(`/task/${id}/undo`)
}

