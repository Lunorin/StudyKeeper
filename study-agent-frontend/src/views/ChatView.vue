<script setup>
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { chatStream } from '../api/ai'
import { getHistory } from '../api/chat'
import { formatMessageTime } from '../utils/time'
import { showError } from '../utils/error'

/** 进入页面时最多拉多少条历史 */
const HISTORY_LIMIT = 50

/** 快捷操作:label 是按钮文案,message 是点击后直接发送的内容(写死的 4 条,不做成可配置) */
const QUICK_ACTIONS = [
  { label: '查看今日任务', message: '我今天有哪些任务？' },
  { label: '帮我排任务', message: '帮我安排一下今天的学习' },
  { label: '我累了', message: '我今天有点累' },
  { label: '调整计划', message: '我想调整一下今天的任务' }
]

/**
 * 消息列表:后端历史 + 本轮新发的消息,共用同一套渲染逻辑。
 * 历史消息带 id / createdAt,刚发的消息没有 id。
 */
const messages = ref([])
/** 输入框内容 */
const input = ref('')
/** 请求中(发消息) */
const loading = ref(false)
/** 拉历史中 */
const historyLoading = ref(false)
/** 消息区容器 */
const messageAreaRef = ref(null)
/** 消息区底部的哨兵元素,用来 scrollIntoView */
const bottomRef = ref(null)

/** 滚动到消息区底部 */
async function scrollToBottom() {
  await nextTick()
  bottomRef.value?.scrollIntoView({ block: 'end' })
  // 个别浏览器对 flex 容器内的 scrollIntoView 处理不一致,再兜一次底
  const area = messageAreaRef.value
  if (area) {
    area.scrollTop = area.scrollHeight
  }
}

/**
 * 拉历史消息:进入页面时调一次。
 * 历史里带 id,本地新发的消息没有 id,用 id 区分是为了
 * 「历史还没回来用户就发了消息」时不用本地消息覆盖掉历史。
 */
async function loadHistory() {
  historyLoading.value = true
  try {
    const data = await getHistory(HISTORY_LIMIT)
    const history = Array.isArray(data) ? data : []
    const local = messages.value.filter((msg) => msg.id == null)
    messages.value = [...history, ...local]
  } catch (error) {
    // 拉不到历史不阻塞聊天,退化成空状态(欢迎语)
    messages.value = messages.value.filter((msg) => msg.id == null)
    showError(error)
  } finally {
    historyLoading.value = false
    scrollToBottom()
  }
}

onMounted(loadHistory)

/** 当前流式请求的控制器:组件卸载时用它中断请求 */
let streamController = null

/** 组件卸载(关页面 / 切路由)时中断还没结束的流,避免在已卸载的组件上继续写消息 */
onUnmounted(() => {
  streamController?.abort()
})

/**
 * 收拢 tool_calls 事件里「受影响的任务 id」。
 * 兼容两种形状:[1, 2, 3] 和 [{ taskId: 1 }, { id: 2 }]。
 */
function collectAffectedTaskIds(toolCalls) {
  if (!Array.isArray(toolCalls)) return []
  return toolCalls
    .map((item) => {
      if (typeof item === 'number') return item
      if (!item || typeof item !== 'object') return null
      return item.taskId ?? item.affectedTaskId ?? item.id ?? null
    })
    .filter((id) => id !== null && id !== undefined)
}

/**
 * 发送一条消息:输入框发送与快捷按钮发送共用。
 * 历史由后端自己查,这里只发用户这一条;
 * 回复走流式接口:先占一条空的 AI 消息,onDelta 往里追加内容,做到逐字显示。
 * 不碰输入框——清空动作留给 handleSend,这样点快捷按钮不会清掉正在编辑的草稿。
 */
