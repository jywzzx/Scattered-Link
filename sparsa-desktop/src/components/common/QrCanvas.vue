<script setup>
/**
 * 二维码画布。
 *
 * 关键点不是「画个码」，而是**容量控制**：
 * 手机端要扫的是整段配置文本（中英混排），QR 的字节上限是分模式的，
 * 纯字节模式 2953 字节 ≠ 混排文本能塞 2953 字符。
 * 所以这里先用保守成本模型估算，再按成本自适应纠错等级：
 *   成本 ≤ 260 → H（最抗污损，屏幕上有反光也能扫）
 *   成本 ≤ 620 → M
 *   其余      → L（尽量塞下，容错最低）
 * 超限时明确显示「配置过长」，而不是丢一张失败的图让人猜。
 */
import { ref, watch, onMounted, computed } from 'vue'
import QRCode from 'qrcode'
import { qrCost, pickQrLevel, SAFE_COST } from '@/utils/qr'

const props = defineProps({
  text: { type: String, default: '' },
  size: { type: Number, default: 260 }
})

const src = ref('')
const state = ref('idle') // idle | ok | overflow | error
const message = ref('')
const levelUsed = ref('')

const cost = computed(() => qrCost(props.text))
const overLimit = computed(() => cost.value > SAFE_COST)

async function render() {
  if (!props.text) {
    state.value = 'idle'
    src.value = ''
    return
  }
  if (overLimit.value) {
    state.value = 'overflow'
    message.value = `配置约 ${cost.value} 字节，超过二维码容量上限 ${SAFE_COST} 字节。`
    src.value = ''
    return
  }
  const level = pickQrLevel(cost.value)
  levelUsed.value = level
  try {
    src.value = await QRCode.toDataURL(props.text, {
      errorCorrectionLevel: level,
      margin: 1,
      width: props.size * 2, // 2 倍图，高分屏不糊
      color: { dark: '#1f2733', light: '#ffffff' }
    })
    state.value = 'ok'
  } catch (e) {
    state.value = 'error'
    message.value = String(e?.message || e)
    src.value = ''
  }
}

watch(() => [props.text, props.size], render)
onMounted(render)
</script>

<template>
  <div class="qr">
    <div class="qr-frame" :style="{ width: size + 'px', height: size + 'px' }">
      <img v-if="state === 'ok'" :src="src" :width="size" :height="size" alt="配置二维码" />
      <div v-else class="qr-placeholder">
        <el-icon class="qr-icon"><component :is="state === 'overflow' ? 'WarningFilled' : 'Picture'" /></el-icon>
        <p class="qr-tip">{{ state === 'overflow' ? '配置过长，无法生成二维码' : '暂无配置内容' }}</p>
      </div>
    </div>
    <div class="qr-meta">
      <span v-if="state === 'ok'">纠错等级 {{ levelUsed }} · 约 {{ cost }} 字节</span>
      <span v-else-if="state === 'overflow'" class="warn">{{ message }}</span>
      <span v-else>—</span>
    </div>
  </div>
</template>

<style scoped>
.qr {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.qr-frame {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border: 1px solid var(--sp-border);
  border-radius: 12px;
  box-shadow: var(--sp-shadow-sm);
  overflow: hidden;
}
.qr-placeholder {
  text-align: center;
  color: var(--sp-text-muted);
  padding: 16px;
}
.qr-icon {
  font-size: 28px;
  color: var(--sp-border-strong);
}
.qr-tip {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
.qr-meta {
  font-size: 11.5px;
  color: var(--sp-text-muted);
}
.qr-meta .warn {
  color: var(--sp-warning);
}
</style>
