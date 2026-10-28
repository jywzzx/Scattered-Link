<script setup>
/**
 * 运行日志。对应 Swing 的 buildLogPanel + appendLog。
 *
 * 两个细节来自旧版的教训：
 *   1. 自动滚底只在「用户没有手动上滚」时执行 —— 否则你正翻日志，它一直把你拽回底部
 *   2. 用「内容没变就不 setText」的等价策略避免整块重绘导致的位置重置
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { logApi } from '@/api'
import { ElMessage } from 'element-plus'

const lines = ref([])
const keyword = ref('')
const autoScroll = ref(true)
const boxRef = ref(null)
const loading = ref(false)
let timer = null

const filtered = computed(() => {
  const k = keyword.value.trim()
  if (!k) return lines.value
  return lines.value.filter((l) => l.includes(k))
})

function nearBottom() {
  const el = boxRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

async function load(initial = false) {
  if (!initial && loading.value) return
  loading.value = true
  try {
    const r = await logApi.tail(400)
    const next = r.lines || []
    // 内容一致就不动 DOM，避免光标与滚动位置被重置
    if (next.length !== lines.value.length || next[next.length - 1] !== lines.value[lines.value.length - 1]) {
      const stick = autoScroll.value && nearBottom()
      lines.value = next
      if (stick) {
        await nextTick()
        scrollToBottom()
      }
    }
  } catch (e) {
    if (initial) ElMessage.error(String(e?.message || e))
  } finally {
    loading.value = false
  }
}

function scrollToBottom() {
  const el = boxRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function onScroll() {
  // 用户上滚 → 暂停自动跟随；回到底部 → 恢复
  autoScroll.value = nearBottom()
}

function clearView() {
  lines.value = []
  ElMessage.success('已清空当前视图。磁盘日志不受影响。')
}

watch(autoScroll, (v) => {
  if (v) scrollToBottom()
})

onMounted(() => {
  load(true)
  timer = setInterval(() => load(), 2000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="sp-page logs-page">
    <div class="head">
      <div>
        <h2 class="sp-page-title">运行日志</h2>
        <p class="sp-page-desc" style="margin-bottom: 0">内核与界面的事件按时间排列，出问题时截图反馈即可。</p>
      </div>
      <div class="actions">
        <el-input v-model="keyword" placeholder="过滤关键字" size="small" clearable class="filter" />
        <el-switch v-model="autoScroll" size="small" active-text="跟随最新" />
        <el-button size="small" plain @click="load()">刷新</el-button>
        <el-button size="small" plain @click="clearView">清空视图</el-button>
      </div>
    </div>

    <div ref="boxRef" class="log-box sp-mono" @scroll="onScroll">
      <div v-if="!filtered.length" class="log-empty">
        {{ lines.length ? '没有匹配的行' : '暂无日志' }}
      </div>
      <div v-for="(l, i) in filtered" :key="i" class="log-line" :class="{
        warn: /⚠|警告|超时|失败/.test(l),
        err: /错误|异常|Exception|FAIL/.test(l),
        ok: /✓|就绪|成功|通过/.test(l)
      }">{{ l }}</div>
    </div>

    <p class="foot-hint">
      共 {{ filtered.length }} 行。{{ keyword ? `已按「${keyword}」过滤。` : '' }}
      每 2 秒自动拉取。
    </p>
  </div>
</template>

<style scoped>
.logs-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: none;
  padding-bottom: 12px;
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.filter {
  width: 150px;
}
.log-box {
  flex: 1;
  min-height: 240px;
  overflow-y: auto;
  background: #fbfcfe;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  padding: 12px 14px;
  font-size: 12px;
  line-height: 1.75;
  user-select: text;
}
.log-line {
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--sp-text-secondary);
}
.log-line.warn {
  color: #a86a00;
}
.log-line.err {
  color: #b32d2d;
}
.log-line.ok {
  color: #0f7a44;
}
.log-empty {
  color: var(--sp-text-muted);
  font-size: 12px;
}
.foot-hint {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--sp-text-muted);
}
</style>
