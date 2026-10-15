<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deleteAccount } from '../api/user'
import { clearAuthStorage } from '../api/request'
import { clearUserInfo } from '../stores/user'
import { showError } from '../utils/error'

const props = defineProps({
  /** 第 1 步弹窗(警告说明)的显隐,父组件用 v-model:visible 控制 */
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible'])

const router = useRouter()

/**
 * 「注销」这两个字:第 2 步的确认框必须原样打出来才允许提交。
 * 常量放这里,提示文案与校验逻辑共用一份,不会两边写歪。
 */
const DELETE_CONFIRM_TEXT = '注销'

/** 当前步骤:1 = 警告说明,2 = 身份验证(0 = 整个流程已关闭) */
const step = ref(0)
/** DELETE /api/user/account 请求中 */
const submitting = ref(false)
/** 表单:登录密码 + 确认文字 */
const form = reactive({ password: '', confirm: '' })

/** 「永久注销」按钮的启用条件:密码非空且不少于 6 位,确认文字恰好等于「注销」 */
const canSubmit = computed(
  () =>
    form.password.length > 0 &&
    form.password.length >= 6 &&
    form.confirm === DELETE_CONFIRM_TEXT
)

/** 清空密码 / 确认文字:密码不留在内存里 */
function resetForm() {
  form.password = ''
  form.confirm = ''
}

/** 打开时从第 1 步开始,并且是一个干净的空表单 */
watch(
  () => props.visible,
  (visible) => {
    resetForm()
    if (!visible) {
      step.value = 0
      return
    }
    submitting.value = false
    step.value = 1
  },
  { immediate: true }
)

/** 关掉整个流程(取消 / 返回 / 右上角 × / 点遮罩都走这里) */
function close() {
  step.value = 0
  resetForm()
  emit('update:visible', false)
}

/** 第 1 步「我确定，继续」:只切到验证弹窗,**不调接口** */
function goToVerify() {
  step.value = 2
}

/**
 * 第 1 步弹窗被关掉。
 * 注意:点「我确定，继续」时 step 会切到 2,而 el-dialog 在关闭动画结束后还会补发一次
 * update:modelValue(false)(见 element-plus 的 afterLeave),那种情况不是「取消」,要跳过。
 */
function handleWarningUpdate(value) {
  if (value || step.value === 2) return
  close()
}

/** 第 2 步弹窗被关掉(X / 点遮罩 / Esc):整个流程结束 */
function handleVerifyUpdate(value) {
  if (value) return
  close()
}

/**
 * 是不是「密码错」。
 * 后端注销接口密码不对时返回 400 + message「密码错误」(docs/api.md §12.7);
 * 这里同时认 1002(账号或密码错误)与任何带「密码」字样的文案,后端文案微调也不会漏掉这种提示。
 */
function isPasswordError(error) {
  if (error?.code === 1002) return true
  return typeof error?.message === 'string' && error.message.includes('密码')
}

/**
 * 第 2 步「永久注销」:DELETE /api/user/account。
 * 成功 → 清本地登录态(token + store 里的资料)并回登录页;
 * 失败 → 留在弹窗里,用户可以直接改密码重试(密码错给更直白的文案)。
 */
async function handleSubmit() {
  // 条件不满足 / 正在请求时不重复提交
  if (!canSubmit.value || submitting.value) return

  submitting.value = true
  try {
    await deleteAccount({ password: form.password })

    // 先关弹窗再清状态,避免弹窗还开着的时候页面已经跳走
    close()
    clearAuthStorage()
    clearUserInfo()

    ElMessage.success('账号已注销')
    router.push('/login')
  } catch (error) {
    if (isPasswordError(error)) {
      ElMessage.error('密码错误')
    } else {
      showError(error)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <!-- 第 1 步:警告说明(400px;只讲清楚后果,这一步不调接口) -->
  <el-dialog
    :model-value="step === 1"
    title="确定要注销账号吗？"
    width="400px"
    @update:model-value="handleWarningUpdate"
  >
    <div class="delete-warning-box">
      <div class="delete-warning-head">此操作将永久删除你的所有数据：</div>
      <ul class="delete-warning-list">
        <li>所有任务和历史记录</li>
        <li>所有聊天记录和 AI 记忆</li>
        <li>所有课程表和休息时间</li>
        <li>所有学习统计</li>
      </ul>
      <div class="delete-warning-foot">数据删除后无法恢复。</div>
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="danger" plain @click="goToVerify">我确定，继续</el-button>
    </template>
  </el-dialog>

  <!-- 第 2 步:身份验证(460px;密码 + 打出「注销」,都通过才允许调 DELETE /api/user/account) -->
  <el-dialog
    :model-value="step === 2"
    title="验证身份"
    width="460px"
    @update:model-value="handleVerifyUpdate"
    @closed="resetForm"
  >
    <el-form label-position="top">
      <el-form-item label="登录密码">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          placeholder="输入你的登录密码"
        />
      </el-form-item>

      <el-form-item label="确认注销">
        <el-input
          v-model="form.confirm"
          :placeholder="`输入“${DELETE_CONFIRM_TEXT}”两个字确认`"
        />
      </el-form-item>
    </el-form>

    <div class="delete-hint">这两步是为了防止误操作</div>

    <template #footer>
      <el-button :disabled="submitting" @click="close">返回</el-button>
      <el-button
        type="danger"
        class="delete-submit"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="handleSubmit"
      >
        永久注销
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* ==================== 注销账号弹窗 ==================== */
/* 第 1 步的红色警告框(浅红底 + 浅红描边) */
.delete-warning-box {
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--danger);
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
}

.delete-warning-head {
  font-weight: 600;
}

/* 逐条列出会被删掉的数据 */
.delete-warning-list {
  margin: 8px 0;
  padding-left: 18px;
}

.delete-warning-list li {
  line-height: 1.9;
}

.delete-warning-foot {
  font-weight: 600;
}

/* 第 2 步底部的「防止误操作」小字提示 */
.delete-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/*
 * 「永久注销」按钮:红色实底(#DC2626)+ 白字。
 * 全局 style.css 里的 .el-button 把描边 / 文字统一改成了中性色,
 * 这里显式盖回来 —— scoped 会编译成 .delete-submit[data-v-xxx],优先级高于全局的 .el-button。
 */
.delete-submit {
  background: var(--danger);
  border-color: var(--danger);
  color: #ffffff;
}

.delete-submit:hover,
.delete-submit:focus {
  background: #b91c1c;
  border-color: #b91c1c;
  color: #ffffff;
}

/* 条件不满足(按钮置灰):浅灰底 + 灰色文字,和旁边可点的「返回」区分开 */
.delete-submit.is-disabled,
.delete-submit.is-disabled:hover,
.delete-submit.is-disabled:focus {
  background: var(--hover);
  border-color: var(--border);
  color: var(--text-muted);
}

/* 请求中仍旧保持红底,只让 loading 转圈(写在置灰规则之后,同优先级下覆盖它) */
.delete-submit.is-loading,
.delete-submit.is-loading:hover,
.delete-submit.is-loading:focus {
  background: var(--danger);
  border-color: var(--danger);
  color: #ffffff;
}
</style>


