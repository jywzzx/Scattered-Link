<script setup>
/**
 * 手机端配置。对应 Swing 的 buildRelayCard + QrCard + renderPhoneTips。
 *
 * 这个页面承载了本项目踩过的最多坑，所以设计上坚持三件事：
 *   1. 配置**跟随模式**：强制中继时只写一个 [[peer]] 并禁用打洞，绝不写一堆节点
 *   2. 提示**在界面上**：导入的人不会打开 TOML 看注释，所以 TOML 零注释，提示全在这里
 *   3. 不一致**要显性**：界面模式与内核实际参数不同时，红色横幅直接压在最上面
 *
 * 两种产物刻意分开：
 *   carryText —— 二维码 / 微信粘贴用，带说明与分隔标记，可反向解析
 *   toml      —— 纯配置，保存成文件给 EasyTier 导入用
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { phoneApi } from '@/api'
import { useSessionStore } from '@/stores/session'
import { useAppStore } from '@/stores/app'
import { BRAND } from '@/config/brand'
import QrCanvas from '@/components/common/QrCanvas.vue'
import CopyField from '@/components/common/CopyField.vue'
import TipList from '@/components/common/TipList.vue'

const session = useSessionStore()
const app = useAppStore()

const mode = ref('direct')
const relayUri = ref('')
const loading = ref(false)
const profile = ref(null)
const showToml = ref(false)
const copyBox = ref(null)

const isRelay = computed(() => mode.value === 'relay')

/** 手机端能连上谁，完全取决于当前会话是否给了对方一条路径 */
const sessionWarn = computed(() => {
  if (!session.active) return '当前没有房间在运行。下面的配置不指向任何可用地址，导入手机也连不上。'
  if (session.isHost && !session.hasOthers) return ''
  return ''
})

const missingRelay = computed(() => isRelay.value && !relayUri.value.trim())

onMounted(async () => {
  await app.boot()
  // 默认跟随当前会话的模式，而不是让用户再选一遍
  if (session.session) {
    mode.value = session.session.noP2p ? 'relay' : 'direct'
    relayUri.value = session.session.relayUri || ''
  }
  await load()
})

watch([mode, relayUri], load)

async function load() {
  loading.value = true
  try {
    profile.value = await phoneApi.profile({ mode: mode.value, relayUri: relayUri.value.trim() })
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    loading.value = false
  }
}

/** 外部主按钮走 CopyField 暴露出来的 copy，保证「已复制」状态与内联按钮一致 */
function copyConfig() {
  if (!profile.value?.carryText) return ElMessage.warning('配置尚未生成')
  copyBox.value?.copy()
}

async function useRecommended() {  try {
    const res = await app.loadPublicNodes(1)
    const node = res?.recommended || res?.nodes?.[0]
    if (!node) return ElMessage.warning('未探测到可用节点')
    relayUri.value = node
    mode.value = 'relay'
    ElMessage.success('已填入 ' + node)
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  }
}

