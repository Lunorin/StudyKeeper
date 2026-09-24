<script setup>
import { computed } from 'vue'
import { arePasswordRulesAllOk, getPasswordRules, isValidPassword } from '../utils/validate'

/**
 * 密码强度实时提示:注册页与找回密码页共用(逻辑来自改造前登录页注册模式,规则一字未改)。
 * 用法:放在密码 el-form-item 里 —— <PasswordRules :password="form.password" />
 */
const props = defineProps({
  /** 当前输入的密码:为空时整块不渲染 */
  password: { type: String, default: '' }
})

/** 4 条要求:满足绿色打勾,不满足灰色圆圈 */
const rules = computed(() => getPasswordRules(props.password))

/** 4 条都满足但正则仍不过:说明用了字符集之外的符号,这里点出来 */
const hasUnsupportedChar = computed(
  () => arePasswordRulesAllOk(props.password) && !isValidPassword(props.password)
)
</script>

<template>
  <div v-if="password" class="pwd-rules">
    <div v-for="rule in rules" :key="rule.label" class="pwd-rule" :class="{ 'is-ok': rule.ok }">
      <span class="pwd-rule-mark">{{ rule.ok ? '✓' : '○' }}</span>
      <span>{{ rule.label }}</span>
    </div>

    <div v-if="hasUnsupportedChar" class="pwd-rule pwd-rule-extra">
      <span class="pwd-rule-mark">!</span>
      <span>只能用字母、数字和 @ $ ! % * ? &amp;</span>
    </div>
  </div>
</template>

<style scoped>
.pwd-rules {
  width: 100%;
  margin-top: 4px;
}

/* 一项一行:满足绿色打勾,不满足灰色圆圈 */
.pwd-rule {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-muted);
}

.pwd-rule:last-child {
  margin-bottom: 0;
}

.pwd-rule.is-ok {
  color: var(--success);
}

.pwd-rule-mark {
  width: 14px;
  text-align: center;
  font-weight: 700;
}

/* 4 条全绿但仍不合法(用了不支持的字符)时的提醒 */
.pwd-rule-extra {
  color: var(--danger);
}
</style>
