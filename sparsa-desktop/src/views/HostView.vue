<script setup>
/**
 * 创建房间（房主侧）。对应 Swing 的 buildHostCard + beginHost。
 *
 * 页面分两个阶段，而不是一个页面塞满控件：
 *   未建房 → 只显示参数与「创建房间」
 *   已建房 → 邀请码、成员、连通性测试，参数折叠收起
 * 因为建房后改参数本来就不生效，摆在面前只会误导。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSessionStore } from '@/stores/session'
import { useAppStore } from '@/stores/app'
import { phoneApi } from '@/api'
import RelaySettings from '@/components/room/RelaySettings.vue'
import MemberTable from '@/components/room/MemberTable.vue'
import CopyField from '@/components/common/CopyField.vue'
import QrCanvas from '@/components/common/QrCanvas.vue'

const router = useRouter()
const session = useSessionStore()
const app = useAppStore()

const opts = reactive({ relayUri: '', usePublicNodes: true, noP2p: false })
const creating = ref(false)
const probing = ref(false)
const pingResult = ref(null)
const showAdvanced = ref(false)

const running = computed(() => session.active)
const isThisHost = computed(() => session.active && session.isHost)

/** 运行中改了参数：后端会给出 divergence，或由前端比对得出 */
const restartHint = computed(() => session.divergence || session.checkNeedsRestart(opts))

onMounted(async () => {
  await app.boot()
  if (isThisHost.value) {
    syncFromSession()
  } else if (app.network?.publicIp) {
    // 未建房时给一个默认建议：网络环境差就默认开公共节点
    opts.usePublicNodes = true
  }
})

/**
 * 只在「房间刚建立」这一刻把参数同步到表单，用于刷新页面后回显。
 *
 * 不能直接 watch session.session：轮询每 2 秒会用新对象替换它，
 * 若每次都 syncFromSession，用户正在改的开关会被覆写成内核里的旧值，
 * 表现为开关自己跳动、点了也留不住。
 * startedAt 是会话创建时间，轮询期间不变，用它做触发条件即可。
 */
watch(
  () => (session.session?.active ? session.session.startedAt : 0),
  (stamp) => {
    if (stamp && !creating.value) syncFromSession()
  }
)

function syncFromSession() {
  const s = session.session
  if (!s) return
  opts.relayUri = s.relayUri || ''
  opts.usePublicNodes = !!s.usePublicNodes
  opts.noP2p = !!s.noP2p
}

/**
 * 接收子组件回传的参数快照。
 *
 * 不能写成 v-model="opts"：opts 是 const reactive 对象，
 * v-model 会被编译成对变量的赋值语句 opts = $event，而 const 不允许赋值，
 * 运行时报 Assignment to constant variable，开关点了就弹回原位。
 * 这里改为把字段写回同一个对象，保持引用不变。
 */
function applyOpts(next) {
  if (next) Object.assign(opts, next)
}

async function recommendNode() {
  probing.value = true
  try {
    const res = await app.loadPublicNodes(3)
    const node = res?.recommended || res?.nodes?.[0]
    if (node) {
      opts.relayUri = node
      ElMessage.success('已填入推荐节点')
    } else {
      ElMessage.warning('未探测到可用节点。需手动填写中继节点。')
    }
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    probing.value = false
  }
}

async function createRoom() {
  if (opts.noP2p && !opts.relayUri.trim()) {
    ElMessage.error('缺少中继节点。填入节点，或取消强制统一中继。')
    return
  }
  creating.value = true
  pingResult.value = null
  try {
    await session.host({
      relayUri: opts.relayUri.trim(),
      // 强制中继与公共节点互斥：多个节点会让两端落到不同通道。
      // 界面上开关已置灰，但它的值仍是上次的 true，这里必须显式收敛。
      usePublicNodes: opts.noP2p ? false : opts.usePublicNodes,
      noP2p: opts.noP2p
    })
    ElMessage.success('房间已创建')
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    creating.value = false
  }
}

/** 单个成员测试。ip 为空时由后端取第一个非本机成员。 */
async function testPing(ip) {
  try {
    const r = await session.ping(ip)
    pingResult.value = r
    if (r.ok) {
      ElMessage.success(r.ms == null ? r.detail : `与 ${r.target} 连通（${r.ms} ms）`)
    } else {
      ElMessage.warning(r.detail || '未连通')
    }
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  }
}

/** 逐个测试房间内全部其他成员。多人房间中各条链路互相独立，需要分开测。 */
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
      results.push({ ok: false, target: ip, ms: null, detail: String(e?.message || e) })
    }
  }
  const okCount = results.filter((r) => r.ok).length
  const detail = results
    .map((r) => `${r.target} ${r.ok ? (r.ms == null ? '连通' : r.ms + ' ms') : '不通'}`)
    .join('，')
  pingResult.value = {
    ok: okCount === targets.length,
    ms: null,
    detail,
    detailTitle: `连通 ${okCount} 个，共 ${targets.length} 个`
  }
  if (okCount === targets.length) ElMessage.success('全部成员连通')
  else ElMessage.warning(`有 ${targets.length - okCount} 个成员不通`)
}

async function stopRoom() {
  await session.stop()
  pingResult.value = null
  ElMessage.success('已断开')
}

