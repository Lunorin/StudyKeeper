import request from './request'

/**
 * 课程列表
 * GET /api/course?dayOfWeek=1
 *
 * @param {number} [dayOfWeek] 1=周一 …… 7=周日;不传则返回全部
 * @returns {Promise<Array<{ id: number, courseName: string, dayOfWeek: number, startTime: string, endTime: string, location: string|null }>>}
 *          其中 startTime / endTime 已经是 HH:mm 字符串(如 "08:00")
 */
export function listCourses(dayOfWeek) {
  return request.get('/course', { params: { dayOfWeek } })
}

/**
 * 新增课程(一次只能加一天,勾了多个星期就循环调用)
 * POST /api/course
 *
 * @param {{ courseName: string, dayOfWeek: number, startTime: string, endTime: string, location?: string }} data
 *        startTime 必须早于 endTime,dayOfWeek 必须是 1-7,否则后端返回 400
 * @returns {Promise<object>} 后端返回的课程对象
 */
export function createCourse(data) {
  return request.post('/course', data)
}

/**
 * 修改课程(只传要改的字段)
 * PUT /api/course/{id}
 *
 * @param {number} id 课程 id
 * @param {{ courseName?: string, dayOfWeek?: number, startTime?: string, endTime?: string, location?: string }} data
 * @returns {Promise<object>} 修改后的课程对象
 */
export function updateCourse(id, data) {
  return request.put(`/course/${id}`, data)
}

/**
 * 删除课程
 * DELETE /api/course/{id}
 *
 * @param {number} id 课程 id
 * @returns {Promise<null>}
 */
export function deleteCourse(id) {
  return request.delete(`/course/${id}`)
}
