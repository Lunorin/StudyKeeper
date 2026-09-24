import request from './request'

/**
 * 记忆列表(用户画像:AI 从聊天里提炼出来的「关于你」的信息)
 * GET /api/memory(见 docs/api.md §8.1)
 *
 * @returns {Promise<Array<{ id: number, category: string, content: string, createdAt: string, updatedAt: string }>>}
 *          category 只有 5 类英文值(中文由前端映射,见 SettingsView.vue):
 *          event 重要事件 / goal 长期目标 / emotion 情绪状态 / habit 学习习惯 / preference 偏好;
 *          后端已排好序:按分类分组,组间固定 event > goal > emotion > habit > preference,
 *          组内按 updatedAt 倒序(脏分类排最后);一条都没有时返回空数组 []
 */
export function listMemories() {
  return request.get('/memory')
}

/**
 * 删除一条记忆
 * DELETE /api/memory/{id}(见 docs/api.md §8.3)
 *
 * @param {number} id 记忆 id
 * @returns {Promise<null>}
 *          记忆不存在、或不属于当前用户,后端统一返回 404
 */
export function deleteMemory(id) {
  return request.delete(`/memory/${id}`)
}

/**
 * 手动新增一条记忆
 * POST /api/memory(见 docs/api.md §8.2)
 *
 * @param {{ category: string, content: string }} data
 *        category 只能是 habit / emotion / event / preference / goal(大小写敏感),其它值返回 400「无效的分类」;
 *        content 去首尾空白后不能为空、最长 500 字
 * @returns {Promise<object>} 新增成功的那条记忆(结构同 listMemories 的单项)
 */
export function createMemory(data) {
  return request.post('/memory', data)
}
