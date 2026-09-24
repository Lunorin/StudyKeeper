<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../components/EmptyState.vue'
import {
  createTask,
  deleteTask,
  getTodayTasks,
  markDone,
  markUndo,
  updateTask
} from '../api/task'
import { createTasksBatch, planTasks } from '../api/ai'
import { showError } from '../utils/error'

/** 后端状态值:已完成 */
const STATUS_DONE = 'done'

/** 任务列表加载中 */
const loading = ref(false)
/** 弹窗保存中 */
const saving = ref(false)
/** 今日任务列表 */
const tasks = ref([])
/** 完成度统计,来自 GET /api/task/today */
const summary = ref({ date: '', total: 0, completed: 0, percent: 0 })

/** 弹窗显隐 */
const dialogVisible = ref(false)
/** 正在编辑的任务 id,null 表示新增 */
const editingTaskId = ref(null)
/** 弹窗表单:标题 + 开始时间 + 结束时间 */
const form = reactive({ title: '', startTime: '08:00', endTime: '09:00' })

/** 时间双下拉框的选项:小时 00-23,分钟 00-59(按 1 分钟递增) */
const HOUR_OPTIONS = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'))
const MINUTE_OPTIONS = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'))

/**
 * 把表单里的 "HH:mm" 字段拆成「小时 / 分钟」两个可写计算属性,给双下拉框用。
 * 这样 form.startTime / endTime 仍然是唯一的 "HH:mm" 字符串,校验与提交逻辑不用改。
 */
function timePart(source, field, index, fallback) {
  return computed({
    get: () => (source[field] || fallback).slice(index, index + 2),
    set: (value) => {
      const text = source[field] || fallback
      source[field] = index === 0 ? `${value}:${text.slice(3, 5)}` : `${text.slice(0, 2)}:${value}`
    }
  })
}

const startHour = timePart(form, 'startTime', 0, '08:00')
const startMinute = timePart(form, 'startTime', 3, '08:00')
const endHour = timePart(form, 'endTime', 0, '09:00')
const endMinute = timePart(form, 'endTime', 3, '09:00')

/** AI 帮我排弹窗显隐 */
const aiDialogVisible = ref(false)
/** AI 弹窗当前步骤:'input' 输入目标 / 'result' 预览拆解结果 */
const aiStep = ref('input')
/** 用户输入的目标 */
const aiGoal = ref('')
/** 调 /ai/plan 中 */
const aiPlanning = ref(false)
/** 调 /task/batch 中 */
const aiConfirming = ref(false)
/** AI 拆解出的任务(只预览,确认后才落库) */
const aiTasks = ref([])

const percent = computed(() => summary.value.percent || 0)

/** 顶部日期文案,例如 2026年9月17日 星期四 */
const dateText = computed(() => {
  const raw = summary.value.date
  if (!raw) return ''
  const [year, month, day] = String(raw).split('-').map(Number)
  const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  return `${year}年${month}月${day}日 ${weekdays[new Date(year, month - 1, day).getDay()]}`
})

/** 后端返回的 LocalTime 可能是 07:00 或 07:00:00,统一取 HH:mm */
function toHHmm(value) {
  return value ? String(value).slice(0, 5) : ''
}

/** 时段展示:07:00-07:30 */
function formatTimeRange(task) {
  const start = toHHmm(task.startTime)
  const end = toHHmm(task.endTime)
  if (!start && !end) return '时段待定'
  if (start && end) return `${start}-${end}`
  return start ? `${start} 起` : `至 ${end}`
}

/** 时长计算与后端保持一致:endTime 不晚于 startTime 时按跨天处理 */
function calcMinutes(startTime, endTime) {
  const start = toHHmm(startTime)
  const end = toHHmm(endTime)
  if (!start || !end) return null
  const [startHour, startMinute] = start.split(':').map(Number)
  const [endHour, endMinute] = end.split(':').map(Number)
  let minutes = endHour * 60 + endMinute - (startHour * 60 + startMinute)
  if (minutes <= 0) minutes += 24 * 60
  return minutes
}

