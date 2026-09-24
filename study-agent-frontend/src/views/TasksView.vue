<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock } from '@element-plus/icons-vue'
import EmptyState from '../components/EmptyState.vue'
import {
  createTemplate,
  deleteTemplate,
  listTemplates,
  updateTemplate
} from '../api/template'
import { showError } from '../utils/error'

/** 星期数字 -> 中文,下标 0 对应 1(周一) */
const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日']

/** 重复日复选框选项 */
const repeatOptions = WEEK_LABELS.map((label, index) => ({ value: index + 1, label: `周${label}` }))

/** 优先级下拉选项,value 用接口文档的枚举值 high / medium / low */
const priorityOptions = [
  { value: 'high', label: '高' },
  { value: 'medium', label: '中' },
  { value: 'low', label: '低' }
]

/** 优先级 -> 标签颜色 */
const PRIORITY_TAG_TYPE = { high: 'danger', medium: 'warning', low: 'info' }

/** 列表加载中 */
const loading = ref(false)
/** 弹窗保存中 */
const saving = ref(false)
/** 长期任务列表 */
const templates = ref([])

/** 弹窗显隐 */
const dialogVisible = ref(false)
/** 正在编辑的长期任务 id,null 表示新增 */
const editingId = ref(null)
/** 弹窗表单:标题 + 时长(分钟) + 优先级 + 重复日 */
const form = reactive({ title: '', duration: 30, priority: 'medium', repeatDays: [] })

/** [1,3,5] -> 周一/三/五 */
function formatRepeatDays(days) {
  if (!Array.isArray(days) || days.length === 0) return '不重复'
  const labels = [...days]
    .sort((a, b) => a - b)
    .map((day) => WEEK_LABELS[day - 1])
    .filter(Boolean)
  return labels.length === 0 ? '不重复' : `周${labels.join('/')}`
}

/** 时长(分钟) -> 30分钟 / 1小时 / 1小时30分钟 */
function minutesToText(minutes) {
  if (!minutes || minutes <= 0) return '时长待定'
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest}分钟`
  if (rest === 0) return `${hours}小时`
  return `${hours}小时${rest}分钟`
}

function priorityLabel(value) {
  return priorityOptions.find((item) => item.value === value)?.label || value
}

function priorityTagType(value) {
  return PRIORITY_TAG_TYPE[value] || 'info'
}

/** 拉取长期任务列表 */
async function loadTemplates() {
  loading.value = true
  try {
    const data = await listTemplates()
    templates.value = Array.isArray(data) ? data : []
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

/** 打开新增弹窗 */
function openCreateDialog() {
  editingId.value = null
  form.title = ''
  form.duration = 30
  form.priority = 'medium'
  form.repeatDays = []
  dialogVisible.value = true
}

/** 打开编辑弹窗,复用同一个弹窗并回填 */
function openEditDialog(template) {
  editingId.value = template.id
  form.title = template.title || ''
  form.duration = template.duration ?? 30
  form.priority = template.priority || 'medium'
  form.repeatDays = Array.isArray(template.repeatDays) ? [...template.repeatDays] : []
  dialogVisible.value = true
}

/** 弹窗提交:新增走 createTemplate,编辑走 updateTemplate */
async function handleSubmit() {
  const title = form.title.trim()
  if (!title) {
    ElMessage.warning('请填写标题')
    return
  }

  const duration = Number(form.duration)
  if (!Number.isInteger(duration) || duration <= 0) {
    ElMessage.warning('请填写正确的时长(分钟)')
    return
  }

  if (form.repeatDays.length === 0) {
    ElMessage.warning('请至少选择一个重复日')
    return
  }

  saving.value = true
  try {
    const payload = {
      title,
      duration,
      priority: form.priority,
      repeatDays: [...form.repeatDays].sort((a, b) => a - b)
    }

    if (editingId.value === null) {
      await createTemplate(payload)
      ElMessage.success('新增成功')
    } else {
      await updateTemplate(editingId.value, payload)
      ElMessage.success('保存成功')
    }

    dialogVisible.value = false
    await loadTemplates()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

/** 删除:先 confirm,确认后调接口并刷新列表 */
async function handleDelete(template) {
  try {
    await ElMessageBox.confirm(`确定删除长期任务「${template.title}」吗?`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    // 用户取消,不做任何处理
    return
  }

  try {
    await deleteTemplate(template.id)
    ElMessage.success('已删除')
    await loadTemplates()
  } catch (error) {
    showError(error)
  }
}

onMounted(loadTemplates)
</script>

<template>
  <div class="page">
    <!-- 原型:标题 + 主按钮在卡片外面,卡片里只放列表 -->
    <div class="list-head">
      <h2 class="page-title">长期任务</h2>
      <el-button type="primary" @click="openCreateDialog">新增长期任务</el-button>
    </div>

    <el-card shadow="never">
      <el-skeleton v-if="loading" :rows="4" animated />

      <EmptyState
        v-else-if="templates.length === 0"
        icon="📌"
        text="还没有长期任务"
        sub-text="添加一个每天要做的吧"
        action-text="新增长期任务"
        @action="openCreateDialog"
      />

      <div v-else class="template-list">
        <div v-for="template in templates" :key="template.id" class="template-item">
          <div class="template-info">
            <div class="template-title">{{ template.title }}</div>
            <div class="template-meta">
              <el-icon><Clock /></el-icon>
              {{ minutesToText(template.duration) }} · 重复：{{ formatRepeatDays(template.repeatDays) }}
            </div>
          </div>

          <div class="template-actions">
            <el-tag :type="priorityTagType(template.priority)" size="small">
              {{ priorityLabel(template.priority) }}
            </el-tag>
            <el-button size="small" @click="openEditDialog(template)">编辑</el-button>
            <el-button type="danger" size="small" plain @click="handleDelete(template)">
              删除
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 新增 / 编辑弹窗(暂内联在本页面) -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增长期任务' : '编辑长期任务'"
      width="460px"
    >
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="form.title" class="full-width" placeholder="例如：每日背单词" clearable />
        </el-form-item>

        <el-form-item label="时长">
          <el-input v-model="form.duration" class="full-width" type="number" min="1" placeholder="例如：30">
            <template #suffix>分钟</template>
          </el-input>
        </el-form-item>

        <el-form-item label="优先级">
          <el-select v-model="form.priority" class="full-width" placeholder="请选择优先级">
            <el-option
              v-for="item in priorityOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="重复日">
          <el-checkbox-group v-model="form.repeatDays" class="repeat-group">
            <el-checkbox v-for="day in repeatOptions" :key="day.value" :value="day.value">
              {{ day.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">
          {{ editingId === null ? '新增' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 原型:区块标题 + 主按钮一行,放在卡片外面 */
.list-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.template-item {
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

.template-item:hover {
  background: var(--bg);
}

.template-item:last-child {
  border-bottom: none;
}

.template-info {
  flex: 1;
  min-width: 180px;
}

.template-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.template-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.template-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.template-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.full-width {
  width: 100%;
}

.repeat-group {
  display: flex;
  flex-wrap: wrap;
  gap: 0 16px;
}
</style>

