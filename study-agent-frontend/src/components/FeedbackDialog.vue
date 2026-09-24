<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitFeedback } from '../api/user'
import { showError } from '../utils/error'

/** 弹窗显隐由父组件用 v-model:visible 控制(模板里直接用 visible) */
defineProps({
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible'])

/** 内容长度上限 */
const CONTENT_MAX = 5000

/** 反馈内容(取消不清空,方便接着改;提交成功才清) */
const content = ref('')
/** 提交中 */
const submitting = ref(false)

/** 关闭弹窗(取消 / 右上角 × / 点遮罩都走这里) */
function close() {
  emit('update:visible', false)
}

/** 提交:POST /api/user/feedback */
async function handleSubmit() {
  const text = content.value.trim()
  if (!text) {
    ElMessage.warning('请输入反馈内容')
    return
  }

  submitting.value = true
  try {
    await submitFeedback({ content: text })
    ElMessage.success('感谢您的反馈')
    content.value = ''
    close()
  } catch (error) {
    // 业务错误带的是能直接给用户看的中文文案,优先展示;其余交给 showError
    if (typeof error?.code === 'number' && error.message) {
      ElMessage.error(error.message)
    } else {
      showError(error)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="意见反馈"
    width="520px"
    @update:model-value="(value) => emit('update:visible', value)"
  >
    <!-- 字数统计由 el-input 的 show-word-limit 渲染在右下角 -->
    <el-input
      v-model="content"
      type="textarea"
      :rows="7"
      resize="none"
      :maxlength="CONTENT_MAX"
      show-word-limit
      placeholder="哪里不好用、想要什么功能，都可以告诉我们"
    />

    <template #footer>
      <el-button :disabled="submitting" @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">提交</el-button>
    </template>
  </el-dialog>
</template>
