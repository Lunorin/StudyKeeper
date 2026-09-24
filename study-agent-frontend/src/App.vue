<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import NavBar from './components/NavBar.vue'
import OnboardingDialog from './components/OnboardingDialog.vue'
import { TOKEN_KEY } from './api/request'
import { getOnboardingStatus } from './api/user'
import { patchUserInfo, userInfo } from './stores/user'

const route = useRoute()

/**
 * 认证页(登录 / 注册 / 找回密码)按原型是独立页面:不套居中卡片,用 .app-container--plain 撑满视口。
 * 这三条路由都用 meta.hideNavBar 标记,这里直接复用它判断。
 */
const isPlainPage = computed(() => !!route.meta.hideNavBar)

/* ==================== 新用户引导 ==================== */

/** 引导弹窗显隐 */
const showOnboarding = ref(false)
/** 本次会话是否已经弹过:点遮罩关掉后不再反复冒出来 */
const onboardingShown = ref(false)

/**
 * 补一次引导状态查询。
 * 登录响应与 GET /user/profile 都带 onboarded,只有「刷新页面时资料还没拉到 / 资料接口失败」才需要它。
 * 拉不到就当已完成:引导是锦上添花,不能因为一次查询失败把用户拦在弹窗上。
 */
async function syncOnboardingStatus() {
  try {
    const data = await getOnboardingStatus()
    patchUserInfo({ onboarded: data?.onboarded === true })
  } catch (error) {
    console.warn('查询新用户引导状态失败', error)
    patchUserInfo({ onboarded: true })
  }
}

/** 判断此刻要不要弹引导(首次挂载、登录后进应用页、从认证页切过来都会走这里) */
async function maybeShowOnboarding() {
  // 未登录 / 认证页(登录、注册、找回密码)都不弹
  if (!localStorage.getItem(TOKEN_KEY) || route.meta.hideNavBar) return
  // 本次会话已经弹过就不再弹
  if (onboardingShown.value) return

  // store 里还不是布尔值(null = 还没拉到)时补一次查询
  if (typeof userInfo.value.onboarded !== 'boolean') {
    await syncOnboardingStatus()
  }

  // 只有明确「未完成」才弹
  if (userInfo.value.onboarded !== false) return

  onboardingShown.value = true
  showOnboarding.value = true
}

/** 引导结束(走完 4 步 / 跳过 / 点遮罩)—— 完成状态由 OnboardingDialog 自己标记 */
function handleOnboardingCompleted() {
  showOnboarding.value = false
}

onMounted(maybeShowOnboarding)

/**
 * 路由变化时重新判断:
 * 登录成功跳 /today 后要弹;退出登录(本地已无 token)时复位标记,换个新账号登录还能正常弹。
 */
watch(
  () => route.path,
  () => {
    if (!localStorage.getItem(TOKEN_KEY)) {
      onboardingShown.value = false
      return
    }
    maybeShowOnboarding()
  }
)
</script>

<template>
  <!-- 非认证页套居中圆角卡片;登录 / 注册 / 找回密码只用 .app-container--plain 撑满视口 -->
  <div class="app-layout" :class="isPlainPage ? 'app-container--plain' : 'app-container'">
    <!-- 认证页通过 meta.hideNavBar 隐藏顶部导航 -->
    <NavBar v-if="!route.meta.hideNavBar" />

    <main class="app-main">
      <router-view />
    </main>

    <!-- 新用户引导:已登录 + 未完成引导 + 不在认证页时才弹(见 maybeShowOnboarding) -->
    <OnboardingDialog v-model:visible="showOnboarding" @completed="handleOnboardingCompleted" />
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
}

.app-main {
  flex: 1;
  overflow: auto;
}
</style>

