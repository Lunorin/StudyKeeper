<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { completeOnboarding } from '../api/user'
import { patchUserInfo } from '../stores/user'
import { showError } from '../utils/error'

/**
 * 新用户引导弹窗(4 步)。
 * 由 App.vue 在「已登录 + 未完成引导 + 不在认证页」时弹出来,用 v-model:visible 控制显隐。
 * 每一步 / 每次跳过 / 点遮罩关闭都会 fire-and-forget 调一次 POST /user/onboarding-complete,
 * 后端是幂等的,所以重复调用无所谓(引导只记「完成 / 未完成」,不记走到第几步)。
 */
const props = defineProps({
  /** 弹窗显隐,父组件用 v-model:visible 控制 */
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'completed'])

const router = useRouter()

/**
 * 4 步内容:
 * title / desc 是文案,primary 是主按钮,skip 是「跳过」(没有就不显示),
 * action 是要跳转的目标(带 query),emoji 是标题上方的小图标。
 */
const STEPS = [
  {
    key: 'welcome',
    emoji: '👋',
    title: '欢迎来到学习监督',
    desc: '我帮你规划每天的学习任务，让你不再拖延',
    primary: '开始设置'
  },
  {
    key: 'course',
    emoji: '📅',
    title: '先设置你的课表',
    desc: '花 1 分钟填一下课表，AI 才知道你什么时候有空',
    primary: '去填课表',
    skip: '跳过',
    // 设置页支持 ?tab=课程表 直接定位到课程表页签
    action: { path: '/settings', query: { tab: '课程表' } }
  },
  {
    key: 'ai',
    emoji: '🤖',
    title: '用 AI 排今天的任务',
    desc: '说说今天想做什么，AI 帮你拆成具体任务',
    primary: '去试试',
    skip: '跳过',
    // 今日页支持 ?aiArrange=1 自动打开「AI 帮我排」弹窗
    action: { path: '/today', query: { aiArrange: '1' } }
  },
  {
    key: 'done',
    emoji: '✅',
    title: '完成任务后点完成',
    desc: '完成任务后点一下「完成」，就能看到进度啦',
    primary: '知道了'
  }
]

/** 当前第几步(下标 0 开始) */
const stepIndex = ref(0)
/** 当前一步的文案与按钮 */
const currentStep = computed(() => STEPS[stepIndex.value])
/** 是不是最后一步 */
const isLastStep = computed(() => stepIndex.value === STEPS.length - 1)

/** 每次打开都从第 1 步开始(关掉再打开不该停在上次那一步) */
watch(
  () => props.visible,
  (visible) => {
    if (visible) stepIndex.value = 0
  }
)

/**
 * 标记引导完成:fire-and-forget —— 不 await、不挡 UI。
 * 本地先把 store 置成已完成(本次会话里不再弹);接口失败才提示一句 ——
 * 那种情况下后端没记住,下次刷新还会再弹一次(接口幂等,重试即可)。
 */
function markOnboarded() {
  patchUserInfo({ onboarded: true })
  completeOnboarding().catch((error) => {
    console.warn('标记新用户引导完成失败', error)
    showError(error)
  })
}

/** 收起弹窗(点按钮 / 点遮罩 / 按 Esc / 点右上角 × 都走这里):通知父组件 + 广播 completed */
function close() {
  emit('update:visible', false)
  emit('completed')
}

/** 弹窗自己想关时(点遮罩 / Esc / ×)也算「完成」,以后不再弹 */
function handleVisibleChange(value) {
  if (value) {
    emit('update:visible', true)
    return
  }

  markOnboarded()
  close()
}

/** 主按钮:第 1 步进下一步;第 2 / 3 步关掉引导并跳到对应页面;第 4 步结束引导 */
function handlePrimary() {
  const action = currentStep.value.action
  markOnboarded()

  if (action) {
    // 先关弹窗再跳转,免得弹窗盖在目标页面上
    close()
    router.push(action)
    return
  }

  if (isLastStep.value) {
    close()
    return
  }

  stepIndex.value += 1
}

/** 跳过:同样算「完成」(不再弹),但让用户继续看完后面的步骤 */
function handleSkip() {
  markOnboarded()

  if (isLastStep.value) {
    close()
    return
  }

  stepIndex.value += 1
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    width="460px"
    align-center
    class="onboarding-dialog"
    @update:model-value="handleVisibleChange"
  >
    <div class="onboarding-body">
      <!-- 进度:4 个圆点,已走过的浅紫、当前步实心紫色 -->
      <div class="onboarding-dots">
        <span
          v-for="(step, index) in STEPS"
          :key="step.key"
          class="onboarding-dot"
          :class="{ 'is-active': index === stepIndex, 'is-done': index < stepIndex }"
        ></span>
      </div>

      <div class="onboarding-emoji">{{ currentStep.emoji }}</div>
      <h2 class="onboarding-title">{{ currentStep.title }}</h2>
      <p class="onboarding-desc">{{ currentStep.desc }}</p>
    </div>

    <template #footer>
      <el-button v-if="currentStep.skip" @click="handleSkip">{{ currentStep.skip }}</el-button>
      <el-button type="primary" @click="handlePrimary">{{ currentStep.primary }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.onboarding-body {
  padding: 4px 8px 8px;
  text-align: center;
}

/* 进度圆点 */
.onboarding-dots {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 20px;
}

.onboarding-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #e2e8f0;
  transition: all 0.2s ease;
}

.onboarding-dot.is-done {
  background: #c0cbee;
}

.onboarding-dot.is-active {
  background: #6b7ac9;
  box-shadow: 0 0 0 4px rgba(107, 122, 201, 0.15);
}

/* 标题上方的小图标:沿用原型 .icon-circle.lg 的圆角尺寸 + 紫色渐变 */
.onboarding-emoji {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  margin: 0 auto 16px;
  border-radius: 20px;
  font-size: 1.6rem;
  background: linear-gradient(135deg, #e8ebfd 0%, #f3e8ff 100%);
}

.onboarding-title {
  margin: 0 0 8px;
  font-size: 1.25rem;
  font-weight: 700;
  color: #1e293b;
}

.onboarding-desc {
  margin: 0;
  font-size: 0.875rem;
  line-height: 1.6;
  color: #64748b;
}
</style>
