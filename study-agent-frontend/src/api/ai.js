import request, { TOKEN_KEY } from './request'

/**
 * AI 拆解当天任务(只返回建议,不落库)
 * POST /api/ai/plan
 *
 * @param {{ goal: string, planDate: string }} data
 *        planDate 格式 yyyy-MM-dd;
 *        availableSlots 不用前端传,后端会按课程表 + 休息时间自己算
 * @returns {Promise<Array<{ title: string, startTime: string, endTime: string, priority: string }>>}
 *          时间格式 HH:mm,没有 duration 字段(时长由前端按起止时间算)
 *          goal / planDate 不合法或当天没有可用时段时后端返回 400,
 *          AI 服务不可用返回 3001(拦截器会 reject,Error.message = "AI 服务调用失败")
 */
export function planTasks(data) {
  return request.post('/ai/plan', data)
}

/**
 * 把 AI 拆解出的任务一次性批量落库
 * POST /api/task/batch
 *
 * 说明:这其实是任务模块的接口,但本步限定只改 ai.js / TodayView.vue,
 * 所以先放在这里;以后可以挪到 src/api/task.js。
 *
 * @param {{ planBatchId: string, planDate: string, tasks: Array<{ title: string, startTime: string, endTime: string, priority: string }> }} data
 * @returns {Promise<{ planBatchId: string, createdCount: number, ids: number[] }>}
 */
export function createTasksBatch(data) {
  return request.post('/task/batch', data)
}

/**
 * AI 对话:可以纯聊天,也可以让 AI 帮忙增 / 改 / 删今日任务
 * POST /api/ai/chat
 *
 * @param {{ message: string }} data
 *        只传用户这一条消息;历史上下文由后端自己查(见 GET /api/chat/history),
 *        今日任务上下文也由后端自己查,前端都不用传
 * @returns {Promise<{ reply: string, affectedTaskIds: number[] }>}
 *          reply 是模型回复;affectedTaskIds 是这轮真正改动的任务 id(非空说明改了任务)
 *          AI 服务不可用返回 3001;AI 的工具调用没执行成功返回 3002(这轮改动已整体回滚)
 */
export function chat(data) {
  return request.post('/ai/chat', data)
}

/** 流式对话地址:和 axios 实例一样带 /api 前缀,由 vite dev server 代理到后端 */
const CHAT_STREAM_URL = '/api/ai/chat/stream'

/**
 * AI 对话 - 流式版本(边生成边返回,前端逐字显示)
 * POST /api/ai/chat/stream(响应是 SSE,text/event-stream)
 *
 * 为什么用 fetch 而不是 EventSource:EventSource 只支持 GET,
 * 本接口是 POST + JSON body,只能 fetch + ReadableStream 手动解析 SSE 事件。
 *
 * @param {{ message: string }} data 请求体,和 chat() 一样只传用户这一条消息
 * @param {{
 *   onDelta?: (content: string) => void,
 *   onToolCalls?: (toolCalls: Array) => void,
 *   onEnd?: (reply: string) => void,
 *   onError?: (message: string) => void,
 *   signal?: AbortSignal
 * }} handlers
 *        onDelta     收到一小段增量文本
 *        onToolCalls 这轮的工具调用结果(用来看改动了哪些任务)
 *        onEnd       流正常结束,参数是完整回复(后端可能不传)
 *        onError     服务端在流里报错,参数是错误文案
 *        signal      传 AbortController.signal,组件卸载时可以中断请求
 * @returns {Promise<void>} 流读完后 resolve;
 *          HTTP 非 2xx / 网络中断 / 被 abort 时 reject(abort 时 error.name === 'AbortError');
 *          服务端用 error 事件报的错不会 reject,而是走 onError 回调
 */
export async function chatStream(data, { onDelta, onToolCalls, onEnd, onError, signal } = {}) {
  const response = await fetch(CHAT_STREAM_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // fetch 不经过 axios 拦截器,token 这里自己带(和后端约定的 Bearer 格式一致)
      Authorization: `Bearer ${localStorage.getItem(TOKEN_KEY) || ''}`
    },
    body: JSON.stringify(data),
    signal
  })

  if (!response.ok) {
    const error = new Error(`HTTP ${response.status}`)
    // 带上 status,调用方的 showError 才能区分 401(清 token 回登录页)和 5xx(提示 AI 忙)
    error.response = { status: response.status }
    throw error
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  /** 处理一个 SSE 事件块,例如:data: {"type":"delta","content":"你"} */
  function handleEvent(part) {
    // 一个事件块里可能有多行(event: / data: / id:),这里只取 data: 那行
    const dataLine = part.split('\n').find((line) => line.startsWith('data:'))
    if (!dataLine) return

    const json = dataLine.slice(5).trim() // 去掉 "data:" 前缀
    if (!json || json === '[DONE]') return

    try {
      const event = JSON.parse(json)
      if (event.type === 'delta') onDelta?.(event.content || '')
      else if (event.type === 'tool_calls') onToolCalls?.(event.toolCalls || [])
      else if (event.type === 'end') onEnd?.(event.reply || '')
      else if (event.type === 'error') onError?.(event.message || '')
    } catch {
      console.warn('解析 SSE 失败', part)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // SSE 事件之间用空行分隔;正则容忍服务端用 \r\n
    const parts = buffer.split(/\r?\n\r?\n/)
    buffer = parts.pop() // 最后一段可能不完整,留着和下一块拼

    parts.forEach(handleEvent)
  }

  // 收尾:最后一条事件可能没有以空行结尾,漏掉就会丢内容
  buffer += decoder.decode()
  buffer.split(/\r?\n\r?\n/).forEach(handleEvent)
}

/**
 * AI 解析课程文本:把一段自由格式的课程文本转成结构化课程列表(只解析,不落库)
 * POST /api/ai/parse-course
 *
 * @param {{ rawText: string }} data
 *        rawText 支持多行,最长 5000 字;只传这一个字段(后端转发给 Python,
 *        那边 extra="forbid",多传字段会被判 422 从而变成 3001)
 * @returns {Promise<{ courses: Array<{ courseName: string, daysOfWeek: number[], startTime: string, endTime: string }>, failed: string[] }>}
 *          daysOfWeek:1=周一 …… 7=周日;startTime / endTime 为 HH:mm;
 *          failed 是没能识别的原始行(字符串),只用于提示用户手工修正
 *          rawText 不合法返回 400,AI 服务不可用返回 3001
 */
export function parseCourse(data) {
  return request.post('/ai/parse-course', data)
}