/** 建房后顺手确认手机端配置能否生成，避免用户点进手机端才发现问题 */
const phonePreview = ref(null)
watch(isThisHost, async (v) => {
  if (!v) {
    phonePreview.value = null
    return
  }
  try {
    phonePreview.value = await phoneApi.profile({ mode: opts.noP2p ? 'relay' : 'direct', relayUri: opts.relayUri })
  } catch {
    phonePreview.value = null
  }
})
</script>

<template>
  <div class="sp-page">
    <!-- ── 未建房：参数表单 ── -->
    <template v-if="!isThisHost">
      <h2 class="sp-page-title">创建房间</h2>
      <p class="sp-page-desc">
        创建后本机成为房主，并生成邀请码。房主保持程序运行，对方才能接入。
      </p>

      <div class="sp-card pad">
        <RelaySettings
          :model-value="opts"
          :probing="probing"
          :running="running"
          @update:model-value="applyOpts"
          @recommend="recommendNode"
        />

        <el-divider />

        <div class="action-row">
          <el-button type="primary" size="large" :loading="creating" @click="createRoom">
            <el-icon><Promotion /></el-icon>
            创建房间
          </el-button>
          <span class="action-hint">
            创建时会请求管理员权限以安装虚拟网卡。系统提示中选择「允许」。
          </span>
        </div>
      </div>
    </template>

    <!-- ── 已建房：邀请码 + 成员 ── -->
    <template v-else>
      <div class="head-row">
        <div>
          <h2 class="sp-page-title">房间已创建</h2>
          <p class="sp-page-desc" style="margin-bottom: 0">
            把邀请码发给对方即可接入。本机虚拟 IP 固定为
            <span class="sp-mono strong">{{ session.myIp }}</span>。
          </p>
        </div>
        <el-button plain @click="stopRoom">断开连接</el-button>
      </div>

      <el-alert
        v-if="restartHint"
        type="warning"
        show-icon
        :closable="false"
        class="gap"
        title="界面参数与内核不一致"
        :description="restartHint"
      />

      <div class="sp-card pad gap">
        <div class="invite-grid">
          <div class="invite-main">
            <CopyField label="邀请码（发给对方）" :value="session.inviteCode" />
            <CopyField
              class="gap-sm"
              label="对方在游戏中应填的地址"
              :value="session.myIp"
            />
            <p class="addr-hint">
              本机是房主，这个地址就是本机虚拟 IP。若游戏房间由对方创建，则本机填对方的地址。
            </p>
            <el-alert
              v-if="!session.hasOthers"
              type="info"
              :closable="false"
              show-icon
              class="gap-sm"
              title="暂无其他成员接入"
              description="对方接入后，成员列表会显示他的虚拟 IP。"
            />
          </div>
          <div class="invite-qr">
            <QrCanvas :text="session.inviteCode" :size="168" />
            <p class="qr-caption">手机扫码可直接复制邀请码</p>
          </div>
        </div>
      </div>

      <div class="sp-card pad gap">
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

        <el-alert
          v-if="pingResult"
          :type="pingResult.ok ? 'success' : 'warning'"
          :closable="false"
          show-icon
          class="gap-sm"
          :title="pingResult.detailTitle
            ? pingResult.detailTitle
            : pingResult.ok
              ? (pingResult.ms == null ? '连通性正常' : `连通性正常 · ${pingResult.ms} ms`)
              : '连通性未通过'"
          :description="pingResult.detail"
        />

        <MemberTable :members="session.members" testable @test="testPing" />

        <p class="note">
          本机不参与连通性测试。测试本机会走本机回环，结果恒为连通，无法证明虚拟网卡与隧道是否可用。
        </p>
      </div>

      <!-- 折叠区：运行中不再改动内核参数，只做展示 -->
      <div class="sp-card pad gap">
        <div class="collapse-head" @click="showAdvanced = !showAdvanced">
          <span class="sp-section-title" style="margin: 0">
            <el-icon><Setting /></el-icon>
            当前链路参数
          </span>
          <el-button link type="primary" size="small">
            {{ showAdvanced ? '收起' : '展开' }}
          </el-button>
        </div>

        <el-descriptions v-if="showAdvanced" :column="2" size="small" border class="gap-sm">
          <el-descriptions-item label="界面模式">
            {{ session.session?.noP2p ? '强制统一中继' : '直连优先' }}
          </el-descriptions-item>
          <el-descriptions-item label="内核实际">
            {{ session.kernel?.noP2p ? '强制中继' : '直连' }} /
            公共节点 {{ session.kernel?.usePublicNodes ? '开' : '关' }}
          </el-descriptions-item>
          <el-descriptions-item label="中继节点">{{ session.session?.relayUri || '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="监听端口">{{ session.session?.listenPort || '—' }}</el-descriptions-item>
          <el-descriptions-item label="内核节点" :span="2">
            <span class="sp-mono small">{{ (session.kernel?.peers || []).join(' , ') || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>
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
.action-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.action-hint {
  font-size: 12px;
  color: var(--sp-text-muted);
}
.invite-grid {
  display: grid;
  grid-template-columns: 1fr 200px;
  gap: 20px;
  align-items: start;
}
.invite-main {
  min-width: 0;
}
.invite-qr {
  text-align: center;
}
.qr-caption {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  line-height: 1.6;
}
.addr-hint {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  line-height: 1.7;
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
.note {
  margin: 14px 0 0;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  line-height: 1.75;
}
.collapse-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}
.small {
  font-size: 11.5px;
}
</style>
