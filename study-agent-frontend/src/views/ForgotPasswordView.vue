<script setup>
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Key, Lock, Message, Promotion, School, Timer } from '@element-plus/icons-vue'
import { resetPassword, sendForgotCode } from '../api/auth'
import PasswordRules from '../components/PasswordRules.vue'
import { showError } from '../utils/error'
import { isValidCode, isValidEmail, isValidPassword } from '../utils/validate'

const router = useRouter()

/** 倒计时秒数:与后端「同一邮箱 60 秒内不能重复发」保持一致 */
const COUNTDOWN_SECONDS = 60

/** 当前步骤:1 填邮箱发码 / 2 填验证码 + 新密码 */
const step = ref(1)
/** 发码中(发送 / 重新发送验证码) */
const sendingCode = ref(false)
/** 提交中(重置密码按钮) */
const submitting = ref(false)
/** 倒计时剩余秒数,> 0 时发码按钮禁用 */
const countdown = ref(0)
/** 倒计时定时器句柄,组件卸载时要清掉 */
let countdownTimer = null

/** 表单:邮箱在第 1 步填,验证码 / 新密码在第 2 步填 */
const form = reactive({ email: '', code: '', newPassword: '' })

/** 第 1 步的发码按钮文案 */
const sendButtonText = computed(() =>
  countdown.value > 0 ? `${countdown.value}s 后重试` : '发送验证码'
)
/** 第 2 步的重新发送按钮文案(与第 1 步共用同一个倒计时) */
const resendButtonText = computed(() =>
  countdown.value > 0 ? `${countdown.value}s 后重试` : '重新发送'
)
/** 发码按钮禁用:倒计时中 / 正在请求 */
const codeButtonDisabled = computed(() => countdown.value > 0 || sendingCode.value)

/** 新密码是否通过强度校验(与后端同款规则,见 utils/validate.js) */
const newPasswordValid = computed(() => isValidPassword(form.newPassword))

/**
 * 重置按钮禁用条件:新密码「填了但不合格」时禁用。
 * 新密码为空不禁用,这样点提交还能收到「请输入新密码」的提示。
 */
const submitDisabled = computed(
  () => submitting.value || (!!form.newPassword && !newPasswordValid.value)
)

/** 开始 60 秒倒计时 */
function startCountdown() {
  countdown.value = COUNTDOWN_SECONDS
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      stopCountdown()
    }
  }, 1000)
}

/** 停掉倒计时并复位(倒计时结束 / 组件卸载时调用,避免定时器泄漏) */
function stopCountdown() {
  if (countdownTimer) {
    window.clearInterval(countdownTimer)
    countdownTimer = null
  }
  countdown.value = 0
}

onUnmounted(stopCountdown)

/**
 * 发送验证码:第 1 步成功后进入第 2 步;第 2 步的「重新发送」复用这一个方法。
 * 后端要求邮箱已注册,没注册过会返回 400「该邮箱未注册」。
 */
async function handleSendCode() {
  const email = form.email.trim()

  if (!email) {
    ElMessage.warning('请输入邮箱')
    return
  }
  if (!isValidEmail(email)) {
    ElMessage.warning('邮箱格式不正确')
    return
  }

  sendingCode.value = true
  try {
    await sendForgotCode({ email })
    ElMessage.success('验证码已发送,请到邮箱查收')
    step.value = 2
    startCountdown()
  } catch (error) {
    showError(error)
  } finally {
    sendingCode.value = false
  }
}

/** 回第 1 步换个邮箱:清掉第 2 步填过的内容,重新发一次码即可 */
function backToEmail() {
  stopCountdown()
  form.code = ''
  form.newPassword = ''
  step.value = 1
}