async function saveFile() {
  const content = profile.value?.toml || ''
  if (!content) return
  if (window.sparsa?.dialog) {
    try {
      const r = await window.sparsa.dialog.saveText({
        content,
        defaultName: BRAND.configFileName,
        filters: [{ name: 'TOML 配置', extensions: ['toml'] }]
      })
      if (!r.canceled) ElMessage.success('已保存到 ' + r.filePath)
    } catch (e) {
      ElMessage.error(String(e?.message || e))
    }
    return
  }
  // 浏览器模式：走下载
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = BRAND.configFileName
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div class="sp-page">
    <h2 class="sp-page-title">手机端配置</h2>
    <p class="sp-page-desc">
      手机端 EasyTier 只有导入配置文件一个入口。需先把配置保存为文件，也可用二维码或微信文本传递。
    </p>

    <!-- 最高优先级：界面模式与内核实际不一致 -->
    <el-alert
      v-if="profile?.divergence"
      class="gap"
      type="error"
      show-icon
      :closable="false"
      title="配置已按新模式生成，但电脑端内核仍在旧模式下运行"
      :description="profile.divergence"
    />

    <el-alert
      v-if="sessionWarn"
      class="gap"
      type="warning"
      show-icon
      :closable="false"
      :title="sessionWarn"
    />

    <div class="sp-card pad gap">
      <div class="mode-row">
        <div class="mode-label">配置模式</div>
        <el-radio-group v-model="mode" size="default">
          <el-radio-button value="direct">直连优先</el-radio-button>
          <el-radio-button value="relay">强制统一中继</el-radio-button>
        </el-radio-group>
        <span class="mode-hint">
          {{ isRelay ? '只写一个初始节点，并禁用打洞' : '写入局域网地址、公网地址与社区公共节点三条路径' }}
        </span>
      </div>

      <div v-if="isRelay" class="mode-row">
        <div class="mode-label">中继节点</div>
        <el-input v-model="relayUri" placeholder="tcp://节点地址:11010" class="relay-input" clearable>
          <template #append>
            <el-button @click="useRecommended">填入推荐</el-button>
          </template>
        </el-input>
      </div>

      <el-alert
        v-if="missingRelay"
        class="gap-sm"
        type="error"
        :closable="false"
        show-icon
        title="强制中继模式未填写节点"
        description="手机端没有可用路径。填入节点，或改回直连优先。"
      />
    </div>

    <div class="sp-card pad gap">
      <div class="deliver">
        <div class="deliver-qr">
          <QrCanvas v-loading="loading" :text="profile?.carryText || ''" :size="240" />
          <p class="qr-caption">用手机相机扫描此二维码</p>
        </div>

        <div class="deliver-main">
          <CopyField
            ref="copyBox"
            label="配置文本（扫码 / 微信 / 记事本都行）"
            :value="profile?.carryText || ''"
            :copy-text="profile?.carryText || ''"
            multiline
            :rows="6"
          />

          <div class="deliver-actions">
            <el-button type="primary" @click="copyConfig">
              <el-icon><CopyDocument /></el-icon>
              复制配置内容
            </el-button>
            <el-button plain @click="saveFile">
              <el-icon><Download /></el-icon>
              保存为文件
            </el-button>
            <el-button link type="primary" @click="showToml = !showToml">
              {{ showToml ? '收起 TOML 正文' : '展开 TOML 正文' }}
            </el-button>
          </div>

          <div class="meta-grid">
            <div class="meta-item">
              <span class="k">网络名称</span>
              <span class="v sp-mono">{{ profile?.meta?.networkName || '—' }}</span>
            </div>
            <div class="meta-item">
              <span class="k">网络密码</span>
              <span class="v sp-mono">{{ profile?.meta?.secret || '—' }}</span>
            </div>
            <div class="meta-item">
              <span class="k">初始节点</span>
              <span class="v sp-mono">{{ profile?.meta?.initialPeer || '—' }}</span>
            </div>
            <div class="meta-item">
              <span class="k">手机虚拟 IP</span>
              <span class="v sp-mono">{{ profile?.meta?.myIp || '—' }}/24</span>
            </div>
          </div>
        </div>
      </div>

      <div v-if="showToml" class="toml-box">
        <pre class="sp-mono">{{ profile?.toml || '生成中' }}</pre>
      </div>
    </div>

    <div class="sp-card pad gap">
      <span class="sp-section-title">
        <el-icon><Warning /></el-icon>
        导入前核对
      </span>
      <TipList :items="profile?.tips || []" />
      <p v-if="profile?.ipCollisionHint" class="collision">{{ profile.ipCollisionHint }}</p>
    </div>
  </div>
</template>

<style scoped>
.pad {
  padding: 18px 20px;
}
.gap {
  margin-top: 16px;
}
.gap-sm {
  margin-top: 10px;
}
.mode-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}
.mode-row:last-child {
  margin-bottom: 0;
}
.mode-label {
  width: 68px;
  flex: 0 0 68px;
  font-size: 12.5px;
  color: var(--sp-text-secondary);
}
.mode-hint {
  font-size: 11.5px;
  color: var(--sp-text-muted);
}
.relay-input {
  max-width: 420px;
}

.deliver {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 24px;
  align-items: start;
}
.deliver-qr {
  text-align: center;
}
.qr-caption {
  margin: 10px 0 0;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  line-height: 1.6;
}
.deliver-main {
  min-width: 0;
}
.deliver-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}
.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 20px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--sp-border);
}
.meta-item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}
.meta-item .k {
  color: var(--sp-text-muted);
}
.meta-item .v {
  color: var(--sp-text);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.toml-box {
  margin-top: 16px;
  padding: 12px 14px;
  background: #f7f9fc;
  border: 1px solid var(--sp-border);
  border-radius: 8px;
  max-height: 300px;
  overflow: auto;
}
.toml-box pre {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--sp-text-secondary);
  user-select: text;
}
.collision {
  margin: 12px 0 0;
  font-size: 11.5px;
  color: var(--sp-warning);
  line-height: 1.7;
}
</style>
