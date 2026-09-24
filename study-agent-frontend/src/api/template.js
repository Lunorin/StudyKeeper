import request from './request'

/**
 * 长期任务列表(含已停用的)
 * GET /api/template
 *
 * @returns {Promise<Array<{ id: number, title: string, duration: number, priority: string, repeatDays: number[], difficulty: number, active: boolean }>>}
 */
export function listTemplates() {
  return request.get('/template')
}

/**
 * 新增长期任务
 * POST /api/template
 *
 * @param {{ title: string, description?: string, duration: number, priority?: string, repeatDays: number[] }} data
 *         repeatDays:1=周一 …… 7=周日,至少一天,否则后端返回 400
 * @returns {Promise<object>} 后端返回的长期任务对象
 */
export function createTemplate(data) {
  return request.post('/template', data)
}

/**
 * 修改长期任务(只传要改的字段)
 * PUT /api/template/{id}
 *
 * @param {number} id 长期任务 id
 * @param {{ title?: string, description?: string, duration?: number, priority?: string, repeatDays?: number[] }} data
 * @returns {Promise<object>} 修改后的长期任务对象
 */
export function updateTemplate(id, data) {
  return request.put(`/template/${id}`, data)
}

/**
 * 删除长期任务
 * DELETE /api/template/{id}
 *
 * @param {number} id 长期任务 id
 * @returns {Promise<null>}
 */
export function deleteTemplate(id) {
  return request.delete(`/template/${id}`)
}
