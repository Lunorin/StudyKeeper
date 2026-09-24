<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '../components/EmptyState.vue'
import {
  createCourse,
  deleteCourse,
  listCourses,
  updateCourse
} from '../api/course'
import {
  createRestTime,
  deleteRestTime,
  listRestTimes,
  updateRestTime
} from '../api/rest'
import { getTodayStats, getTrendStats, getWeekStats } from '../api/stats'
import { createMemory, deleteMemory, listMemories } from '../api/memory'
import { parseCourse } from '../api/ai'
import { showError } from '../utils/error'
import { Calendar, Histogram, MagicStick, Plus, Sunny } from '@element-plus/icons-vue'

/** 星期数字 -> 中文,下标 0 对应 1(周一) */
const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日']

/** 周几切换按钮 */
const dayOptions = WEEK_LABELS.map((label, index) => ({ value: index + 1, label: `周${label}` }))

/** 当前 Tab(可由 ?tab= 指定,见 applyTabFromQuery) */
const activeTab = ref('course')

const route = useRoute()

/** 默认选中今天:JS 的 getDay() 里 0 是周日,这里要转成 1~7 */
function todayDayOfWeek() {
  const jsDay = new Date().getDay()
  return jsDay === 0 ? 7 : jsDay
}

/** 课程列表加载中 */
const loading = ref(false)
/** 弹窗保存中 */
const saving = ref(false)
/** 整周全部课程(网格课表的数据源) */
const courses = ref([])

/** 弹窗显隐 */
const dialogVisible = ref(false)
/** 正在编辑的课程 id,null 表示新增 */
const editingId = ref(null)
/** 弹窗表单:课程名 + 周几(多选) + 开始/结束时间 + 地点 */
const form = reactive({
  courseName: '',
  days: [],
  startTime: '08:00',
  endTime: '09:40',
  location: ''
})

/** 时间双下拉框的选项:小时 00-23,分钟 00-59(按 1 分钟递增) */
const HOUR_OPTIONS = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'))
const MINUTE_OPTIONS = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'))

/**
 * 把某个表单里的 "HH:mm" 字段拆成「小时 / 分钟」两个可写计算属性,给双下拉框用。
 * 表单里的 startTime / endTime 仍是唯一的 "HH:mm" 字符串,校验与提交逻辑不用改。
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

/** 课程弹窗的时间双下拉框 */
const courseStartHour = timePart(form, 'startTime', 0, '08:00')
const courseStartMinute = timePart(form, 'startTime', 3, '08:00')
const courseEndHour = timePart(form, 'endTime', 0, '09:40')
const courseEndMinute = timePart(form, 'endTime', 3, '09:40')

function dayLabel(value) {
  return dayOptions.find((day) => day.value === value)?.label || ''
}

/** 后端已经是 HH:mm,这里只做兜底截断(防止出现 08:00:00) */
function toHHmm(value) {
  return value ? String(value).slice(0, 5) : ''
}

/** 时间展示:08:00-09:40 */
function formatTimeRange(course) {
  const start = toHHmm(course.startTime)
  const end = toHHmm(course.endTime)
  if (!start || !end) return '时间待定'
  return `${start}-${end}`
}

/* ==================== 整周网格课表 ==================== */

/** 每小时占多少像素(原型:1 小时 = 60px,也就是 1 分钟 = 1px) */
const HOUR_HEIGHT = 60

/** 同一课程名 -> 同一浅色(原型 getCourseColor 的 7 色表) */
const COURSE_COLORS = [
  { bg: '#FFFDE7', border: '#FFF9C4', text: '#F57F17' },
  { bg: '#E3F2FD', border: '#BBDEFB', text: '#0D47A1' },
  { bg: '#E8F5E9', border: '#C8E6C9', text: '#1B5E20' },
  { bg: '#F3E5F5', border: '#E1BEE7', text: '#4A148C' },
  { bg: '#FFEBEE', border: '#FFCDD2', text: '#B71C1C' },
  { bg: '#E0F7FA', border: '#B2EBF2', text: '#006064' },
  { bg: '#FFF3E0', border: '#FFE0B2', text: '#E65100' }
]

/** HH:mm -> 当天的第几分钟;解析不出来返回 null */
function toMinutes(value) {
  const text = toHHmm(value)
  if (!text) return null
  const [hour, minute] = text.split(':').map(Number)
  if (Number.isNaN(hour) || Number.isNaN(minute)) return null
  return hour * 60 + minute
}

/** 课程块配色:按课程名哈希,保证同名同色 */
function courseColor(name) {
  const text = String(name || '')
  let hash = 0
  for (let i = 0; i < text.length; i += 1) {
    hash = text.charCodeAt(i) + ((hash << 5) - hash)
  }
  return COURSE_COLORS[Math.abs(hash) % COURSE_COLORS.length]
}

/** 时间轴起始小时:默认 08:00,有更早的课就往前扩 */
const gridStartHour = computed(() => {
  let minStart = 8 * 60
  courses.value.forEach((course) => {
    const start = toMinutes(course.startTime)
    if (start !== null && start < minStart) {
      minStart = start
    }
  })
  return Math.floor(minStart / 60)
})

/** 时间轴结束小时:默认 22:00,有更晚的课就往后扩 */
const gridEndHour = computed(() => {
  let maxEnd = 22 * 60
  courses.value.forEach((course) => {
    const end = toMinutes(course.endTime)
    if (end !== null && end > maxEnd) {
      maxEnd = end
    }
  })
  return Math.ceil(maxEnd / 60)
})

/** 左侧时间轴的整点标签 */
const hourLabels = computed(() => {
  const labels = []
  for (let hour = gridStartHour.value; hour < gridEndHour.value; hour += 1) {
    labels.push(`${String(hour).padStart(2, '0')}:00`)
  }
  return labels
})

/** 网格内容总高度(容器固定 600px,内部滚动) */
const gridHeight = computed(() => (gridEndHour.value - gridStartHour.value) * HOUR_HEIGHT)

/**
 * 课程块的绝对定位 + 配色,算法与原型一致:
 * top / height 按分钟(1 分钟 = 1px),left / width 按 7 等分列。
 */
