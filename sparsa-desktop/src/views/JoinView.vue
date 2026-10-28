<script setup>
/**
 * 加入房间（加入方）。对应 Swing 的 buildJoinCard + beginJoinFromCode / beginJoinFromPastedConfig。
 *
 * 三条输入路径，但只对应一个后端动作（POST /session/join）：
 *   1. 邀请码    —— 最常见，粘贴 地址:端口#密钥
 *   2. 粘贴配置  —— 手机扫码/微信拿到的是整段 TOML，自动抠出房间信息
 *   3. IP + 密码 —— 邀请码丢了但对方能报出地址时用
 * 前端先本地解析给出即时反馈，最终仍以后端判定为准。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSessionStore } from '@/stores/session'
import { useAppStore } from '@/stores/app'
import { phoneApi } from '@/api'
import { parseInvite, parseConfigText } from '@/utils/invite'
import MemberTable from '@/components/room/MemberTable.vue'
import CopyField from '@/components/common/CopyField.vue'
import TipList from '@/components/common/TipList.vue'

const router = useRouter()
const session = useSessionStore()
const app = useAppStore()

const tab = ref('code')
const joining = ref(false)

const codeText = ref('')
const configText = ref('')
const manual = reactive({ host: '', secret: '' })
const relayOpts = reactive({ noP2p: false, relayUri: '' })

const joined = computed(() => session.active && session.isJoiner)

/* 本地即时解析 */
const codeParsed = computed(() => (codeText.value.trim() ? parseInvite(codeText.value) : null))
const configParsed = computed(() => (configText.value.trim() ? parseConfigText(configText.value) : null))

const manualValid = computed(
  () => /^[0-9a-zA-Z.\-:[\]]+$/.test(manual.host.trim()) && manual.secret.trim().length >= 12
)

const canSubmit = computed(() => {
  if (tab.value === 'code') return !!codeParsed.value?.ok
  if (tab.value === 'config') return !!configParsed.value?.ok
  return manualValid.value
})

onMounted(() => app.boot())

/* 粘贴配置里认出模式后，同步链路开关，让下面的参数与手机一致 */
watch(configParsed, (p) => {
  if (!p?.ok) return
  relayOpts.noP2p = p.mode === 'relay'
  if (p.mode === 'relay' && p.relay) relayOpts.relayUri = p.relay
})

async function readFile() {
  if (!window.sparsa?.dialog) {
    ElMessage.info('浏览器模式不支持读取文件。可粘贴文本。')
    return
  }
  try {
    const r = await window.sparsa.dialog.openText()
    if (r.canceled) return
    configText.value = r.content || ''
    tab.value = 'config'
    ElMessage.success('已读取 ' + r.filePath)
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  }
}

async function pasteFromClipboard() {
  try {
    let text = ''
    if (window.sparsa?.clipboard) text = await window.sparsa.clipboard.read()
    else text = await navigator.clipboard.readText()
    if (!text) return ElMessage.warning('剪贴板为空')
    if (tab.value === 'config') configText.value = text
    else codeText.value = text
    ElMessage.success('已粘贴')
  } catch {
    ElMessage.error('读取剪贴板失败。可手动粘贴。')
  }
}

