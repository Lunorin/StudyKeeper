import request from './request'

/**
 * 拉取当前用户的聊天历史
 * GET /api/chat/history?limit=50
 *
 * @param {number} [limit] 最多返回多少条,默认 50,取值 1-200;越界后端返回 400
 * @returns {Promise<Array<{ id: number, role: string, content: string, createdAt: string }>>}
 *          role 为 user / assistant;createdAt 格式 YYYY-MM-DD HH:mm:ss;
 *          按时间正序返回(最早的在前,最后一条最新);
 *          一条消息都没有时后端返回空数组 []
 */
export function getHistory(limit = 50) {
  return request.get('/chat/history', { params: { limit } })
}
