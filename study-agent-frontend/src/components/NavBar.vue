<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Bell,
  ChatDotRound,
  Collection,
  Iphone,
  List,
  Setting,
  SwitchButton,
  User
} from '@element-plus/icons-vue'
import { logout } from '../api/auth'
import { TOKEN_KEY, clearAuthStorage } from '../api/request'
import ProfileDialog from './ProfileDialog.vue'
import BindDialog from './BindDialog.vue'
import NotifyDialog from './NotifyDialog.vue'
import FeedbackDialog from './FeedbackDialog.vue'
import { DEFAULT_AVATAR, clearUserInfo, isImageAvatar, loadProfile, userInfo } from '../stores/user'

const router = useRouter()

/**
 * 导航项:path 直接用路由地址,交给 router-link 渲染,
 * 选中态用 vue-router 自动加的 router-link-active 类(当前路由表是平级的,不会误匹配)
 */
const menus = [
  { path: '/today', label: '今日任务', icon: List },
  { path: '/tasks', label: '任务管理', icon: Collection },
  { path: '/chat', label: '聊天', icon: ChatDotRound },
  { path: '/settings', label: '设置', icon: Setting }
]

/* ==================== 用户头像 + 下拉菜单 ==================== */

/** 下拉菜单显隐 */
const menuVisible = ref(false)
/** 编辑资料弹窗显隐 */
const profileVisible = ref(false)
/** 绑定手机号 / 邮箱弹窗显隐 */
const bindVisible = ref(false)
/** 通知设置弹窗显隐 */
const notifyVisible = ref(false)
/** 意见反馈弹窗显隐 */
const feedbackVisible = ref(false)
/** 菜单容器:用来判断点击是否落在菜单外面 */
const menuContainerRef = ref(null)

/** 头像内容:没设置时用默认 emoji;base64 图片交给 <img> 渲染 */
const avatarText = computed(() => userInfo.value.avatar || DEFAULT_AVATAR)
/** 鼠标悬停提示:昵称优先,其次用户名 */
const avatarTitle = computed(
  () => userInfo.value.nickname || userInfo.value.username || '未设置昵称'
)

/** 点击菜单外面关掉下拉(原型同款行为) */
function handleDocumentClick(event) {
  if (menuContainerRef.value?.contains(event.target)) return
  menuVisible.value = false
}

onMounted(() => {
  document.addEventListener('click', handleDocumentClick)

  // 刷新页面时 store 是空的,这里补拉一次资料。
  // 放在 NavBar 里而不是 App.vue:导航栏只在登录后的页面挂载,
  // 而且 store 里已经有资料(比如刚登录过)就不再重复请求。
  if (localStorage.getItem(TOKEN_KEY) && !userInfo.value.id) {
    loadProfile().catch((error) => {
      // 拉不到不影响页面使用(401 已由 request.js 拦截器处理)
      console.warn('加载用户资料失败', error)
    })
  }
})

onUnmounted(() => {
  document.removeEventListener('click', handleDocumentClick)
})

/** 打开「编辑资料」弹窗 */
function handleEditProfile() {
  menuVisible.value = false
  profileVisible.value = true
}

/** 打开「绑定手机号/邮箱」弹窗 */
function handleBind() {
  menuVisible.value = false
  bindVisible.value = true
}

/** 打开「通知设置」弹窗 */
function handleNotify() {
  menuVisible.value = false
  notifyVisible.value = true
}

/** 打开「意见反馈」弹窗 */
function handleFeedback() {
  menuVisible.value = false
  feedbackVisible.value = true
}

/** 退出:先调接口(不管成功失败),再清本地登录态与用户资料并回登录页 */
async function handleLogout() {
  menuVisible.value = false
  try {
    await logout()
  } catch {
    // JWT 是无状态的,接口失败(例如 token 已失效返回 401)也不影响退出
  } finally {
    clearAuthStorage()
    clearUserInfo()
    ElMessage.success('已退出')
    router.push('/login')
  }
}
</script>