/** 重置密码:校验 → 提交 → 回登录页(新密码立即生效,顺手把账号填好) */
async function handleReset() {
  const email = form.email.trim()
  const code = form.code.trim()
  const newPassword = form.newPassword

  if (!code) {
    ElMessage.warning('请输入验证码')
    return
  }
  if (!isValidCode(code)) {
    ElMessage.warning('验证码是 6 位数字')
    return
  }
  if (!newPassword) {
    ElMessage.warning('请输入新密码')
    return
  }
  // 回车提交 / 按钮点击都会走到这里:再挡一次密码强度(按钮禁用挡不住 Enter)
  if (!newPasswordValid.value) {
    ElMessage.warning('密码不符合要求')
    return
  }

  submitting.value = true
  try {
    await resetPassword({ email, code, newPassword })
    ElMessage.success('密码已重置,请用新密码登录')
    // 用 replace:重置页不留在历史里(它上面的验证码已经用掉了)
    router.replace({ path: '/login', query: { account: email } })
  } catch (error) {
    showError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-box">
      <div class="icon-circle lg auth-icon">
        <el-icon><School /></el-icon>
      </div>

      <h1 class="auth-title text-gradient">找回密码</h1>
      <p class="auth-subtitle">用注册邮箱收验证码,再设个新密码</p>

      <!-- 两步指示 -->
      <div class="auth-steps">
        <span class="auth-step" :class="{ 'is-active': step === 1 }">1 验证邮箱</span>
        <span class="auth-step-arrow">→</span>
        <span class="auth-step" :class="{ 'is-active': step === 2 }">2 重置密码</span>
      </div>

      <!-- 第 1 步:填邮箱,点发送验证码即进入第 2 步 -->
      <template v-if="step === 1">
        <el-form class="auth-form" label-position="top" @submit.prevent>
          <el-form-item>
            <template #label>
              <span class="auth-label">
                <el-icon><Message /></el-icon>
                注册邮箱
              </span>
            </template>
            <el-input
              v-model="form.email"
              size="large"
              placeholder="you@example.com"
              clearable
              @keyup.enter="handleSendCode"
            />
          </el-form-item>
        </el-form>

        <el-button
          class="auth-button"
          type="primary"
          size="large"
          :loading="sendingCode"
          :disabled="codeButtonDisabled"
          @click="handleSendCode"
        >
          <el-icon><Promotion /></el-icon>
          {{ sendingCode ? '发送中...' : sendButtonText }}
        </el-button>
      </template>

      <!-- 第 2 步:验证码 + 新密码 -->
      <template v-else>
        <p class="auth-email-chip">
          <el-icon><Message /></el-icon>
          {{ form.email }}
        </p>

        <el-form class="auth-form" label-position="top" @submit.prevent>
          <el-form-item>
            <template #label>
              <span class="auth-label">
                <el-icon><Timer /></el-icon>
                验证码
              </span>
            </template>
            <div class="code-row">
              <el-input
                v-model="form.code"
                size="large"
                placeholder="邮箱里的 6 位数字"
                maxlength="6"
                clearable
                @keyup.enter="handleReset"
              />
              <el-button
                size="large"
                :loading="sendingCode"
                :disabled="codeButtonDisabled"
                @click="handleSendCode"
              >
                {{ resendButtonText }}
              </el-button>
            </div>
          </el-form-item>

          <el-form-item>
            <template #label>
              <span class="auth-label">
                <el-icon><Lock /></el-icon>
                新密码
              </span>
            </template>
            <el-input
              v-model="form.newPassword"
              size="large"
              type="password"
              placeholder="8-20 位,含大小写字母和数字"
              show-password
              @keyup.enter="handleReset"
            />

            <!-- 密码强度:实时勾选 4 条要求 -->
            <PasswordRules :password="form.newPassword" />
          </el-form-item>
        </el-form>

        <el-button
          class="auth-button"
          type="primary"
          size="large"
          :loading="submitting"
          :disabled="submitDisabled"
          @click="handleReset"
        >
          <el-icon><Key /></el-icon>
          {{ submitting ? '提交中...' : '重置密码' }}
        </el-button>

        <p class="auth-links center">
          <span class="auth-link" @click="backToEmail">换一个邮箱</span>
        </p>
      </template>

      <p class="auth-links center">
        <router-link class="auth-link" to="/login">返回登录</router-link>
      </p>
    </div>
  </div>
</template>
