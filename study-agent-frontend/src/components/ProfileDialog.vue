<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { updateProfile } from '../api/user'
import { DEFAULT_AVATAR, isImageAvatar, setUserInfo, userInfo } from '../stores/user'
import { showError } from '../utils/error'

const props = defineProps({
  /** 弹窗显隐,父组件用 v-model:visible 控制 */
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'saved'])

/** 可选头像:原型里的 10 个 emoji */
const AVATAR_OPTIONS = ['🎓', '📚', '✏️', '🌟', '🚀', '🎯', '🎨', '🦊', '🐼', '🐧']

/** 昵称 / 用户名的长度上限(与原型一致) */
const NICKNAME_MAX = 20
const USERNAME_MAX = 30

/**
 * 体积口径(见 docs/api.md §12.2):
 * 后端只放行 avatar 字符串 ≤ 500KB(UTF-8 字节数),超出返回 400。
 * base64 字符串是 ASCII,字节数 = 字符串长度;它比原图大约 1/3,
 * 所以 400KB 的原图转出来就已经超了 —— 判断必须按 base64 的长度来。
 */
const AVATAR_MAX_BYTES = 500 * 1024
/** 原图超过这个大小就不必先看了,直接压 */
const COMPRESS_THRESHOLD = 500 * 1024
/** 压缩时长边最大像素(文档建议 400×400) */
const AVATAR_MAX_SIZE = 400
/** 兜底档位:400 还超(极少见)时再缩到这个尺寸 */
const AVATAR_FALLBACK_SIZE = 200

/** 表单:只编辑这三项(手机号 / 邮箱留到「绑定」那轮) */
const form = reactive({ nickname: '', username: '', avatar: DEFAULT_AVATAR })
/** 保存中 */
const saving = ref(false)
/** 隐藏的 file input,由「选择本地图片」按钮触发 */
const fileInputRef = ref(null)

/** 打开弹窗时把 store 里的资料预填进表单 */
watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    form.nickname = userInfo.value.nickname || ''
    form.username = userInfo.value.username || ''
    form.avatar = userInfo.value.avatar || DEFAULT_AVATAR
  },
  { immediate: true }
)

/** 关闭弹窗(取消 / 右上角 × / 点遮罩都走这里) */
function close() {
  emit('update:visible', false)
}

/** FileReader 读成 base64 data URL */
function readAsDataURL(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(reader.error || new Error('图片读取失败'))
    reader.readAsDataURL(file)
  })
}

/**
 * 用 canvas 等比缩放到最长边不超过 maxSize。
 * 目的是照片类大图:PNG 仍输出 PNG(保留透明),其它输出 JPEG。
 * 任何一步失败都退回原图,不阻塞用户选头像。
 */
function compressImage(dataUrl, maxSize) {
  return new Promise((resolve) => {
    const image = new Image()
    image.onload = () => {
      const scale = Math.min(1, maxSize / Math.max(image.width, image.height))
      if (scale >= 1) {
        resolve(dataUrl)
        return
      }

      const canvas = document.createElement('canvas')
      canvas.width = Math.round(image.width * scale)
      canvas.height = Math.round(image.height * scale)

      const ctx = canvas.getContext('2d')
      if (!ctx) {
        resolve(dataUrl)
        return
      }
      ctx.drawImage(image, 0, 0, canvas.width, canvas.height)

      const type = /^data:image\/png/i.test(dataUrl) ? 'image/png' : 'image/jpeg'
      resolve(canvas.toDataURL(type, 0.85))
    }
    image.onerror = () => resolve(dataUrl)
    image.src = dataUrl
  })
}

/**
 * 把选中的图片转成可以直接提交的 avatar 字符串。
 * 原图过大、或者转成 base64 后超过后端 500KB 的限制,都先压到 400×400 以内。
 */
async function buildAvatar(file) {
  const dataUrl = await readAsDataURL(file)
  if (file.size <= COMPRESS_THRESHOLD && dataUrl.length <= AVATAR_MAX_BYTES) {
    return dataUrl
  }

  const compressed = await compressImage(dataUrl, AVATAR_MAX_SIZE)
  if (compressed.length <= AVATAR_MAX_BYTES) {
    return compressed
  }

  // 400×400 仍然超(几乎不会出现),再缩一档兜底
  return compressImage(dataUrl, AVATAR_FALLBACK_SIZE)
}

