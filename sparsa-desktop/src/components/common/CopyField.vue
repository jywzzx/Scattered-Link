<script setup>
/**
 * 只读文本 + 一键复制。
 *
 * 复制走三层兜底：Electron clipboard → navigator.clipboard → textarea 选中执行 execCommand。
 * 为什么不用 navigator.clipboard 一把梭：file:// 下它不一定被认作安全上下文。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  value: { type: String, default: '' },
  label: { type: String, default: '' },
  mono: { type: Boolean, default: true },
  multiline: { type: Boolean, default: false },
  rows: { type: Number, default: 3 },
  copyText: { type: String, default: '' },
  disabled: { type: Boolean, default: false }
})

const copied = ref(false)
let timer = null

async function copy() {
  const text = props.copyText || props.value
  if (!text) return
  try {
    if (window.sparsa?.clipboard) {
      await window.sparsa.clipboard.write(text)
    } else if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    copied.value = true
    clearTimeout(timer)
    timer = setTimeout(() => (copied.value = false), 1800)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败。可手动选择文本后复制。')
  }
}

// 允许父组件用一个外部按钮触发复制（例如「复制配置内容」主按钮）
defineExpose({ copy })
</script>

<template>
  <div class="copy-field">
    <div v-if="label" class="cf-label">{{ label }}</div>
    <div class="cf-body">
      <div v-if="multiline" class="cf-value cf-value--multi" :class="{ 'sp-mono': mono }">
        {{ value || '—' }}
      </div>
      <div v-else class="cf-value" :class="{ 'sp-mono': mono }">{{ value || '—' }}</div>
      <el-button
        class="cf-btn"
        :disabled="disabled || !(copyText || value)"
        :type="copied ? 'success' : 'primary'"
        :plain="!copied"
        size="small"
        @click="copy"
      >
        <el-icon><component :is="copied ? 'Select' : 'CopyDocument'" /></el-icon>
        <span class="cf-btn-text">{{ copied ? '已复制' : '复制' }}</span>
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.copy-field {
  width: 100%;
}
.cf-label {
  font-size: 12px;
  color: var(--sp-text-muted);
  margin-bottom: 6px;
}
.cf-body {
  display: flex;
  align-items: stretch;
  gap: 8px;
}
.cf-value {
  flex: 1;
  min-width: 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--sp-bg-sunken);
  border: 1px solid var(--sp-border);
  font-size: 13px;
  line-height: 1.6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  user-select: text;
}
.cf-value--multi {
  white-space: pre-wrap;
  max-height: 220px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.65;
}
.cf-btn {
  flex: 0 0 auto;
}
.cf-btn-text {
  margin-left: 4px;
}
</style>
