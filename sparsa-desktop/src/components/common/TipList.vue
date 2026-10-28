<script setup>
/**
 * 提示清单。
 *
 * 旧版把这些话写在 TOML 注释里，但用户导入配置时根本不会打开文件 ——
 * 所以提示必须显示在界面上，并且分级：error 要先看到，info 可以后看。
 */
import { computed } from 'vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  title: { type: String, default: '' },
  compact: { type: Boolean, default: false }
})

const ICONS = {
  error: 'CircleCloseFilled',
  warn: 'WarningFilled',
  info: 'InfoFilled',
  success: 'SuccessFilled'
}

const sorted = computed(() => {
  const rank = { error: 0, warn: 1, success: 2, info: 3 }
  return [...props.items].sort((a, b) => (rank[a.level] ?? 9) - (rank[b.level] ?? 9))
})
</script>

<template>
  <div v-if="items.length" class="tips" :class="{ compact }">
    <div v-if="title" class="tips-title">{{ title }}</div>
    <ul class="tips-list">
      <li v-for="(t, i) in sorted" :key="i" class="tip" :class="'tip--' + (t.level || 'info')">
        <el-icon class="tip-icon"><component :is="ICONS[t.level] || ICONS.info" /></el-icon>
        <span class="tip-text">{{ t.text }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.tips {
  border-radius: 10px;
  background: var(--sp-bg-sunken);
  padding: 12px 14px;
}
.tips.compact {
  padding: 8px 10px;
}
.tips-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sp-text-secondary);
  margin-bottom: 8px;
}
.tips-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tip {
  display: flex;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--sp-text-secondary);
}
.tip-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  font-size: 14px;
  color: var(--sp-text-muted);
}
.tip--error .tip-icon {
  color: var(--sp-danger);
}
.tip--warn .tip-icon {
  color: var(--sp-warning);
}
.tip--success .tip-icon {
  color: var(--sp-success);
}
.tip--info .tip-icon {
  color: var(--sp-primary);
}
.tip--error .tip-text {
  color: #a82727;
}
.tip-text {
  min-width: 0;
}
</style>