async function sendMessage(text) {
  if (!text || loading.value) {
    return
  }

  // createdAt 是本地时间:后端只回回复内容,刚发的消息时间戳前端自己补
  messages.value.push({ role: 'user', content: text, createdAt: new Date() })

  // 用 reactive 包一层,才能持有引用直接追加 content 并触发重新渲染
  const replyMessage = reactive({
    role: 'assistant',
    content: '',
    createdAt: new Date(),
    streaming: true,
    failed: false
  })
  messages.value.push(replyMessage)
  await scrollToBottom()

  loading.value = true
  // 这轮真正改动的任务 id,流结束后统一提示(工具名与参数后端不外传)
  let affectedTaskIds = []

  streamController = new AbortController()
  try {
    await chatStream(
      { message: text },
      {
        signal: streamController.signal,

        // 收到一小段增量文本:追加到那条 AI 消息上
        onDelta: (chunk) => {
          replyMessage.content += chunk
          scrollToBottom()
        },

        onToolCalls: (toolCalls) => {
          affectedTaskIds = collectAffectedTaskIds(toolCalls)
        },

        // 流正常结束:收掉光标;个别情况一个字都没收到,用 end 里的完整回复兜底
        onEnd: (reply) => {
          replyMessage.streaming = false
          if (!replyMessage.content && reply) {
            replyMessage.content = reply
          }
        },

        // 服务端在流里报的错:chatStream 不会 reject,这里自己标记 + 统一提示
        onError: (message) => {
          replyMessage.streaming = false
          replyMessage.failed = true
          showError(new Error(message || 'AI 服务调用失败'))
        }
      }
    )

    // 后端只暴露 affectedTaskIds(工具名与参数不外传),所以这里统一提示;
    // 这轮报过错就不再提「已更新任务」了(失败时后端会整体回滚)
    if (!replyMessage.failed && affectedTaskIds.length > 0) {
      ElMessage.success('已为你更新任务，可到今日任务页查看')
    }
  } catch (error) {
    // 页面卸载时主动 abort,不是真的出错,不用弹提示
    if (error?.name !== 'AbortError') {
      replyMessage.streaming = false
      replyMessage.failed = true
      showError(error)
    }
  } finally {
    replyMessage.streaming = false
    // 兜底文案:没收到任何内容时给个占位,避免出现空气泡
    if (!replyMessage.content) {
      replyMessage.content = replyMessage.failed ? '(回复失败)' : '(无回复)'
    }
    streamController = null
    loading.value = false
    scrollToBottom()
  }
}

/** 输入框发送:取值 + 非空校验 + 清空输入框,其余交给 sendMessage */
function handleSend() {
  const text = input.value.trim()
  if (!text || loading.value) {
    return
  }
  input.value = ''
  sendMessage(text)
}

/** Enter 发送,Shift+Enter 换行;中文输入法组词中的回车不发送 */
function handleEnter(event) {
  if (event.isComposing || event.keyCode === 229 || event.shiftKey) {
    return
  }
  event.preventDefault()
  handleSend()
}
</script>

<template>
  <div class="page">
    <div class="chat-card">
      <div class="chat-header">
        <div class="icon-circle chat-avatar">
          <el-icon><ChatDotRound /></el-icon>
        </div>
        <div>
          <div class="chat-title">AI 学习助手</div>
          <div class="chat-online">
            <span class="chat-online-dot"></span>
            在线
          </div>
        </div>
      </div>

      <div ref="messageAreaRef" class="chat-body">
        <!-- 拉历史中 -->
        <div v-if="historyLoading" class="chat-row is-ai">
          <div class="message-bubble message-ai">
            <div class="message-role">AI 助手</div>
            <div class="message-text thinking">正在加载历史消息...</div>
          </div>
        </div>

        <!-- 没有历史消息:空状态欢迎语 -->
        <div v-else-if="messages.length === 0" class="chat-row is-ai">
          <div class="message-bubble message-ai">
            <div class="message-role">AI 助手</div>
            <div class="message-text">你好，我是你的学习助手，有什么想聊的？</div>
          </div>
        </div>

        <!-- 历史消息与刚发的消息共用这一套渲染:user 靠右,assistant 靠左 -->
        <div
          v-for="(msg, index) in messages"
          :key="msg.id != null ? msg.id : `local-${index}`"
          class="chat-row"
          :class="msg.role === 'user' ? 'is-user' : 'is-ai'"
        >
          <div class="message-block">
            <div
              class="message-bubble"
              :class="msg.role === 'user' ? 'message-user' : 'message-ai'"
            >
              <div class="message-role">{{ msg.role === 'user' ? '我' : 'AI 助手' }}</div>

              <!-- 流式过程中:还没收到第一个字时先显示「AI 正在思考...」,末尾跟一个闪烁光标 -->
              <div class="message-text" :class="{ thinking: msg.streaming && !msg.content }">
                <template v-if="msg.streaming && !msg.content">AI 正在思考...</template>
                <template v-else>{{ msg.content }}</template>
                <span v-if="msg.streaming" class="typing-cursor"></span>
              </div>
            </div>

            <!-- 这条回复中断了(网络断了 / 服务端在流里报错) -->
            <div v-if="msg.failed" class="message-failed">回复中断，可以再问一次</div>

            <!-- 时间戳放在气泡下方:用户消息贴左,AI 消息贴右 -->
            <div v-if="formatMessageTime(msg.createdAt)" class="message-time">
              {{ formatMessageTime(msg.createdAt) }}
            </div>
          </div>
        </div>

        <div ref="bottomRef" class="chat-bottom"></div>
      </div>

      <div class="chat-footer">
        <!-- 快捷操作:点击直接把预置文本当消息发出去,不经过输入框 -->
        <div class="quick-actions">
          <button
            v-for="item in QUICK_ACTIONS"
            :key="item.label"
            type="button"
            class="quick-btn"
            :disabled="loading"
            @click="sendMessage(item.message)"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="chat-footer-row">
          <el-input
            v-model="input"
            class="chat-input"
            type="textarea"
            :rows="2"
            resize="none"
            :disabled="loading"
            placeholder="输入消息,Enter 发送,Shift+Enter 换行"
            @keydown.enter="handleEnter"
          />
          <el-button
            type="primary"
            :loading="loading"
            :disabled="loading"
            @click="handleSend"
          >
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-card {
  display: flex;
  flex-direction: column;
  height: 520px;
  background: #fff;
  border: 1px solid #eef2f8;
  border-radius: 20px;
  box-shadow: 0 8px 20px -10px rgba(140, 160, 210, 0.08);
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  background: rgba(248, 250, 252, 0.5);
  border-bottom: 1px solid #f0f4fa;
}

