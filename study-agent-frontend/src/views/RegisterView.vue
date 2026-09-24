<script setup>
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Message, Right, School, Timer, User } from '@element-plus/icons-vue'
import { register, sendEmailCode } from '../api/auth'
import PasswordRules from '../components/PasswordRules.vue'
import { showError } from '../utils/error'
import { isValidCode, isValidEmail, isValidPassword } from '../utils/validate'

const router = useRouter()

/** 验证码倒计时秒数:与后端「同一邮箱 60 秒内不能重复发」保持一致 */
const COUNTDOWN_SECONDS = 60

/** 提交中(注册按钮) */
const submitting = ref(false)
/** 发码中(获取验证码按钮) */
const sendingCode = ref(false)
/** 倒计时剩余秒数,> 0 时发码按钮禁用 */
const countdown = ref(0)
/** 倒计时定时器句柄,组件卸载时要清掉 */
let countdownTimer = null

/** 注册表单:邮箱 + 验证码 + 密码 + 昵称(可选) */
const form = reactive({ email: '', code: '', password: '', nickname: '' })

/** 发码按钮文案:倒计时中显示「60s 后重试」 */
const codeButtonText = computed(() =>
  countdown.value > 0 ? `${countdown.value}s 后重试` : '获取验证码'
)

/** 发码按钮禁用:倒计时中 / 正在请求 */
const codeButtonDisabled = computed(() => countdown.value > 0 || sendingCode.value)

/** 密码是否通过强度校验(与后端同款规则,见 utils/validate.js) */
const passwordValid = computed(() => isValidPassword(form.password))

/**
 * 提交按钮禁用条件:密码「填了但不合格」时禁用。
 * 密码为空不禁用,这样点提交还能收到「请输入密码」的提示(否则按钮点不动、提示又不显示,用户会莫名其妙)。
 */
const submitDisabled = computed(() => submitting.value || (!!form.password && !passwordValid.value))

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
 * 邮箱框回车:发码按钮可用且还没填验证码时,视为「获取验证码」;
 * 其余情况(已在倒计时中 / 已经填了验证码)按「注册」提交处理 ——
 * 免得用户在邮箱框里敲回车又打一次发码接口,收到「发送太频繁」的报错。
 */
function handleEmailEnter() {
  if (!codeButtonDisabled.value && !form.code) {
    handleSendCode()
    return
  }
  handleSubmit()
}

/** 获取验证码:先做邮箱非空 + 格式校验,再调后端发码,成功后进入 60 秒倒计时 */
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
    await sendEmailCode({ email })
    ElMessage.success('验证码已发送,请到邮箱查收')
    startCountdown()
  } catch (error) {
    showError(error)
  } finally {
    sendingCode.value = false
  }
}

/** 注册:校验 → 提交 → 回登录页手动登录(注册接口不返回 token,不替用户自动登录更清楚) */
async function handleSubmit() {
  const email = form.email.trim()
  const code = form.code.trim()
  const password = form.password
  const nickname = form.nickname.trim()

  if (!email) {
    ElMessage.warning('请输入邮箱')
    return
  }
  if (!isValidEmail(email)) {
    ElMessage.warning('邮箱格式不正确')
    return
  }
  if (!code) {
    ElMessage.warning('请输入验证码')
    return
  }
  if (!isValidCode(code)) {
    ElMessage.warning('验证码是 6 位数字')
    return
  }
  if (!password) {
    ElMessage.warning('请输入密码')
    return
  }
  // 回车提交 / 按钮点击都会走到这里:再挡一次密码强度(按钮禁用挡不住 Enter)
  if (!passwordValid.value) {
    ElMessage.warning('密码不符合要求')
    return
  }

  submitting.value = true
  try {
    // nickname 为空时不传,由后端自动取邮箱 @ 前面那一段
    await register({ email, code, password, nickname: nickname || undefined })
    ElMessage.success('注册成功,请用邮箱登录')
    // 用 replace:注册页不留在历史里;带上邮箱,登录页直接把账号填好
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

      <h1 class="auth-title text-gradient">创建账号</h1>
      <p class="auth-subtitle">用邮箱注册,收一条验证码就行</p>

      <el-form class="auth-form" label-position="top" @submit.prevent>
        <!-- 邮箱 + 右侧「获取验证码」 -->
        <el-form-item>
          <template #label>
            <span class="auth-label">
              <el-icon><Message /></el-icon>
              邮箱
            </span>
          </template>
          <div class="code-row">
            <el-input
              v-model="form.email"
              size="large"
              placeholder="you@example.com"
              clearable
              @keyup.enter="handleEmailEnter"
            />
            <el-button
              size="large"
              :loading="sendingCode"
              :disabled="codeButtonDisabled"
              @click="handleSendCode"
            >
              {{ codeButtonText }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item>
          <template #label>
            <span class="auth-label">
              <el-icon><Timer /></el-icon>
              验证码
            </span>
          </template>
          <el-input
            v-model="form.code"
            size="large"
            placeholder="邮箱里的 6 位数字"
            maxlength="6"
            clearable
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item>
          <template #label>
            <span class="auth-label">
              <el-icon><Lock /></el-icon>
              密码
            </span>
          </template>
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            placeholder="8-20 位,含大小写字母和数字"
            show-password
            @keyup.enter="handleSubmit"
          />

          <!-- 密码强度:实时勾选 4 条要求 -->
          <PasswordRules :password="form.password" />
        </el-form-item>

        <el-form-item>
          <template #label>
            <span class="auth-label">
              <el-icon><User /></el-icon>
              昵称（可选）
            </span>
          </template>
          <el-input
            v-model="form.nickname"
            size="large"
            placeholder="不填默认用邮箱前缀"
            clearable
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
      </el-form>

      <el-button
        class="auth-button"
        type="primary"
        size="large"
        :loading="submitting"
        :disabled="submitDisabled"
        @click="handleSubmit"
      >
        <el-icon><Right /></el-icon>
        {{ submitting ? '注册中...' : '注册' }}
      </el-button>

      <p class="auth-links center">
        已有账号？
        <router-link class="auth-link" to="/login">去登录</router-link>
      </p>
    </div>
  </div>
</template>
