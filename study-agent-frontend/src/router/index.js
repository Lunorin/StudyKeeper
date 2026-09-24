import { createRouter, createWebHistory } from 'vue-router'
import { TOKEN_KEY } from '../api/request'

const routes = [
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', hideNavBar: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/RegisterView.vue'),
    meta: { title: '注册', hideNavBar: true }
  },
  {
    path: '/forgot-password',
    name: 'forgotPassword',
    component: () => import('../views/ForgotPasswordView.vue'),
    meta: { title: '找回密码', hideNavBar: true }
  },
  {
    path: '/today',
    name: 'today',
    component: () => import('../views/TodayView.vue'),
    meta: { title: '今日任务' }
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('../views/TasksView.vue'),
    meta: { title: '任务管理' }
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '聊天' }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../views/SettingsView.vue'),
    meta: { title: '设置' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 免登录白名单:登录 / 注册 / 找回密码这三页都不需要 token
 * (它们都用 meta.hideNavBar 标记,App.vue 里据此决定不套卡片、不显示导航栏)
 */
const PUBLIC_PATHS = ['/login', '/register', '/forgot-password']

/**
 * 路由守卫:白名单页面直接放行,其余路由本地没有 token 时一律跳回登录页。
 * 直接读 localStorage,不引入 Pinia / Vuex。
 */
router.beforeEach((to, from, next) => {
  // 目标路由在白名单里,直接放行
  if (PUBLIC_PATHS.includes(to.path)) {
    next()
    return
  }

  // 有 token:放行;没有:回登录页
  if (localStorage.getItem(TOKEN_KEY)) {
    next()
    return
  }

  next('/login')
})

export default router
