<script setup>
import { computed } from 'vue'

/**
 * 统一空状态:大号图标 + 主文案 + 可选副文案 + 可选操作按钮。
 * icon 支持两种写法:
 *   1) emoji / 文本,例如 icon="🎯";
 *   2) Element Plus 图标组件,例如 :icon="Sunny"(调用方自己 import 后传组件)。
 * 传了 actionText 就渲染一个主按钮(道奇蓝),点击时 emit('action')。
 */
const props = defineProps({
  /** emoji 文本或 Element Plus 图标组件 */
  icon: { type: [String, Object, Function], default: '' },
  /** 主文案 */
  text: { type: String, default: '' },
  /** 副文案(可选) */
  subText: { type: String, default: '' },
  /** 按钮文案(可选,不传就不显示按钮) */
  actionText: { type: String, default: '' }
})

const emit = defineEmits(['action'])

/** icon 不是字符串,说明传进来的是 Element Plus 图标组件 */
const iconIsComponent = computed(() => Boolean(props.icon) && typeof props.icon !== 'string')

/** 点操作按钮:把事件抛给使用方,组件本身不认识业务 */
function handleAction() {
  emit('action')
}
</script>

<template>
  <div class="empty-state">
    <div v-if="icon" class="empty-icon">
      <el-icon v-if="iconIsComponent" :size="26">
        <component :is="icon" />
      </el-icon>
      <span v-else class="empty-emoji">{{ icon }}</span>
    </div>

    <div v-if="text" class="empty-text">{{ text }}</div>
    <div v-if="subText" class="empty-sub-text">{{ subText }}</div>

    <el-button v-if="actionText" type="primary" class="empty-action" @click="handleAction">
      {{ actionText }}
    </el-button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 36px 16px;
  text-align: center;
}

/* 图标底衬:与全局 .icon-circle 同一套中性配色 */
.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  margin-bottom: 6px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--hover);
  color: var(--text-secondary);
}

.empty-emoji {
  font-size: 28px;
  line-height: 1;
}

.empty-text {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.empty-sub-text {
  font-size: 13px;
  color: var(--text-muted);
}

.empty-action {
  margin-top: 10px;
}
</style>