/** 选本地图片 */
async function handleFileChange(event) {
  const file = event.target.files?.[0]
  // 清掉 value,否则下次选同一个文件不会再触发 change
  event.target.value = ''
  if (!file) return

  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }

  try {
    form.avatar = await buildAvatar(file)
  } catch (error) {
    console.warn('读取头像图片失败', error)
    ElMessage.error('图片读取失败，换一张试试')
  }
}

/** 保存:PUT /api/user/profile */
async function handleSave() {
  const nickname = form.nickname.trim()
  const username = form.username.trim()

  if (!nickname) {
    ElMessage.warning('请填写昵称')
    return
  }
  if (!username) {
    ElMessage.warning('请填写用户名')
    return
  }
  // 后端限制 avatar ≤ 500KB(见 docs/api.md §12.2),这里先拦一下,省得白跑一次 400
  if (form.avatar.length > AVATAR_MAX_BYTES) {
    ElMessage.warning('头像图片太大了，换一张小一点的图片')
    return
  }

  saving.value = true
  try {
    const data = await updateProfile({ nickname, username, avatar: form.avatar })
    // 后端没回完整对象时用表单值兜底,保证导航栏头像立刻更新
    setUserInfo(data || { nickname, username, avatar: form.avatar })
    ElMessage.success('资料已更新')
    emit('saved')
    close()
  } catch (error) {
    // 业务错误(如 1001 用户名已存在)后端带的是能直接给用户看的中文文案,优先展示;
    // 网络 / 服务端异常再交给 showError 统一转成友好提示
    if (typeof error?.code === 'number' && error.message) {
      ElMessage.error(error.message)
    } else {
      showError(error)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="编辑资料"
    width="520px"
    @update:model-value="(value) => emit('update:visible', value)"
  >
    <el-form label-position="top">
      <el-form-item label="更换头像">
        <!-- 头像预览:emoji 或 base64 图片,72x72 圆形 -->
        <div class="avatar-preview">
          <img
            v-if="isImageAvatar(form.avatar)"
            :src="form.avatar"
            alt="头像预览"
            class="avatar-preview-img"
          />
          <span v-else class="avatar-preview-emoji">{{ form.avatar || DEFAULT_AVATAR }}</span>
        </div>

        <div class="avatar-options">
          <button
            v-for="emoji in AVATAR_OPTIONS"
            :key="emoji"
            type="button"
            class="avatar-option"
            :class="{ 'is-active': form.avatar === emoji }"
            @click="form.avatar = emoji"
          >
            {{ emoji }}
          </button>
        </div>

        <input
          ref="fileInputRef"
          class="hidden-file"
          type="file"
          accept="image/*"
          @change="handleFileChange"
        />
        <el-button :icon="Upload" size="small" @click="fileInputRef?.click()">
          选择本地图片
        </el-button>
      </el-form-item>

      <el-form-item label="昵称">
        <el-input
          v-model="form.nickname"
          class="full-width"
          :maxlength="NICKNAME_MAX"
          placeholder="请输入昵称"
          clearable
        />
      </el-form-item>

      <el-form-item label="用户名">
        <el-input
          v-model="form.username"
          class="full-width"
          :maxlength="USERNAME_MAX"
          placeholder="请输入用户名"
          clearable
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="saving" @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.avatar-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  margin-bottom: 12px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: var(--hover);
}

.avatar-preview-emoji {
  font-size: 2rem;
  line-height: 1;
}

.avatar-preview-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

/* 可选 emoji:小方块按钮,选中道奇蓝描边(原型同款) */
.avatar-option {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  padding: 0;
  font-family: inherit;
  font-size: 1.25rem;
  line-height: 1;
  background: var(--bg);
  border: 1.5px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.12s ease;
}

.avatar-option:hover {
  border-color: var(--accent);
}

.avatar-option.is-active {
  border-color: var(--dodger-blue);
}

.hidden-file {
  display: none;
}

.full-width {
  width: 100%;
}
</style>