.chat-avatar {
  width: 36px;
  height: 36px;
  font-size: 0.9rem;
  background: #e2e8ff;
  color: #6b7ac9;
}

.chat-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: #1e293b;
}

.chat-online {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  font-size: 0.75rem;
  color: #34d399;
}

.chat-online-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6ee7b7;
}

.chat-body {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}

/* 滚动锚点,不占视觉高度 */
.chat-bottom {
  height: 1px;
}

/* 行负责左右对齐,气泡沿用原型的 message-bubble 尺寸 */
.chat-row {
  display: flex;
  margin-bottom: 12px;
}

.chat-row.is-user {
  justify-content: flex-end;
}

.chat-row.is-ai {
  justify-content: flex-start;
}

/* 一条消息 = 气泡 + 下方时间戳;整体用 grid,列宽由气泡决定 */
.message-block {
  display: grid;
  max-width: 80%;
  min-width: 0;
}

.message-bubble {
  max-width: 100%;
  padding: 10px 14px;
  border-radius: 16px;
  line-height: 1.5;
  background: #f1f5f9;
}

/* 时间戳:小字号浅灰,与气泡留 4px 间距 */
.message-time {
  margin-top: 4px;
  font-size: 0.7rem;
  color: #94a3b8;
}

/* 用户消息:时间贴气泡左下方 */
.chat-row.is-user .message-time {
  justify-self: start;
}

/* AI 消息:时间贴气泡右下方 */
.chat-row.is-ai .message-time {
  justify-self: end;
}

/* 流式输出时的闪烁光标 */
.typing-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: #6b7ac9;
  animation: cursor-blink 1s step-end infinite;
}

@keyframes cursor-blink {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0;
  }
}

/* 这条回复中断了(网络断了 / 服务端在流里报错) */
.message-failed {
  margin-top: 4px;
  font-size: 0.7rem;
  color: #f56c6c;
}

.message-user {
  background: #dce8ff;
  color: #1e293b;
  border-bottom-right-radius: 6px;
}

.message-ai {
  background: #fff;
  border: 1px solid #e9eef4;
  border-bottom-left-radius: 6px;
}

.message-role {
  margin-bottom: 2px;
  font-size: 12px;
  font-weight: 500;
  color: #909399;
}

.message-user .message-role {
  color: #5e6f8d;
}

.message-text {
  font-size: 14px;
  /* 保留 Shift+Enter 输入的多行换行 */
  white-space: pre-wrap;
  word-break: break-word;
}

/* 「AI 正在思考...」轻微呼吸效果 */
.thinking {
  color: #909399;
  animation: thinking-pulse 1.2s ease-in-out infinite;
}

@keyframes thinking-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.45;
  }
}

/* 纵向:上面一行快捷操作,下面输入框 + 发送按钮,两行不会重叠 */
.chat-footer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 20px;
  border-top: 1px solid #f0f4fa;
}

/* 输入框 + 发送按钮这一行(保持原来的横向布局与间距) */
.chat-footer-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

/* 快捷操作行:横向排列,一行放不下自动换行 */
.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 胶囊按钮:白底 + 浅紫边框 + 小字号,悬停浅紫底(色值取自 style.css 的紫色主题) */
.quick-btn {
  padding: 4px 12px;
  font-family: inherit;
  font-size: 12px;
  color: #5a6ab0;
  background: #fff;
  border: 1px solid #d0d8f3;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.quick-btn:hover {
  background: #e8ebfd;
  border-color: #a5b4e3;
}

/* AI 思考中按钮置灰,鼠标悬停也不再变色 */
.quick-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.quick-btn:disabled:hover {
  background: #fff;
  border-color: #d0d8f3;
}

.chat-input {
  flex: 1;
}
</style>