<template>
  <nav class="navbar">
    <div class="navbar-brand">StudyKeeper</div>

    <div class="navbar-menu">
      <router-link v-for="item in menus" :key="item.path" :to="item.path" class="nav-item">
        <el-icon class="nav-icon">
          <component :is="item.icon" />
        </el-icon>
        <span>{{ item.label }}</span>
      </router-link>
    </div>

    <div class="navbar-right">
      <!-- 头像按钮 + 下拉菜单(原型:圆形头像,菜单浮在头像右下方) -->
      <div ref="menuContainerRef" class="user-menu">
        <button
          class="avatar-btn"
          type="button"
          :title="avatarTitle"
          @click="menuVisible = !menuVisible"
        >
          <img v-if="isImageAvatar(avatarText)" :src="avatarText" alt="头像" class="avatar-img" />
          <span v-else class="avatar-emoji">{{ avatarText }}</span>
        </button>

        <div v-if="menuVisible" class="user-dropdown">
          <button class="user-menu-item" type="button" @click="handleEditProfile">
            <el-icon class="user-menu-icon"><User /></el-icon>
            <span>编辑资料</span>
          </button>

          <button class="user-menu-item" type="button" @click="handleBind">
            <el-icon class="user-menu-icon"><Iphone /></el-icon>
            <span>绑定手机号/邮箱</span>
          </button>

          <button class="user-menu-item" type="button" @click="handleNotify">
            <el-icon class="user-menu-icon"><Bell /></el-icon>
            <span>通知设置</span>
          </button>

          <button class="user-menu-item" type="button" @click="handleFeedback">
            <el-icon class="user-menu-icon"><ChatDotRound /></el-icon>
            <span>意见反馈</span>
          </button>

          <div class="user-menu-divider"></div>

          <button class="user-menu-item user-menu-danger" type="button" @click="handleLogout">
            <el-icon><SwitchButton /></el-icon>
            <span>退出登录</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 编辑资料弹窗(保存后 store 里的资料会更新,头像自动跟着变) -->
    <ProfileDialog v-model:visible="profileVisible" />
    <!-- 绑定手机号 / 邮箱 -->
    <BindDialog v-model:visible="bindVisible" />
    <!-- 通知设置 -->
    <NotifyDialog v-model:visible="notifyVisible" />
    <!-- 意见反馈 -->
    <FeedbackDialog v-model:visible="feedbackVisible" />
  </nav>
</template>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  gap: 16px;
  /* 卡片本身已有内边距,这里只留上下的呼吸空间,底部一条浅分隔线(原型同款) */
  padding: 4px 0 16px;
  border-bottom: 1px solid #f1f5f9;
}

.navbar-brand {
  font-size: 0.95rem;
  font-weight: 600;
  color: #6b7ac9;
}

/* 4 个胶囊之间的间距 */
.navbar-menu {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 12px;
  color: #64748b;
  font-size: 0.9rem;
  font-weight: 500;
  text-decoration: none;
  transition: all 0.2s ease;
}

.nav-item:hover {
  background: #f1f5f9;
  color: #334155;
}

/* 选中态:vue-router 自动加的类 */
.nav-item.router-link-active {
  background: #e8ebfd;
  color: #4e5590;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(168, 179, 247, 0.4);
}

.nav-item.router-link-active:hover {
  background: #dfe3fd;
  color: #3e467a;
}

.nav-icon {
  font-size: 16px;
}

.navbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

/* ==================== 头像 + 下拉菜单(原型 .user-menu-item 同款) ==================== */

/* 相对定位:菜单浮层相对它绝对定位到右下方 */
.user-menu {
  position: relative;
}

.avatar-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 50%;
  cursor: pointer;
  transition: all 0.2s ease;
}

.avatar-btn:hover {
  border-color: #cbd5e1;
  box-shadow: 0 6px 16px -6px rgba(140, 160, 210, 0.5);
}

.avatar-emoji {
  font-size: 1.1rem;
  line-height: 1;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 下拉浮层:绝对定位到头像右下方 */
.user-dropdown {
  position: absolute;
  top: 48px;
  right: 0;
  z-index: 50;
  width: 210px;
  padding: 8px;
  background: #fff;
  border: 1px solid #eef2f8;
  border-radius: 16px;
  box-shadow: 0 10px 40px -10px rgba(120, 140, 220, 0.35);
}

.user-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  font-family: inherit;
  font-size: 0.875rem;
  font-weight: 500;
  color: #334155;
  text-align: left;
  background: transparent;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.user-menu-item:hover {
  background: #f1f5f9;
}

/* 普通菜单项的图标用主题紫 */
.user-menu-icon {
  width: 18px;
  font-size: 16px;
  color: #6b7ac9;
}

.user-menu-divider {
  height: 1px;
  margin: 6px 8px;
  background: #f1f5f9;
}

/* 危险项:退出登录(颜色随 currentColor 生效) */
.user-menu-item.user-menu-danger {
  color: #e11d48;
}

.user-menu-item.user-menu-danger:hover {
  background: #fef2f2;
}
</style>


