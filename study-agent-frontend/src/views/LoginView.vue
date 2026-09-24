<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Right, School, User } from '@element-plus/icons-vue'
import { login } from '../api/auth'
import { TOKEN_KEY, USER_INFO_KEY } from '../api/request'
import { loadProfile } from '../stores/user'
import { showError } from '../utils/error'

const router = useRouter()
const route = useRoute()

/** 提交中(按钮禁用) */
const loading = ref(false)

/**
 * 登录表单:账号(邮箱或用户名)+ 密码。
 * 邮箱注册 / 找回密码成功后会带着 ?account= 跳回登录页,这里直接帮用户填好。
 */
const form = reactive({
  account: typeof route.query.account === 'string' ? route.query.account : '',
  password: ''
})

/** 只挡重复提交:空表单仍然可点,点了才能收到「请输入邮箱或用户名」这类提示 */
const submitDisabled = computed(() => loading.value)

/** 登录成功后,把 token 与基础用户信息存到 localStorage(导航栏先按它渲染,随后 loadProfile 再补全) */
function saveLoginState(data) {
  localStorage.setItem(TOKEN_KEY, data.token)
  localStorage.setItem(
    USER_INFO_KEY,
    JSON.stringify({
      // 接口文档写的是 userId,后端实际返回的是 id,两个都兼容
      id: data.userId ?? data.id,
      username: data.username,
      nickname: data.nickname
    })
  )
}

async function handleSubmit() {
  const account = form.account.trim()
  const password = form.password

  if (!account) {
    ElMessage.warning('请输入邮箱或用户名')
    return
  }
  if (!password) {
    ElMessage.warning('请输入密码')
    return
  }

  loading.value = true
  try {
    const data = await login({ account, password })
    saveLoginState(data)
    ElMessage.success('登录成功')

    // 登录成功后拉一次完整资料(头像 / 昵称 / 手机号等)进 store,导航栏直接用它渲染。
    // 拉失败不影响登录:导航栏会退化成默认头像,而且它自己挂载时还会再补拉一次。
    try {
      await loadProfile()
    } catch (error) {
      console.warn('加载用户资料失败', error)
    }

    router.push('/today')
  } catch (error) {
    // 账号或密码错误(1002)这类业务文案、网络 / 服务端异常统一交给 showError 兜底
    showError(error)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-box">
      <!-- 原型:道奇蓝方块图标 + 标题 + 副标题 -->
      <div class="icon-circle lg auth-icon">
        <el-icon><School /></el-icon>
      </div>

      <h1 class="auth-title text-gradient">学习监督</h1>
      <p class="auth-subtitle">开始高效学习的一天</p>

      <el-form class="auth-form" label-position="top" @submit.prevent>
        <el-form-item>
          <template #label>
            <span class="auth-label">
              <el-icon><User /></el-icon>
              账号
            </span>
          </template>
          <el-input
            v-model="form.account"
            size="large"
            placeholder="邮箱或用户名"
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
            placeholder="密码"
            show-password
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
      </el-form>

      <el-button
        class="auth-button"
        type="primary"
        size="large"
        :loading="loading"
        :disabled="submitDisabled"
        @click="handleSubmit"
      >
        <el-icon><Right /></el-icon>
        {{ loading ? '登录中...' : '登录' }}
      </el-button>

      <p class="auth-links center">
        <router-link class="auth-link" to="/forgot-password">忘记密码？</router-link>
      </p>

      <p class="auth-links center">
        还没有账号？
        <router-link class="auth-link" to="/register">立即注册</router-link>
      </p>
    </div>
  </div>
</template>