function courseBlockStyle(course) {
  const start = toMinutes(course.startTime)
  const end = toMinutes(course.endTime)
  const dayIndex = Math.min(Math.max((course.dayOfWeek || 1) - 1, 0), 6)
  const top = start === null ? 0 : start - gridStartHour.value * 60
  const height = start !== null && end !== null && end > start ? end - start : HOUR_HEIGHT
  const color = courseColor(course.courseName)

  return {
    top: `${top}px`,
    height: `${height}px`,
    left: `calc(${dayIndex} * 14.285% + 2px)`,
    width: 'calc(14.285% - 4px)',
    background: color.bg,
    border: `1px solid ${color.border}`,
    color: color.text
  }
}

/** 拉取整周课程(不传 dayOfWeek,后端返回全部) */
async function loadCourses() {
  loading.value = true
  try {
    const data = await listCourses()
    courses.value = Array.isArray(data) ? data : []
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/** 打开新增弹窗:周几默认勾上今天 */
function openCreateDialog() {
  editingId.value = null
  form.courseName = ''
  form.days = [todayDayOfWeek()]
  form.startTime = '08:00'
  form.endTime = '09:40'
  form.location = ''
  dialogVisible.value = true
}

/** 打开编辑弹窗,复用同一个弹窗并回填 */
function openEditDialog(course) {
  editingId.value = course.id
  form.courseName = course.courseName || ''
  form.days = course.dayOfWeek ? [course.dayOfWeek] : []
  form.startTime = toHHmm(course.startTime) || '08:00'
  form.endTime = toHHmm(course.endTime) || '09:40'
  form.location = course.location || ''
  dialogVisible.value = true
}

/** 弹窗提交 */
async function handleSubmit() {
  const courseName = form.courseName.trim()
  if (!courseName) {
    ElMessage.warning('请填写课程名')
    return
  }
  if (form.days.length === 0) {
    ElMessage.warning('请至少选择一个星期')
    return
  }
  if (form.startTime >= form.endTime) {
    ElMessage.warning('结束时间需要晚于开始时间')
    return
  }

  saving.value = true
  try {
    const days = [...form.days].sort((a, b) => a - b)
    // 地点固定传字符串:后端 update 把 null 当成「不修改」,传 null 会导致清空地点无效
    const base = {
      courseName,
      startTime: form.startTime,
      endTime: form.endTime,
      location: form.location.trim()
    }

    if (editingId.value === null) {
      // 一个弹窗多天:接口一次只能加一天,勾了几天就循环调几次
      for (const day of days) {
        await createCourse({ ...base, dayOfWeek: day })
      }
      ElMessage.success('新增成功')
    } else {
      // 编辑:第一天改当前这条,多出来的天当新增处理
      await updateCourse(editingId.value, { ...base, dayOfWeek: days[0] })
      const extraDays = days.slice(1)
      for (const day of extraDays) {
        await createCourse({ ...base, dayOfWeek: day })
      }
      ElMessage.success(
        extraDays.length > 0 ? `保存成功,并新增 ${extraDays.length} 天的课程` : '保存成功'
      )
    }

    dialogVisible.value = false
    await loadCourses()
  } catch (error) {
    showError(error)
    // 多天循环可能已经写成功了一部分,刷新一下让列表和数据库保持一致
    await loadCourses()
  } finally {
    saving.value = false
  }
}

/** 删除:先 confirm,确认后调接口并刷新列表 */
async function handleDelete(course) {
  try {
    await ElMessageBox.confirm(`确定删除课程「${course.courseName}」吗?`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    // 用户取消,不做任何处理
    return
  }

  try {
    await deleteCourse(course.id)
    ElMessage.success('已删除')
    await loadCourses()
  } catch (error) {
    showError(error)
  }
}

/* ==================== 休息时间 Tab ==================== */

/** 生效范围选项:0 表示「每天」,提交时转成 dayOfWeek = null;1~7 复用课程表的 dayOptions */
const restScopeOptions = [{ value: 0, label: '每天' }, ...dayOptions]

/** 休息时段列表加载中 */
const restLoading = ref(false)
/** 弹窗保存中 */
const restSaving = ref(false)
/** 休息时段列表(顺序直接用后端的,前端不再排) */
const restTimes = ref([])

/** 弹窗显隐 */
const restDialogVisible = ref(false)
/** 正在编辑的休息时段 id,null 表示新增 */
const restEditingId = ref(null)
/** 弹窗表单:生效范围 + 开始/结束时间 + 标签 */
const restForm = reactive({ dayOfWeek: 0, startTime: '10:00', endTime: '10:20', label: '' })

/** 休息时段弹窗的时间双下拉框 */
const restStartHour = timePart(restForm, 'startTime', 0, '10:00')
const restStartMinute = timePart(restForm, 'startTime', 3, '10:00')
const restEndHour = timePart(restForm, 'endTime', 0, '10:20')
const restEndMinute = timePart(restForm, 'endTime', 3, '10:20')

/** 生效范围文案:null -> 每天 */
function restScopeLabel(dayOfWeek) {
  if (dayOfWeek === null || dayOfWeek === undefined) return '每天'
  return dayLabel(dayOfWeek)
}

/** 时间范围展示:10:00 - 10:20 */
function formatRestRange(restTime) {
  const start = toHHmm(restTime.startTime)
  const end = toHHmm(restTime.endTime)
  if (!start || !end) return '时间待定'
  return `${start} - ${end}`
}

/** 拉取休息时段列表 */
async function loadRestTimes() {
  restLoading.value = true
  try {
    const data = await listRestTimes()
    restTimes.value = Array.isArray(data) ? data : []
  } catch (error) {
    showError(error)
  } finally {
    restLoading.value = false
  }
}

/** 打开新增弹窗 */
function openRestCreateDialog() {
  restEditingId.value = null
  restForm.dayOfWeek = 0
  restForm.startTime = '10:00'
  restForm.endTime = '10:20'
  restForm.label = ''
  restDialogVisible.value = true
}

/** 打开编辑弹窗,复用同一个弹窗并回填 */
function openRestEditDialog(restTime) {
  restEditingId.value = restTime.id
  restForm.dayOfWeek =
    restTime.dayOfWeek === null || restTime.dayOfWeek === undefined ? 0 : restTime.dayOfWeek
  restForm.startTime = toHHmm(restTime.startTime) || '10:00'
  restForm.endTime = toHHmm(restTime.endTime) || '10:20'
  restForm.label = restTime.label || ''
  restDialogVisible.value = true
}

/** 弹窗提交 */
async function handleRestSubmit() {
  if (restForm.startTime >= restForm.endTime) {
    ElMessage.warning('结束时间需要晚于开始时间')
    return
  }

  // 0 是「每天」的哨兵值,提交时还原成后端要的 null
  const dayOfWeek = restForm.dayOfWeek === 0 ? null : restForm.dayOfWeek
  // 标签固定传字符串:后端 update 把 null 当成「不修改」,传 null 会清不掉标签
  const base = {
    startTime: restForm.startTime,
    endTime: restForm.endTime,
    label: restForm.label.trim()
  }

  restSaving.value = true
  try {
    if (restEditingId.value === null) {
      await createRestTime({ ...base, dayOfWeek })
      ElMessage.success('新增成功')
    } else {
      const current = restTimes.value.find((item) => item.id === restEditingId.value)
      if (dayOfWeek === null && current && current.dayOfWeek !== null) {
        // 后端 update 改不回「每天」(null 会被当成不修改),按其后端注释建议:先新建再删旧的
        await createRestTime({ ...base, dayOfWeek: null })
        await deleteRestTime(restEditingId.value)
        ElMessage.success('已改为每天生效')
      } else {
        await updateRestTime(restEditingId.value, { ...base, dayOfWeek })
        ElMessage.success('保存成功')
      }
    }

    restDialogVisible.value = false
    await loadRestTimes()
  } catch (error) {
    showError(error)
    await loadRestTimes()
  } finally {
    restSaving.value = false
  }
}

/** 删除:先 confirm,确认后调接口并刷新列表 */
async function handleRestDelete(restTime) {
  try {
    await ElMessageBox.confirm(
      `确定删除休息时段「${restScopeLabel(restTime.dayOfWeek)} ${formatRestRange(restTime)}」吗?`,
      '删除确认',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    // 用户取消,不做任何处理
    return
  }

  try {
    await deleteRestTime(restTime.id)
    ElMessage.success('已删除')
    await loadRestTimes()
  } catch (error) {
    showError(error)
  }
}

/* ==================== 统计 Tab ==================== */

/** 统计数据加载中 */
const statsLoading = ref(false)
/** 今日完成度 */
const todayStats = ref({ total: 0, completed: 0, percent: 0 })
/** 本周完成度 */
const weekStats = ref({ weekStart: '', weekEnd: '', total: 0, completed: 0, percent: 0 })
/** 近 7 天趋势 */
const trendStats = ref([])

/** 柱状图的最大值,用来算柱高比例(至少取 1,避免除零) */
const trendMax = computed(() => {
  const values = trendStats.value.map((item) => Number(item.completed) || 0)
  return Math.max(...values, 1)
})

/** 柱高百分比:按当日 completed 占最大值的比例 */
function barHeight(item) {
  const completed = Number(item.completed) || 0
  return `${(completed / trendMax.value) * 100}%`
}

/** 横轴短日期:2026-09-11 -> 09-11 */
function shortDate(date) {
  return date ? String(date).slice(5, 10) : ''
}

/** 本周区间:09-15 ~ 09-21 */
const weekRangeText = computed(() => {
  const { weekStart, weekEnd } = weekStats.value
  if (!weekStart || !weekEnd) return ''
  return `${shortDate(weekStart)} ~ ${shortDate(weekEnd)}`
})

/** 同时拉三份统计数据 */
async function loadStats() {
  statsLoading.value = true
  try {
    const [today, week, trend] = await Promise.all([
      getTodayStats(),
      getWeekStats(),
      getTrendStats(7)
    ])

    todayStats.value = {
      total: today?.total || 0,
      completed: today?.completed || 0,
      percent: today?.percent || 0
    }
    weekStats.value = {
      weekStart: week?.weekStart || '',
      weekEnd: week?.weekEnd || '',
      total: week?.total || 0,
      completed: week?.completed || 0,
      percent: week?.percent || 0
    }
    trendStats.value = Array.isArray(trend) ? trend : []
  } catch (error) {
    showError(error)
  } finally {
    statsLoading.value = false
  }
}

/** 切到「统计」/「AI 对我的了解」Tab 时才加载 */
function handleTabChange(name) {
  if (name === 'stats') {
    loadStats()
  } else if (name === 'memory') {
    loadMemories()
  }
}

/** el-tabs 的标签名 -> 内部 name */
const TAB_NAME_BY_LABEL = {
  '课程表': 'course',
  '休息时间': 'rest',
  '统计': 'stats',
  'AI 对我的了解': 'memory'
}

/** ?tab= 允许传内部 name(course)也允许传标签名(课程表) */
function resolveTabName(tab) {
  if (typeof tab !== 'string') return ''

  const value = tab.trim()
  if (!value) return ''
  if (Object.values(TAB_NAME_BY_LABEL).includes(value)) return value

  return TAB_NAME_BY_LABEL[value] || ''
}

/** 按 ?tab= 定位页签(新用户引导第 2 步「去填课表」会带 ?tab=课程表 跳过来) */
function applyTabFromQuery() {
  const name = resolveTabName(route.query.tab)
  if (!name || name === activeTab.value) return

  activeTab.value = name
  // 统计 / AI 对我的了解是懒加载的,程序化切过去也要补一次数据
  handleTabChange(name)
}

/* ==================== AI 对我的了解 Tab(用户画像记忆) ==================== */

/**
 * category 英文值 -> 中文显示。
 * 对象的键顺序就是分组展示顺序:event > goal > emotion > habit > preference
 */
const MEMORY_CATEGORY_LABELS = {
  event: '重要事件',
  goal: '长期目标',
  emotion: '情绪状态',
  habit: '学习习惯',
  preference: '偏好'
}

/** 分组顺序:固定 5 类,以它为基准分组 */
const MEMORY_CATEGORY_ORDER = Object.keys(MEMORY_CATEGORY_LABELS)

/** 下拉选项:value 传英文给后端,label 显示中文 */
const MEMORY_CATEGORY_OPTIONS = MEMORY_CATEGORY_ORDER.map((value) => ({
  value,
  label: MEMORY_CATEGORY_LABELS[value]
}))

/** 分组标题的颜色标记:沿用课表那套「浅底 + 深字」配色,让 5 类一眼能区分 */
const MEMORY_CATEGORY_COLORS = {
  event: { bg: '#F3E5F5', color: '#4A148C' },
  goal: { bg: '#E3F2FD', color: '#0D47A1' },
  emotion: { bg: '#FFEBEE', color: '#B71C1C' },
  habit: { bg: '#E8F5E9', color: '#1B5E20' },
  preference: { bg: '#FFF3E0', color: '#E65100' }
}

/** 没见过的 category 用中性灰兜底 */
const MEMORY_FALLBACK_COLOR = { bg: '#f1f5f9', color: '#475569' }

/** 内容长度上限,与后端校验保持一致 */
const MEMORY_CONTENT_MAX = 500

/** 记忆列表加载中(只在进入 Tab 首次加载时显示骨架屏) */
const memoryLoading = ref(false)
/** 弹窗保存中 */
const memorySaving = ref(false)
/** 记忆列表(原始数据,展示顺序交给 memoryGroups) */
const memories = ref([])

/** 弹窗显隐 */
const memoryDialogVisible = ref(false)
/** 弹窗表单:类别 + 内容 */
const memoryForm = reactive({ category: MEMORY_CATEGORY_ORDER[0], content: '' })

/** 取某个分组标记的配色 */
function memoryColor(category) {
  return MEMORY_CATEGORY_COLORS[category] || MEMORY_FALLBACK_COLOR
}

/**
 * 按 category 分组:分组顺序固定 event > goal > emotion > habit > preference,
 * 组内保持后端返回的顺序(后端已排好序);没有记忆的分组不展示。
 */
const memoryGroups = computed(() => {
  const groupMap = new Map()
  const groups = MEMORY_CATEGORY_ORDER.map((category) => {
    const group = { category, label: MEMORY_CATEGORY_LABELS[category], items: [] }
    groupMap.set(category, group)
    return group
  })

  memories.value.forEach((memory) => {
    const category = memory?.category
    let group = groupMap.get(category)
    if (!group) {
      // 兜底:万一出现 5 类之外的 category,追加一组,别把这条记忆吞掉
      group = { category, label: category || '其他', items: [] }
      groupMap.set(category, group)
      groups.push(group)
    }
    group.items.push(memory)
  })

  return groups.filter((group) => group.items.length > 0)
})

/** 确认框里的内容摘要:太长就截断(内容上限 500 字,整段塞进弹窗会很难看) */
function memorySummary(content, max = 30) {
  const text = String(content || '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > max ? `${text.slice(0, max)}…` : text
}

/**
 * 拉取记忆列表。
 * withSkeleton = false 用于增 / 删后的刷新:不显示骨架屏,列表不会被占位符替换,
 * 页面也就不会因为内容高度塌陷而跳回顶部。
 */
async function loadMemories(withSkeleton = true) {
  if (withSkeleton) {
    memoryLoading.value = true
  }

  try {
    const data = await listMemories()
    memories.value = Array.isArray(data) ? data : []
  } catch (error) {
    showError(error)
  } finally {
    if (withSkeleton) {
      memoryLoading.value = false
    }
  }
}

/** 打开手动添加弹窗 */
function openMemoryDialog() {
  memoryForm.category = MEMORY_CATEGORY_ORDER[0]
  memoryForm.content = ''
  memoryDialogVisible.value = true
}

/** 弹窗提交:POST /api/memory */
async function handleMemorySubmit() {
  const content = memoryForm.content.trim()
  if (!content) {
    ElMessage.warning('请填写记忆内容')
    return
  }
  if (content.length > MEMORY_CONTENT_MAX) {
    ElMessage.warning(`内容不能超过 ${MEMORY_CONTENT_MAX} 字`)
    return
  }

  memorySaving.value = true
  try {
    await createMemory({ category: memoryForm.category, content })
    ElMessage.success('已添加')
    memoryDialogVisible.value = false
    await loadMemories(false)
  } catch (error) {
    showError(error)
  } finally {
    memorySaving.value = false
  }
}

/** 删除:先 confirm,确认后调接口并刷新(刷新不显示骨架屏,避免页面回顶) */
async function handleMemoryDelete(memory) {
  const summary = memorySummary(memory.content)
  try {
    await ElMessageBox.confirm(
      summary ? `确定删除记忆「${summary}」吗?` : '确定删除这条记忆吗?',
      '删除确认',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    // 用户取消,不做任何处理
    return
  }

  try {
    await deleteMemory(memory.id)
    ElMessage.success('已删除')
    await loadMemories(false)
  } catch (error) {
    showError(error)
  }
}

/* ==================== AI 帮我加课程 ==================== */

/** 输入框里的多行示例 */
const AI_RAW_PLACEHOLDER = '例如：\n每周一三五 8:00-9:40 高等数学\n每周二 14:00-15:40 程序设计'

/** AI 加课程弹窗显隐 */
const aiDialogVisible = ref(false)
/** AI 弹窗当前步骤:'input' 输入课程文本 / 'result' 预览解析结果 */
const aiStep = ref('input')
/** 用户粘贴的课程文本 */
const aiRawText = ref('')
/** 调 /ai/parse-course 中 */
const aiParsing = ref(false)
/** 逐条保存课程中 */
const aiSaving = ref(false)
/** 解析出的课程(额外带 checked / added 两个纯前端状态) */
const aiCourses = ref([])
/** 没能识别的原始行 */
const aiFailed = ref([])

/** [1,3,5] -> 周一/三/五 */
function formatDaysOfWeek(days) {
  if (!Array.isArray(days) || days.length === 0) return '未指定星期'
  const labels = [...days]
    .sort((a, b) => a - b)
    .map((day) => WEEK_LABELS[day - 1])
    .filter(Boolean)
  return labels.length === 0 ? '未指定星期' : `周${labels.join('/')}`
}

/** 打开弹窗:每次从第一步开始 */
function openAiCourseDialog() {
  resetAiCourseDialog()
  aiDialogVisible.value = true
}

/** 关闭后重置状态,下次打开是干净的 */
function resetAiCourseDialog() {
  aiStep.value = 'input'
  aiRawText.value = ''
  aiCourses.value = []
  aiFailed.value = []
  aiParsing.value = false
  aiSaving.value = false
}

/** 第一步:把课程文本交给 AI 解析(只解析,不落库) */
async function handleAiParse() {
  const rawText = aiRawText.value.trim()
  if (!rawText) {
    ElMessage.warning('请输入课程内容')
    return
  }

  aiParsing.value = true
  try {
    const data = await parseCourse({ rawText })
    aiCourses.value = (data?.courses || []).map((course) => ({
      ...course,
      checked: true, // 默认勾选,用户可取消
      added: false
    }))
    aiFailed.value = Array.isArray(data?.failed) ? data.failed : []
    aiStep.value = 'result'
  } catch (error) {
    showError(error)
  } finally {
    aiParsing.value = false
  }
}

/** 回到第一步并清空输入(「继续添加」) */
function backToAiCourseInput() {
  aiStep.value = 'input'
  aiRawText.value = ''
}

/** 第二步:逐条保存勾选的课程(一门课有几天就发几条 POST /api/course) */
async function handleAiConfirmAdd() {
  const selected = aiCourses.value.filter((course) => course.checked)
  if (selected.length === 0) {
    ElMessage.warning('请至少勾选一门课程')
    return
  }

  aiSaving.value = true
  let courseSuccess = 0
  let courseFailed = 0

  for (const course of selected) {
    const days = Array.isArray(course.daysOfWeek) ? course.daysOfWeek : []
    if (days.length === 0) {
      courseFailed += 1
      continue
    }

    let allDaysOk = true
    for (const day of days) {
      try {
        await createCourse({
          courseName: course.courseName,
          dayOfWeek: day,
          startTime: course.startTime,
          endTime: course.endTime
        })
      } catch {
        // 单天失败不让整批中断,最后按「部分失败」提示
        allDaysOk = false
      }
    }

    if (allDaysOk) {
      course.added = true
      courseSuccess += 1
    } else {
      courseFailed += 1
    }
  }
  aiSaving.value = false

  if (courseSuccess > 0) {
    await loadCourses()
  }

  if (courseFailed === 0) {
    ElMessage.success(`已添加 ${courseSuccess} 门课程`)
    aiDialogVisible.value = false
    return
  }

  ElMessage.warning('部分课程添加失败')
  // 已经全部加成功的取消勾选,避免用户点重试时重复添加
  aiCourses.value.forEach((course) => {
    if (course.added) {
      course.checked = false
    }
  })
}

onMounted(loadCourses)
onMounted(loadRestTimes)

/**
 * 用 watch + immediate 而不是只挂 onMounted:
 * 从引导弹窗跳过来时页面可能已经挂载,只是 query 变了,onMounted 不会再跑。
 * 放在 script 末尾:applyTabFromQuery 会触发统计 / 记忆的懒加载,那些数据源得先声明好。
 */
watch(() => route.query.tab, applyTabFromQuery, { immediate: true })
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <!-- 课程表:本步实现 -->
        <el-tab-pane label="课程表" name="course">
          <!-- 原型:顶部 AI 提示条 -->
          <div class="ai-tip-banner">
            <el-icon><MagicStick /></el-icon>
            您可以直接告诉 AI 助手帮您添加或修改课表
          </div>

          <el-skeleton v-if="loading" :rows="6" animated />

          <!-- 一门课都没有:统一空状态(不再渲染空白的课表格子) -->
          <EmptyState
            v-else-if="courses.length === 0"
            icon="📅"
            text="还没有课程"
            sub-text="手动添加或让 AI 帮你加"
            action-text="新增课程"
            @action="openCreateDialog"
          />

          <template v-else>
            <!-- 整周网格课表:表头星期吸顶、左侧整点时间轴、课程块按分钟绝对定位 -->
            <div class="timetable-grid-container">
              <div class="timetable-header">
                <div class="timetable-header-corner"></div>
                <div class="timetable-header-days">
                  <div v-for="day in dayOptions" :key="day.value" class="timetable-header-day">
                    {{ day.label }}
                  </div>
                </div>
              </div>

              <div class="timetable-body" :style="{ height: `${gridHeight}px` }">
                <div class="timetable-time-col">
                  <div v-for="label in hourLabels" :key="label" class="timetable-time-label">
                    {{ label }}
                  </div>
                </div>

                <div class="timetable-grid-bg">
                  <div
                    v-for="course in courses"
                    :key="course.id"
                    class="course-block"
                    :style="courseBlockStyle(course)"
                    :title="`${course.courseName} ${formatTimeRange(course)}（点击编辑）`"
                    @click="openEditDialog(course)"
                  >
                    <button
                      class="course-delete-btn"
                      type="button"
                      title="删除课程"
                      @click.stop="handleDelete(course)"
                    >
                      ×
                    </button>
                    <div class="course-block-inner">{{ course.courseName }}</div>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <!-- 原型:按钮放在列表下方 -->
          <div class="tab-actions">
            <el-button type="primary" :icon="Plus" @click="openCreateDialog">新增课程</el-button>
            <el-button class="btn-magic" :icon="MagicStick" @click="openAiCourseDialog">
              AI 帮我加
            </el-button>
          </div>
        </el-tab-pane>

        <!-- 休息时间:本步实现 -->
        <el-tab-pane label="休息时间" name="rest">
          <div class="toolbar">
            <span class="section-title">休息时段</span>
          </div>

          <el-skeleton v-if="restLoading" :rows="3" animated />

          <EmptyState
            v-else-if="restTimes.length === 0"
            icon="☕"
            text="还没有休息时段"
            sub-text="给自己安排点放松时间"
            action-text="新增休息时段"
            @action="openRestCreateDialog"
          />

          <div v-else class="rest-list">
            <div v-for="restTime in restTimes" :key="restTime.id" class="rest-item">
              <div class="rest-info">
                <div class="rest-title">
                  <span>{{ formatRestRange(restTime) }}</span>
                  <el-tag v-if="restTime.label" type="warning" size="small" effect="plain">
                    {{ restTime.label }}
                  </el-tag>
                </div>
                <div class="rest-meta">生效范围：{{ restScopeLabel(restTime.dayOfWeek) }}</div>
              </div>

              <div class="rest-actions">
                <el-button size="small" @click="openRestEditDialog(restTime)">编辑</el-button>
                <el-button type="danger" size="small" plain @click="handleRestDelete(restTime)">
                  删除
                </el-button>
              </div>
            </div>
          </div>

          <!-- 原型:按钮放在列表下方 -->
          <div class="tab-actions">
            <el-button type="primary" @click="openRestCreateDialog">新增休息时段</el-button>
          </div>
        </el-tab-pane>

        <!-- 统计:本步实现 -->
        <el-tab-pane label="统计" name="stats">
          <el-skeleton v-if="statsLoading" :rows="5" animated />

          <template v-else>
            <div class="stats-cards">
              <el-card shadow="never" class="stats-card">
                <div class="icon-circle sun">
                  <el-icon><Sunny /></el-icon>
                </div>
                <div>
                  <div class="stats-label">今日完成度</div>
                  <div class="stats-value">
                    {{ todayStats.completed }}
                    <span class="stats-total">/{{ todayStats.total }}</span>
                  </div>
                  <div class="stats-percent">{{ todayStats.percent }}%</div>
                </div>
              </el-card>

              <el-card shadow="never" class="stats-card">
                <div class="icon-circle calendar">
                  <el-icon><Calendar /></el-icon>
                </div>
                <div>
                  <div class="stats-label">本周完成度</div>
                  <div class="stats-value">
                    {{ weekStats.completed }}
                    <span class="stats-total">/{{ weekStats.total }}</span>
                  </div>
                  <div class="stats-percent week">{{ weekStats.percent }}%</div>
                  <div v-if="weekRangeText" class="stats-range">{{ weekRangeText }}</div>
                </div>
              </el-card>
            </div>

            <el-card shadow="never" class="chart-card">
              <div class="chart-title">
                <el-icon class="chart-title-icon"><Histogram /></el-icon>
                近 7 天完成数
              </div>

              <div v-if="trendStats.length === 0" class="empty-tip">暂无数据</div>

              <div v-else class="bar-chart">
                <div v-for="item in trendStats" :key="item.date" class="bar-item">
                  <div class="bar-track">
                    <div class="bar-fill" :style="{ height: barHeight(item) }"></div>
                  </div>
                  <div class="bar-label">{{ shortDate(item.date) }}</div>
                  <div class="bar-value">{{ item.completed }}</div>
                </div>
              </div>
            </el-card>
          </template>
        </el-tab-pane>

        <!-- AI 对我的了解:本步实现(原型没有这个 Tab,样式参考「休息时间」) -->
        <el-tab-pane label="AI 对我的了解" name="memory">
          <div class="toolbar">
            <span class="section-title">
              <el-icon><MagicStick /></el-icon>
              AI 对我的了解
            </span>
            <el-button type="primary" :icon="Plus" @click="openMemoryDialog">手动添加</el-button>
          </div>

          <el-skeleton v-if="memoryLoading" :rows="4" animated />

          <!-- 空状态:一条记忆都没有 -->
          <EmptyState
            v-else-if="memories.length === 0"
            icon="🧠"
            text="AI 还不太了解你"
            sub-text="多和它聊聊吧"
          />

          <!-- 按 category 分组,每组一张卡片;分组顺序见 MEMORY_CATEGORY_ORDER -->
          <div v-else>
            <el-card
              v-for="group in memoryGroups"
              :key="group.category"
              shadow="never"
              class="memory-card"
            >
              <div class="memory-group-title">
                <span
                  class="memory-dot"
                  :style="{ background: memoryColor(group.category).color }"
                ></span>
                <span>{{ group.label }}</span>
                <span
                  class="memory-count"
                  :style="{
                    background: memoryColor(group.category).bg,
                    color: memoryColor(group.category).color
                  }"
                >
                  {{ group.items.length }}
                </span>
              </div>

              <div class="memory-list">
                <div v-for="memory in group.items" :key="memory.id" class="memory-item">
                  <div class="memory-content">{{ memory.content }}</div>

                  <div class="memory-actions">
                    <el-button
                      type="danger"
                      size="small"
                      plain
                      @click="handleMemoryDelete(memory)"
                    >
                      删除
                    </el-button>
                  </div>
                </div>
              </div>
            </el-card>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新增 / 编辑弹窗(暂内联在本页面) -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增课程' : '编辑课程'"
      width="480px"
    >
      <el-form label-width="90px">
        <el-form-item label="课程名">
          <el-input v-model="form.courseName" class="full-width" placeholder="例如：高等数学" clearable />
        </el-form-item>

        <el-form-item label="周几">
          <el-checkbox-group v-model="form.days" class="day-group">
            <el-checkbox v-for="day in dayOptions" :key="day.value" :value="day.value">
              {{ day.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item label="开始时间">
          <div class="time-picker">
            <select v-model="courseStartHour" class="time-select">
              <option v-for="hour in HOUR_OPTIONS" :key="hour" :value="hour">{{ hour }}</option>
            </select>
            <span class="time-sep">:</span>
            <select v-model="courseStartMinute" class="time-select">
              <option v-for="minute in MINUTE_OPTIONS" :key="minute" :value="minute">
                {{ minute }}
              </option>
            </select>
          </div>
        </el-form-item>

        <el-form-item label="结束时间">
          <div class="time-picker">
            <select v-model="courseEndHour" class="time-select">
              <option v-for="hour in HOUR_OPTIONS" :key="hour" :value="hour">{{ hour }}</option>
            </select>
            <span class="time-sep">:</span>
            <select v-model="courseEndMinute" class="time-select">
              <option v-for="minute in MINUTE_OPTIONS" :key="minute" :value="minute">
                {{ minute }}
              </option>
            </select>
          </div>
        </el-form-item>

        <el-form-item label="地点">
          <el-input v-model="form.location" class="full-width" placeholder="例如：A101（可不填）" clearable />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">
          {{ editingId === null ? '新增' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 休息时段弹窗(暂内联在本页面;原型用的 prompt 已换成 el-dialog) -->
    <el-dialog
      v-model="restDialogVisible"
      :title="restEditingId === null ? '新增休息时段' : '编辑休息时段'"
      width="480px"
    >
      <el-form label-width="90px">
        <el-form-item label="生效范围">
          <el-radio-group v-model="restForm.dayOfWeek">
            <el-radio v-for="scope in restScopeOptions" :key="scope.value" :value="scope.value">
              {{ scope.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="开始时间">
          <div class="time-picker">
            <select v-model="restStartHour" class="time-select">
              <option v-for="hour in HOUR_OPTIONS" :key="hour" :value="hour">{{ hour }}</option>
            </select>
            <span class="time-sep">:</span>
            <select v-model="restStartMinute" class="time-select">
              <option v-for="minute in MINUTE_OPTIONS" :key="minute" :value="minute">
                {{ minute }}
              </option>
            </select>
          </div>
        </el-form-item>

        <el-form-item label="结束时间">
          <div class="time-picker">
            <select v-model="restEndHour" class="time-select">
              <option v-for="hour in HOUR_OPTIONS" :key="hour" :value="hour">{{ hour }}</option>
            </select>
            <span class="time-sep">:</span>
            <select v-model="restEndMinute" class="time-select">
              <option v-for="minute in MINUTE_OPTIONS" :key="minute" :value="minute">
                {{ minute }}
              </option>
            </select>
          </div>
        </el-form-item>

        <el-form-item label="标签">
          <el-input
            v-model="restForm.label"
            class="full-width"
            placeholder="例如：课间休息（可不填）"
            clearable
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="restDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="restSaving" @click="handleRestSubmit">
          {{ restEditingId === null ? '新增' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- AI 帮我加课程弹窗(暂内联在本页面):第一步粘贴课程文本,第二步勾选确认 -->
    <el-dialog
      v-model="aiDialogVisible"
      title="AI 帮我加课程"
      width="620px"
      @closed="resetAiCourseDialog"
    >
      <!-- 第一步:输入课程文本 -->
      <div v-if="aiStep === 'input'">
        <el-form label-position="top">
          <el-form-item label="请告诉我您要添加的课程或完整的课程表">
            <el-input
              v-model="aiRawText"
              type="textarea"
              :rows="5"
              resize="none"
              :placeholder="AI_RAW_PLACEHOLDER"
            />
          </el-form-item>
        </el-form>
        <div class="ai-hint">支持多行输入，每行一条课程</div>
      </div>

      <!-- 第二步:预览解析结果 -->
      <div v-else>
        <div v-if="aiCourses.length === 0" class="empty-tip">
          没能识别出课程，请按「每周X X:XX-X:XX 课程名」的格式重新输入
        </div>

        <div v-else class="ai-course-list">
          <div v-for="(course, index) in aiCourses" :key="index" class="ai-course-item">
            <el-checkbox v-model="course.checked" class="ai-course-check" />
            <div class="ai-course-info">
              <div class="ai-course-name">{{ course.courseName }}</div>
              <div class="ai-course-meta">
                {{ toHHmm(course.startTime) }}-{{ toHHmm(course.endTime) }} ·
                {{ formatDaysOfWeek(course.daysOfWeek) }}
              </div>
            </div>
          </div>
        </div>

        <div v-if="aiFailed.length > 0" class="ai-failed">
          以下内容未能识别：{{ aiFailed.join('；') }}
        </div>
      </div>

      <template #footer>
        <template v-if="aiStep === 'input'">
          <el-button @click="aiDialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="aiParsing"
            :disabled="aiParsing"
            @click="handleAiParse"
          >
            提交
          </el-button>
        </template>

        <template v-else>
          <el-button :disabled="aiSaving" @click="backToAiCourseInput">继续添加</el-button>
          <el-button
            type="primary"
            :loading="aiSaving"
            :disabled="aiSaving || aiCourses.length === 0"
            @click="handleAiConfirmAdd"
          >
            确认添加
          </el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 手动添加记忆弹窗(暂内联在本页面) -->
    <el-dialog v-model="memoryDialogVisible" title="手动添加" width="480px">
      <el-form label-width="90px">
        <el-form-item label="类别">
          <el-select v-model="memoryForm.category" class="full-width">
            <el-option
              v-for="option in MEMORY_CATEGORY_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="内容">
          <el-input
            v-model="memoryForm.content"
            type="textarea"
            :rows="4"
            resize="none"
            :maxlength="MEMORY_CONTENT_MAX"
            show-word-limit
            placeholder="例如：用户不喜欢超过 1 小时的任务"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="memoryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="memorySaving" @click="handleMemorySubmit">
          添加
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f4fa;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.875rem;
  font-weight: 600;
  color: #334155;
}

.empty-tip {
  padding: 32px 0;
  text-align: center;
  font-size: 14px;
  color: #94a3b8;
}

/* ==================== 整周网格课表(照原型 .timetable-* 实现) ==================== */
.timetable-grid-container {
  height: 600px;
  overflow-y: auto;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  background: #fff;
  margin-bottom: 16px;
}

.timetable-header {
  display: flex;
  position: sticky;
  top: 0;
  z-index: 10;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
}

/* 表头左侧空出时间轴的宽度 */
.timetable-header-corner {
  width: 60px;
  flex-shrink: 0;
}

.timetable-header-days {
  display: flex;
  flex: 1;
}

.timetable-header-day {
  flex: 1;
  padding: 12px 0;
  font-size: 0.8rem;
  font-weight: 600;
  text-align: center;
  color: #64748b;
  border-right: 1px solid #f1f5f9;
}

.timetable-header-day:last-child {
  border-right: none;
}

.timetable-body {
  display: flex;
  position: relative;
}

.timetable-time-col {
  width: 60px;
  flex-shrink: 0;
  position: relative;
  z-index: 2;
  background: #fff;
  border-right: 1px solid #e2e8f0;
}

.timetable-time-label {
  height: 60px;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 4px;
  box-sizing: border-box;
  font-size: 0.7rem;
  color: #94a3b8;
}

/* 每小时一条浅分隔线 */
.timetable-grid-bg {
  flex: 1;
  position: relative;
  background-image: repeating-linear-gradient(
    to bottom,
    transparent,
    transparent 59px,
    #f8fafc 59px,
    #f8fafc 60px
  );
}

.course-block {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 6px 4px;
  border-radius: 8px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.02);
  box-sizing: border-box;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.course-block:hover {
  transform: scale(1.02);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  z-index: 5;
}

/* 课程名竖排(原型同款,像纸质课表) */
.course-block-inner {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1.3;
  text-align: center;
  word-break: break-all;
  letter-spacing: 1px;
  writing-mode: vertical-rl;
  text-orientation: upright;
}

/* 课程块右上角的删除按钮,hover 才出现 */
.course-delete-btn {
  position: absolute;
  top: 2px;
  right: 2px;
  z-index: 10;
  width: 16px;
  height: 16px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: #ef4444;
  background: rgba(255, 255, 255, 0.9);
  border: none;
  border-radius: 50%;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.course-block:hover .course-delete-btn {
  opacity: 1;
}

.course-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.full-width {
  width: 100%;
}

.day-group {
  display: flex;
  flex-wrap: wrap;
  gap: 0 16px;
}

/* 休息时段列表:与课程列表同款行布局,这里单独定义,避免改动课程表 Tab 的样式 */
.rest-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid #f1f5f9;
  border-radius: 14px;
  transition: background 0.2s ease;
}

.rest-item:hover {
  background: #fafbff;
}

.rest-item:last-child {
  border-bottom: none;
}

.rest-info {
  flex: 1;
  min-width: 180px;
}

.rest-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.rest-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #a8abb2;
}

.rest-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.rest-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 统计 Tab:两张完成度卡片 + 手写柱状图 */
.stats-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.stats-card,
.chart-card {
  margin: 0;
}

/* 原型:统计卡片内容 = 图标底衬 + 文本块,横向排布 */
.stats-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stats-label {
  font-size: 13px;
  color: #64748b;
}

.stats-value {
  margin-top: 2px;
  font-size: 1.5rem;
  font-weight: 700;
  line-height: 1.2;
  color: #1e293b;
}

.stats-total {
  font-size: 15px;
  font-weight: 400;
  color: #a8abb2;
}

.stats-percent {
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  color: #6b7ac9;
}

/* 本周完成度用原型里的暖色 */
.stats-percent.week {
  color: #c28b3a;
}

.stats-range {
  margin-top: 4px;
  font-size: 12px;
  color: #a8abb2;
}

.chart-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.chart-title-icon {
  color: #a5b4fc;
}

/* 柱状图:手写 div,沿用原型的 .bar-chart / .bar-item / .bar-fill 结构 */
.bar-chart {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.bar-item {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
}

/* 柱子的高度参照物:高度确定,里面的百分比高度才会生效 */
.bar-track {
  display: flex;
  align-items: flex-end;
  width: 100%;
  height: 160px;
}

.bar-fill {
  width: 100%;
  height: 0;
  border-radius: 8px 8px 4px 4px;
  /* 原型 .bar-fill 的紫色渐变 */
  background: linear-gradient(180deg, #b8c5fd 0%, #d9c7f0 100%);
  box-shadow: 0 4px 10px rgba(165, 180, 252, 0.5);
  transition: height 0.6s cubic-bezier(0.4, 0, 0.2, 1);
}

.bar-label {
  margin-top: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
}

.bar-value {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

/* 原型:列表下方的按钮行 */
.tab-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}

.tab-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 原型:课表上方的 AI 提示条 */
.ai-tip-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding: 12px;
  font-size: 12px;
  color: #6b7ac9;
  background: #eef2ff;
  border: 1px solid #dfe6ff;
  border-radius: 12px;
}

/* AI 帮我加课程弹窗 */
.ai-hint {
  margin-top: -8px;
  font-size: 12px;
  color: #a8abb2;
}

.ai-course-list {
  max-height: 280px;
  overflow-y: auto;
}

.ai-course-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f2f5;
}

.ai-course-item:last-child {
  border-bottom: none;
}

.ai-course-check {
  margin-top: 1px;
  /* 去掉 el-checkbox 默认的 margin-right,避免和课程名之间留一大段空白 */
  margin-right: 0;
}

.ai-course-info {
  flex: 1;
  min-width: 0;
}

.ai-course-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.ai-course-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #a8abb2;
}

.ai-failed {
  margin-top: 12px;
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: #f56c6c;
  background: #fef0f0;
  border: 1px solid #fde2e2;
  border-radius: 4px;
  word-break: break-all;
}

/* ==================== AI 对我的了解 Tab(原型没有,样式参考「休息时间」) ==================== */
/* 每个分组一张卡片 */
.memory-card {
  margin: 0 0 16px;
}

.memory-card:last-child {
  margin-bottom: 0;
}

/* 分组标题:颜色标记 + 中文名称 + 条数胶囊 */
.memory-group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.memory-dot {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
  border-radius: 50%;
}

.memory-count {
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 999px;
}

/* 记忆行:间距 / 圆角 / hover 与「休息时间」的 .rest-item 完全一致(单独写一份,互不影响) */
.memory-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid #f1f5f9;
  border-radius: 14px;
  transition: background 0.2s ease;
}

.memory-item:hover {
  background: #fafbff;
}

.memory-item:last-child {
  border-bottom: none;
}

.memory-content {
  flex: 1;
  min-width: 220px;
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
  word-break: break-word;
  white-space: pre-wrap;
}

.memory-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.memory-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>

