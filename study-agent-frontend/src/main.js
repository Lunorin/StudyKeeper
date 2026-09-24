import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(router)
app.use(ElementPlus)

// 等首次路由解析完成后再挂载:路由组件是懒加载的,首屏导航是异步的,
// 提前挂载会出现"当前路由还是初始空路由(meta 为空)"的瞬间,登录页会闪现导航栏
router.isReady().then(() => {
  app.mount('#app')
})