async function doJoin() {
  let payload = { noP2p: relayOpts.noP2p, relayUri: relayOpts.relayUri, usePublicNodes: !relayOpts.noP2p }

  if (tab.value === 'code') {
    payload.invite = codeParsed.value.invite
  } else if (tab.value === 'config') {
    const p = configParsed.value
    // 优先用配置里认出的初始节点；中继模式下它就是唯一通道
    payload.noP2p = p.mode === 'relay'
    payload.relayUri = p.relay || relayOpts.relayUri
    payload.usePublicNodes = p.mode !== 'relay'
    if (p.relay) {
      const host = p.relay.replace(/^\w+:\/\//, '').split(':')[0]
      const port = Number(p.relay.split(':').pop())
      payload.invite = {
        host,
        port,
        secret: p.secret,
        code: `${host}:${port}#${p.secret}`,
        displayHost: `${host}:${port}`,
        roomName: 'lanlink'
      }
    } else {
      ElMessage.error('配置中没有对方地址，只有公共节点。无法直接加入。')
      return
    }
  } else {
    payload.invite = {
      host: manual.host.trim(),
      port: 11010,
      secret: manual.secret.trim(),
      code: `${manual.host.trim()}:11010#${manual.secret.trim()}`,
      displayHost: `${manual.host.trim()}:11010`,
      roomName: 'lanlink'
    }
    payload.noP2p = relayOpts.noP2p
  }

  if (payload.noP2p && !payload.relayUri) {
    // 强制中继必须有节点，否则两端没有共同通道
    try {
      const res = await app.loadPublicNodes(1)
      const node = res?.recommended || res?.nodes?.[0]
      if (node) {
        payload.relayUri = node
        ElMessage.warning(`未填中继节点，已自动使用 ${node}`)
      } else {
        ElMessage.error('已开启强制中继但没有可用节点，请手动填写')
        return
      }
    } catch {
      ElMessage.error('已开启强制中继但没有可用节点，请手动填写')
      return
    }
  }

  joining.value = true
  try {
    await session.join(payload)
    ElMessage.success('已发起连接')
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    joining.value = false
  }
}

/** 单个成员测试。ip 为空时由后端取第一个非本机成员。 */
async function testPing(ip) {
  try {
    const r = await session.ping(ip)
    if (r.ok) ElMessage.success(r.ms == null ? r.detail : `与 ${r.target} 连通（${r.ms} ms）`)
    else ElMessage.warning(r.detail || '未连通')
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  }
}

/** 逐个测试房间内全部其他成员 */
async function testAll() {
  const targets = session.others.map((m) => m.ipv4)
  if (!targets.length) {
    ElMessage.warning('暂无其他成员接入')
    return
  }
  const results = []
  for (const ip of targets) {
    try {
      results.push(await session.ping(ip))
    } catch (e) {
      results.push({ ok: false, target: ip, ms: null })
    }
  }
  const okCount = results.filter((r) => r.ok).length
  if (okCount === targets.length) ElMessage.success('全部成员连通')
  else ElMessage.warning(`有 ${targets.length - okCount} 个成员不通`)
}

async function leave() {
  await session.stop()
  ElMessage.success('已断开')
}
</script>

<template>
  <div class="sp-page">
    <!-- ── 已加入 ── -->
    <template v-if="joined">
      <div class="head-row">
        <div>
          <h2 class="sp-page-title">已连接</h2>
          <p class="sp-page-desc" style="margin-bottom: 0">
            本机虚拟 IP 为 <span class="sp-mono strong">{{ session.myIp }}</span>。
            游戏内填写房主的 IP。
          </p>
        </div>
        <el-button plain @click="leave">断开连接</el-button>
      </div>

      <el-alert
        v-if="session.divergence"
        type="warning"
        show-icon
        :closable="false"
        class="gap"
        title="界面参数与内核不一致"
        :description="session.divergence"
      />

      <div class="sp-card pad gap">
        <CopyField
          label="把这个地址发给房主，对方在游戏内填写"
          :value="session.myIp"
        />
        <div class="sp-card pad gap-sm inner">
          <div class="members-head">
            <span class="sp-section-title" style="margin: 0">
              <el-icon><User /></el-icon>
              房间成员（{{ session.memberCount }}）
            </span>
            <div class="members-actions">
              <el-button size="small" :disabled="!session.hasOthers" @click="testAll">
                <el-icon><Aim /></el-icon>
                测试全部
              </el-button>
              <el-button size="small" plain @click="router.push('/phone')">
                <el-icon><Iphone /></el-icon>
                手机端配置
              </el-button>
            </div>
          </div>
          <MemberTable :members="session.members" testable @test="testPing" />
        </div>
      </div>
    </template>

    <!-- ── 未加入：三种输入方式 ── -->
    <template v-else>
      <h2 class="sp-page-title">加入房间</h2>
      <p class="sp-page-desc">对方建房后提供邀请码或手机端配置。任一方式均可接入。</p>

      <div class="sp-card pad">
        <el-tabs v-model="tab">
          <el-tab-pane label="邀请码" name="code">
            <el-input
              v-model="codeText"
              type="textarea"
              :rows="2"
              placeholder="粘贴对方发来的邀请码，例如 113.133.208.7:41273#k7m2q9x3rt5vb6n"
              class="mono-input"
            />
            <div class="toolbar">
              <el-button size="small" plain @click="pasteFromClipboard">
                <el-icon><DocumentCopy /></el-icon>
                粘贴
              </el-button>
              <span v-if="codeParsed" class="parse-state" :class="codeParsed.ok ? 'ok' : 'bad'">
                <el-icon><component :is="codeParsed.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" /></el-icon>
                {{ codeParsed.ok ? `已识别 ${codeParsed.invite.displayHost}` : codeParsed.error }}
              </span>
            </div>
          </el-tab-pane>

          <el-tab-pane label="粘贴配置" name="config">
            <el-input
              v-model="configText"
              type="textarea"
              :rows="7"
              placeholder="把手机扫码或微信里得到的整段配置粘进来，会自动认出房间地址与密钥"
              class="mono-input"
            />
            <div class="toolbar">
              <el-button size="small" plain @click="pasteFromClipboard">
                <el-icon><DocumentCopy /></el-icon>
                粘贴
              </el-button>
              <el-button size="small" plain @click="readFile">
                <el-icon><FolderOpened /></el-icon>
                从文件读取
              </el-button>
              <span v-if="configParsed" class="parse-state" :class="configParsed.ok ? 'ok' : 'bad'">
                <el-icon><component :is="configParsed.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" /></el-icon>
                {{ configParsed.ok ? `已识别 · ${configParsed.mode === 'relay' ? '强制统一中继' : '直连优先'}` : '配置有缺项' }}
              </span>
            </div>

            <TipList
              v-if="configParsed && !configParsed.ok"
              class="gap-sm"
              :items="configParsed.problems.map((t) => ({ level: 'error', text: t }))"
            />
            <el-descriptions
              v-if="configParsed?.ok"
              :column="2"
              size="small"
              border
              class="gap-sm"
            >
              <el-descriptions-item label="初始节点">
                <span class="sp-mono small">{{ configParsed.relay || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="虚拟 IPv4">
                <span class="sp-mono small">{{ configParsed.ipv4 || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="网络名称">
                <span class="sp-mono small">{{ configParsed.networkName }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="节点数量">{{ configParsed.peers.length }}</el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>

          <el-tab-pane label="IP + 密码" name="manual">
            <div class="manual">
              <div class="manual-item">
                <label>对方 IP 地址</label>
                <el-input v-model="manual.host" placeholder="113.133.208.7 或 192.168.1.24" />
              </div>
              <div class="manual-item">
                <label>房间密码</label>
                <el-input v-model="manual.secret" placeholder="16 位密钥" show-password />
              </div>
            </div>
            <p class="sp-hint">端口默认 11010。邀请码含端口时改用邀请码方式。</p>
          </el-tab-pane>
        </el-tabs>

        <el-divider />

        <div class="action-row">
          <el-button type="primary" size="large" :disabled="!canSubmit" :loading="joining" @click="doJoin">
            <el-icon><Connection /></el-icon>
            连接
          </el-button>
          <span class="action-hint">连接过程中会创建虚拟网卡，首次可能请求管理员权限。</span>
        </div>
      </div>
    </template>
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
.inner {
  box-shadow: none;
  background: #fbfcfe;
}
.head-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 4px;
}
.strong {
  font-weight: 700;
  color: var(--sp-primary);
}
.mono-input :deep(textarea) {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.7;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.parse-state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  margin-left: 4px;
}
.parse-state.ok {
  color: var(--sp-success);
}
.parse-state.bad {
  color: var(--sp-danger);
}
.manual {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.manual-item label {
  display: block;
  font-size: 12px;
  color: var(--sp-text-muted);
  margin-bottom: 6px;
}
.action-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.action-hint {
  font-size: 12px;
  color: var(--sp-text-muted);
}
.members-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.members-actions {
  display: flex;
  gap: 8px;
}
.small {
  font-size: 11.5px;
}
</style>
