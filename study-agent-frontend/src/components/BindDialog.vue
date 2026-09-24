<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateProfile } from '../api/user'
import { patchUserInfo, userInfo } from '../stores/user'
import { showError } from '../utils/error'

const props = defineProps({
  /** 弹窗显隐,父组件用 v-model:visible 控制 */
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'saved'])

/** 表单:手机号 + 邮箱 */
const form = reactive({ phone: '', email: '' })
/** 保存中 */
const saving = ref(false)

/** 打开弹窗时把 store 里的值预填进表单 */
watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    form.phone = userInfo.value.phone || ''
    form.email = userInfo.value.email || ''
  },
  { immediate: true }
)

/** 关闭弹窗(取消 / 右上角 × / 点遮罩都走这里) */
function close() {
  emit('update:visible', false)
}

/** 手机号:不填也允许,填了就必须是 11 位数字 */
function isValidPhone(phone) {
  return /^\d{11}$/.test(phone)
}

/** 邮箱:不填也允许,填了必须像邮箱(@ 后面还得有点) */
function isValidEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

/** 保存:PUT /api/user/profile,只动 phone / email */
async function handleSave() {
  const phone = form.phone.trim()
  const email = form.email.trim()

  if (phone && !isValidPhone(phone)) {
    ElMessage.warning('手机号请填 11 位数字')
    return
  }
  if (email && !isValidEmail(email)) {
    ElMessage.warning('邮箱格式不对，检查一下吧')
    return
  }

  saving.value = true
  try {
    // 传空串 = 清空该字段(后端约定:不传 / null 表示不修改,见 docs/api.md §12.2)
    const data = await updateProfile({ phone, email })
    // 后端没回完整对象时用表单值兜底,保证弹窗与 store 立刻一致
    patchUserInfo({ phone: data?.phone ?? phone, email: data?.email ?? email })
    ElMessage.success('已保存')
    emit('saved')
    close()
  } catch (error) {
    // 业务错误(如 400 参数不合法)后端带的是能直接给用户看的中文文案,优先展示
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
    title="绑定手机号/邮箱"
    width="480px"
    @update:model-value="(value) => emit('update:visible', value)"
  >
    <el-form label-width="90px">
      <el-form-item label="手机号">
        <el-input
          v-model="form.phone"
          class="full-width"
          maxlength="20"
          placeholder="11 位手机号（可不填）"
          clearable
        />
      </el-form-item>

      <el-form-item label="邮箱">
        <el-input
          v-model="form.email"
          class="full-width"
          maxlength="100"
          placeholder="例如 xiaoming@example.com（可不填）"
          clearable
        />
      </el-form-item>
    </el-form>

    <div class="dialog-hint">演示版：信息仅保存在本机数据库</div>

    <template #footer>
      <el-button :disabled="saving" @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full-width {
  width: 100%;
}

/* 提示条:与设置页的 AI 提示条同一套配色(原型:浅灰底 + 细描边) */
.dialog-hint {
  padding: 10px 12px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
}
</style>