/** 时长文案:30分钟 / 1小时 / 1小时30分钟 */
function minutesToText(minutes) {
  if (!minutes || minutes <= 0) return '时长待定'
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest}分钟`
  if (rest === 0) return `${hours}小时`
  return `${hours}小时${rest}分钟`
}

/** 优先用后端算好的 duration,缺失时按起止时间兜底计算 */
function formatDuration(task) {
  const minutes = task.duration ?? calcMinutes(task.startTime, task.endTime)
  return minutesToText(minutes)
}

function isDone(task) {
  return task.status === STATUS_DONE
}

/** 完成时间戳:后端 completedAt 是 ISO 字符串,取不到时当 0(排最后) */
function completionTime(task) {
  const raw = task.completedAt
  if (!raw) return 0
  // 兼容 "2026-09-18T00:09:30" 与带 7 位小数秒的 "…T22:41:11.5355218"
  const text = String(raw)
    .replace(' ', 'T')
    .replace(/(\.\d{3})\d+/, '$1')
  const time = new Date(text).getTime()
  return Number.isNaN(time) ? 0 : time
}

/**
 * 列表展示顺序:
 * 1) 待完成的任务排在前面(保持后端按开始时间的顺序);
 * 2) 已完成的任务全部挪到最后,多个已完成之间按「完成时间从晚到早」排。
 * 注意:filter 返回的是新数组,下面的 sort 不会改动 tasks 本身。
 */
const sortedTasks = computed(() => {
  const pending = tasks.value.filter((task) => !isDone(task))
  const done = tasks.value
    .filter((task) => isDone(task))
    .sort((a, b) => completionTime(b) - completionTime(a))
  return [...pending, ...done]
})

/** 新增任务使用的计划日期:优先用后端返回的今日日期,缺失时用本地日期兜底 */
function currentPlanDate() {
  if (summary.value.date) return String(summary.value.date).slice(0, 10)
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/** 拉取今日任务列表 */
async function loadTodayTasks() {
  loading.value = true
  try {
    const data = await getTodayTasks()
    summary.value = {
      date: data?.date || '',
      total: data?.total || 0,
      completed: data?.completed || 0,
      percent: data?.percent || 0
    }
    tasks.value = data?.tasks || []
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/** 完成 -> 重新拉取列表刷新 */
async function handleDone(task) {
  try {
    await markDone(task.id)
    ElMessage.success('已完成')
    await loadTodayTasks()
  } catch (error) {
    showError(error)
  }
}

/** 取消完成 -> 重新拉取列表刷新 */
async function handleUndo(task) {
  try {
    await markUndo(task.id)
    ElMessage.success('已取消完成')
    await loadTodayTasks()
  } catch (error) {
    showError(error)
  }
}

/** 删除:先 confirm 二次确认(避免误删),确认后再调接口并刷新 */
async function handleDelete(task) {
  try {
    await ElMessageBox.confirm(`确定删除任务「${task.title}」吗?`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    // 用户取消,不做任何处理
    return
  }

  try {
    await deleteTask(task.id)
    ElMessage.success('已删除')
    await loadTodayTasks()
  } catch (error) {
    showError(error)
  }
}

/** 打开新增弹窗 */
function openAddDialog() {
  editingTaskId.value = null
  form.title = ''
  form.startTime = '08:00'
  form.endTime = '09:00'
  dialogVisible.value = true
}

/** 打开编辑弹窗,复用同一个弹窗并回填 */
function openEditDialog(task) {
  editingTaskId.value = task.id
  form.title = task.title || ''
  form.startTime = toHHmm(task.startTime) || '08:00'
  form.endTime = toHHmm(task.endTime) || '09:00'
  dialogVisible.value = true
}

/** 弹窗保存:新增走后端 createTask,编辑走 updateTask */
async function handleSubmit() {
  const title = form.title.trim()
  if (!title) {
    ElMessage.warning('请填写任务标题')
    return
  }
  if (form.startTime >= form.endTime) {
    ElMessage.warning('结束时间需要晚于开始时间')
    return
  }

  saving.value = true
  try {
    if (editingTaskId.value === null) {
      await createTask({
        title,
        planDate: currentPlanDate(),
        startTime: form.startTime,
        endTime: form.endTime
      })
      ElMessage.success('添加成功')
    } else {
      await updateTask(editingTaskId.value, {
        title,
        startTime: form.startTime,
        endTime: form.endTime
      })
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    await loadTodayTasks()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

/* ==================== AI 帮我排 ==================== */

/** 打开 AI 弹窗:每次从第一步开始 */
function openAiDialog() {
  resetAiDialog()
  aiDialogVisible.value = true
}

/** 关闭后重置状态,下次打开是干净的 */
function resetAiDialog() {
  aiStep.value = 'input'
  aiGoal.value = ''
  aiTasks.value = []
  aiPlanning.value = false
  aiConfirming.value = false
}

const route = useRoute()
const router = useRouter()

/**
 * 打开 AI 弹窗(?aiArrange=1)。
 * 新用户引导第 3 步「去试试」会带这个参数跳过来;用完把参数从地址里去掉,
 * 刷新页面不会重复弹,URL 也保持干净。
 */
function openAiDialogFromQuery() {
  if (route.query.aiArrange !== '1') return

  openAiDialog()

  const query = { ...route.query }
  delete query.aiArrange
  router.replace({ query })
}

/** 第一步:把目标交给 AI 拆解成任务(只返回建议,不落库) */
async function handleAiPlan() {
  const goal = aiGoal.value.trim()
  if (!goal) {
    ElMessage.warning('请先输入今天要做什么')
    return
  }

  aiPlanning.value = true
  try {
    const data = await planTasks({ goal, planDate: currentPlanDate() })
    aiTasks.value = Array.isArray(data) ? data : []
    aiStep.value = 'result'
  } catch (error) {
    showError(error)
  } finally {
    aiPlanning.value = false
  }
}

/** 第二步 -> 第一步(保留已输入的目标,方便改一改重排) */
function backToAiInput() {
  aiStep.value = 'input'
}

/** AI 任务的时长:接口没有 duration 字段,用起止时间前端算 */
function aiTaskDuration(task) {
  return minutesToText(calcMinutes(task.startTime, task.endTime))
}

/** 确认添加:一次性批量落库 */
async function handleAiConfirm() {
  if (aiTasks.value.length === 0) {
    return
  }

  aiConfirming.value = true
  try {
    const result = await createTasksBatch({
      // planBatchId 由前端生成,后端用它标记「这一次 AI 排的任务」
      planBatchId: `batch_${Date.now()}`,
      planDate: currentPlanDate(),
      tasks: aiTasks.value.map((task) => ({
        title: task.title,
        startTime: task.startTime,
        endTime: task.endTime,
        priority: task.priority
      }))
    })

    ElMessage.success(`已添加 ${result?.createdCount ?? aiTasks.value.length} 条任务`)
    aiDialogVisible.value = false
    await loadTodayTasks()
  } catch (error) {
    // 后端还没实现 /api/task/batch 时会返回 405(或 404),给一句能看懂的话
    const status = error?.response?.status
    if (status === 405 || status === 404) {
      ElMessage.error('批量添加接口暂不可用(后端 /api/task/batch 尚未实现)')
    } else {
      showError(error)
    }
  } finally {
    aiConfirming.value = false
  }
}

onMounted(loadTodayTasks)

/**
 * 用 watch + immediate 而不是只挂 onMounted:
 * 引导弹窗就弹在今日页上,点「去试试」是「同一个路由只换 query」——组件不会重新挂载,onMounted 不会再跑。
 */
watch(() => route.query.aiArrange, openAiDialogFromQuery, { immediate: true })
</script>

<template>
  <div class="page">
    <!-- 今天还没定计划:统一空状态,点「AI 帮我排」直接让 AI 拆解 -->
    <EmptyState
      v-if="!loading && tasks.length === 0"
      class="plan-empty"
      icon="🎯"
      text="今天还没定计划"
      sub-text="说说今天要做什么？"
      action-text="AI 帮我排"
      @action="openAiDialog"
    />

    <!-- 日期 + 完成度 + 操作按钮 -->
    <el-card class="summary-card" shadow="never">
      <el-skeleton v-if="loading && tasks.length === 0" :rows="2" animated />

      <div v-else class="summary-row">
        <div class="summary-left">
          <div class="summary-date">{{ dateText }}</div>
          <div class="summary-progress">
            <span class="summary-label">今日完成度</span>
            <span class="summary-count">{{ summary.completed }}/{{ summary.total }}</span>
            <el-tag type="primary" size="small" effect="plain">{{ percent }}%</el-tag>
          </div>
          <el-progress
            class="summary-bar"
            :percentage="percent"
            :stroke-width="8"
            :show-text="false"
          />
        </div>

        <div class="summary-right">
          <el-button @click="openAddDialog">添加今日任务</el-button>
          <el-button type="primary" @click="openAiDialog">AI 帮我排</el-button>
        </div>
      </div>
    </el-card>

    <!-- 任务列表 -->
    <el-card class="task-card" shadow="never">
      <template #header>
        <span class="task-card-title">任务列表</span>
      </template>

      <el-skeleton v-if="loading" :rows="4" animated />

      <div v-else-if="tasks.length === 0" class="empty-tip">暂无任务</div>

      <div v-else class="task-list">
        <div v-for="task in sortedTasks" :key="task.id" class="task-item">
          <div class="task-info">
            <div class="task-title" :class="{ 'is-done': isDone(task) }">{{ task.title }}</div>
            <div class="task-meta">{{ formatTimeRange(task) }} · {{ formatDuration(task) }}</div>
          </div>

          <div class="task-actions">
            <span class="status-badge" :class="{ done: isDone(task) }">
              {{ isDone(task) ? '已完成' : '未完成' }}
            </span>

            <el-button v-if="isDone(task)" size="small" @click="handleUndo(task)">
              取消完成
            </el-button>

            <template v-else>
              <el-button type="primary" size="small" @click="handleDone(task)">完成</el-button>
              <el-button size="small" @click="openEditDialog(task)">编辑</el-button>
            </template>

            <el-button type="danger" size="small" plain @click="handleDelete(task)">删除</el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 新增 / 编辑弹窗(暂内联在本页面) -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingTaskId === null ? '添加今日任务' : '编辑今日任务'"
      width="420px"
    >
      <el-form label-width="80px">
        <el-form-item label="任务标题">
          <el-input v-model="form.title" placeholder="例如：做数学卷子" clearable />
        </el-form-item>

        <el-form-item label="开始时间">
          <div class="time-picker">
            <select v-model="startHour" class="time-select">
              <option v-for="hour in HOUR_OPTIONS" :key="hour" :value="hour">{{ hour }}</option>
            </select>
            <span class="time-sep">:</span>
            <select v-model="startMinute" class="time-select">
              <option v-for="minute in MINUTE_OPTIONS" :key="minute" :value="minute">
                {{ minute }}
              </option>
            </select>
          </div>
        </el-form-item>

        <el-form-item label="结束时间">
          <div class="time-picker">
            <select v-model="endHour" class="time-select">
              <option v-for="hour in HOUR_OPTIONS" :key="hour" :value="hour">{{ hour }}</option>
            </select>
            <span class="time-sep">:</span>
            <select v-model="endMinute" class="time-select">
              <option v-for="minute in MINUTE_OPTIONS" :key="minute" :value="minute">
                {{ minute }}
              </option>
            </select>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">
          {{ editingTaskId === null ? '添加' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- AI 帮我排弹窗(暂内联在本页面):第一步输入目标,第二步预览拆解结果 -->
    <el-dialog
      v-model="aiDialogVisible"
      title="AI 帮我排"
      width="640px"
      @closed="resetAiDialog"
    >
      <!-- 第一步:输入今天要做什么 -->
      <div v-if="aiStep === 'input'">
        <el-form label-position="top">
          <el-form-item label="今天要做什么？">
            <el-input
              v-model="aiGoal"
              type="textarea"
              :rows="3"
              placeholder="例如：复习数学，准备英语考试"
            />
          </el-form-item>
        </el-form>
      </div>

      <!-- 第二步:预览 AI 拆解出的任务 -->
      <div v-else>
        <div class="ai-tip">AI 已为你拆解出以下任务：</div>

        <div v-if="aiTasks.length === 0" class="empty-tip">AI 没有拆解出任务,换个说法再试试</div>

        <div v-else class="ai-task-list">
          <div v-for="(task, index) in aiTasks" :key="index" class="ai-task-preview">
            <div class="ai-task-index">{{ index + 1 }}</div>
            <div class="ai-task-info">
              <div class="ai-task-title">{{ task.title }}</div>
              <div class="ai-task-meta">
                {{ formatTimeRange(task) }} · {{ aiTaskDuration(task) }}
              </div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <template v-if="aiStep === 'input'">
          <el-button @click="aiDialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="aiPlanning"
            :disabled="aiPlanning"
            @click="handleAiPlan"
          >
            提交
          </el-button>
        </template>

        <template v-else>
          <el-button :disabled="aiConfirming" @click="backToAiInput">返回</el-button>
          <el-button
            type="primary"
            :loading="aiConfirming"
            :disabled="aiConfirming || aiTasks.length === 0"
            @click="handleAiConfirm"
          >
            确认添加
          </el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.summary-card {
  margin-bottom: 16px;
}

.summary-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.summary-date {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text);
}

.summary-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0;
}

.summary-label {
  font-size: 14px;
  color: var(--text-secondary);
}

.summary-count {
  font-weight: 600;
  color: var(--text);
}

.summary-bar {
  max-width: 192px;
}

.summary-right {
  display: flex;
  gap: 8px;
}

.summary-right :deep(.el-button + .el-button) {
  margin-left: 0;
}

.task-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.empty-tip {
  padding: 32px 0;
  text-align: center;
  font-size: 14px;
  color: var(--text-muted);
}

.task-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 4px;
  border-bottom: 1px solid var(--border);
  border-radius: 6px;
  transition: background 0.12s ease;
}

.task-item:hover {
  background: var(--bg);
}

.task-item:last-child {
  border-bottom: none;
}

.task-info {
  flex: 1;
  min-width: 180px;
}

.task-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.task-title.is-done {
  color: var(--text-muted);
  text-decoration: line-through;
}

.task-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.task-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.task-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 时间双下拉框的公共样式(.time-picker / .time-select / .time-sep)已放在全局 src/style.css */

/* AI 帮我排弹窗 */
.ai-tip {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--text-muted);
}

/* 结果可能很多(实测一次 11 条),固定高度可滚动;行样式用全局 .ai-task-preview */
.ai-task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  overflow-y: auto;
}

.ai-task-info {
  flex: 1;
  min-width: 0;
}

.ai-task-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  word-break: break-all;
}

.ai-task-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
}

/* 空状态与下面的卡片之间留 16px(沿用原来提示条的间距) */
.plan-empty {
  margin-bottom: 16px;
}
</style>

