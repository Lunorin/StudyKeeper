<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateNotifications } from '../api/user'
import { patchUserInfo, userInfo } from '../stores/user'
import { showError } from '../utils/error'

const props = defineProps({
  /** 弹窗显隐,父组件用 v-model:visible 控制 */
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible'])

/**
 * 4 个开关:store 里的字段名(key)和接口字段名(field)不一样,用这张表互相映射
 * (接口字段不带 notify 前缀)。
 */
const NOTIFY_ITEMS = [
  {
    key: 'notifyTaskReminder',
    field: 'taskReminder',
    label: '任务提醒',
    hint: '到点提醒你该做哪个任务'
  },
  { key: 'notifyCourseReminder', field: 'courseReminder', label: '课程提醒', hint: '上课前提醒你' },
  { key: 'notifyDailyReport', field: 'dailyReport', label: '每日学习报告', hint: '每天汇总当天的完成情况' },
  { key: 'notifySound', field: 'sound', label: '提示音', hint: '发提醒时带一声提示音' }
]

/** 表单直接用接口字段名,提交时不用再转换 */
const form = reactive({ taskReminder: true, courseReminder: true, dailyReport: true, sound: true })
/** 保存中 */
const saving = ref(false)

/** 打开弹窗时用 store 里的开关值预填表单 */
watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    NOTIFY_ITEMS.forEach((item) => {
      form[item.field] = Boolean(userInfo.value[item.key])
    })
  },
  { immediate: true }
)

/** 关闭弹窗(取消 / 右上角 × / 点遮罩都走这里) */
function close() {
  emit('update:visible', false)
}

/** 保存:PUT /api/user/notifications */
async function handleSave() {
  saving.value = true
  try {
    const data = await updateNotifications({ ...form })

    // 接口字段名 -> store 字段名;后端返回了就用返回的,没返回就用表单值兜底
    const partial = {}
    NOTIFY_ITEMS.forEach((item) => {
      const value = data?.[item.field]
      partial[item.key] = typeof value === 'boolean' ? value : form[item.field]
    })
    patchUserInfo(partial)

    ElMessage.success('已保存')
    close()
  } catch (error) {
    // 业务错误带的是能直接给用户看的中文文案,优先展示;其余交给 showError
    if (typeof error?.code === 'number' && error.message) {
      ElMessage.error(error.message)
    } else {
      showError(error)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="通知设置"
    width="480px"
    @update:model-value="(value) => emit('update:visible', value)"
  >
    <div class="notify-list">
      <div v-for="item in NOTIFY_ITEMS" :key="item.key" class="notify-row">
        <div class="notify-text">
          <div class="notify-label">{{ item.label }}</div>
          <div class="notify-hint">{{ item.hint }}</div>
        </div>

        <el-switch v-model="form[item.field]" />
      </div>
    </div>

    <template #footer>
      <el-button :disabled="saving" @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 一行一个开关:左边文案、右边开关,整行浅色边框 */
.notify-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
  border: 1px solid #eef2f8;
  border-radius: 14px;
  transition: background 0.2s ease;
}

.notify-row:last-child {
  margin-bottom: 0;
}

.notify-row:hover {
  background: #fafbff;
}

.notify-text {
  min-width: 0;
}

.notify-label {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.notify-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #a8abb2;
}
</style>
