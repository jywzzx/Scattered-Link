<script setup>
/**
 * 网络诊断。对应 Swing 的 buildDiagnostics + detectNetworkAsync。
 *
 * 这里刻意把「内核实际」与「界面声明」并排显示：
 * 历史上最难查的一个 bug 就是界面显示强制中继、内核其实在直连，
 * 只看界面永远发现不了，只有把两边摆在一起才一眼看穿。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { miscApi } from '@/api'
import { useAppStore } from '@/stores/app'
import { useSessionStore } from '@/stores/session'
import CopyField from '@/components/common/CopyField.vue'

const app = useAppStore()
const session = useSessionStore()

const diagText = ref('')
const loadingText = ref(false)
const probing = ref(false)

const preflightItems = computed(() => app.preflight?.items || [])
const publicNodes = computed(() => app.publicNodes?.nodes || [])

onMounted(async () => {
  await app.boot()
  await loadDiagnostics()
})

async function loadDiagnostics() {
  loadingText.value = true
  try {
    const r = await miscApi.diagnostics()
    diagText.value = r.text || ''
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    loadingText.value = false
  }
}

async function probeNodes() {
  probing.value = true
  try {
    await app.loadPublicNodes(3)
    ElMessage.success(`探测到 ${publicNodes.value.length} 个可用节点`)
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    probing.value = false
  }
}

async function addFirewall() {
  try {
    const r = await app.addFirewall()
    ElMessage.success(r.detail || '防火墙规则已添加')
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  }
}

async function saveDiagnostics() {
  if (!diagText.value) return
  if (!window.sparsa?.dialog) {
    ElMessage.info('浏览器模式不支持保存文件')
    return
  }
  try {
    const r = await window.sparsa.dialog.saveText({
      content: diagText.value,
      defaultName: 'sparsa-diagnostics.txt',
      filters: [{ name: '文本文件', extensions: ['txt'] }]
    })
    if (!r.canceled) ElMessage.success('已保存到 ' + r.filePath)
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  }
}
</script>

<template>
  <div class="sp-page">
    <h2 class="sp-page-title">网络诊断</h2>
    <p class="sp-page-desc">
      无法连接时先查看本页。成员显示在线但互相不可见时，多与 NAT 类型、端口映射、内核节点有关。
    </p>

    <div class="grid-2">
      <div class="sp-card pad">
        <span class="sp-section-title">
          <el-icon><Cpu /></el-icon>
          运行环境
        </span>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="操作系统">{{ app.system?.os || '—' }}</el-descriptions-item>
          <el-descriptions-item label="主机名">{{ app.system?.hostname || '—' }}</el-descriptions-item>
          <el-descriptions-item label="管理员权限">
            <el-tag :type="app.system?.admin ? 'success' : 'danger'" size="small" effect="light">
              {{ app.system?.admin ? '已提权' : '未提权' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Java 版本">{{ app.system?.javaVersion || '—' }}</el-descriptions-item>
          <el-descriptions-item label="内核文件">
            <span class="sp-mono tiny">{{ app.system?.corePath || '未找到' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider />

        <span class="sp-section-title">
          <el-icon><List /></el-icon>
          启动预检
        </span>
        <ul class="checks">
          <li v-for="it in preflightItems" :key="it.key" class="check">
            <el-icon :class="it.ok ? 'ok' : 'bad'">
              <component :is="it.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
            </el-icon>
            <span class="check-label">{{ it.label }}</span>
            <span class="check-detail">{{ it.detail }}</span>
          </li>
          <li v-if="!preflightItems.length" class="check muted">预检未返回结果</li>
        </ul>
      </div>

      <div class="sp-card pad">
        <span class="sp-section-title">
          <el-icon><Connection /></el-icon>
          网络环境
        </span>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="局域网 IP">
            <span class="sp-mono">{{ app.network?.lanIp || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="公网 IP">
            <span class="sp-mono">{{ app.network?.publicIp || '探测中' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="NAT 类型 (UDP)">
            <el-tag :type="app.network?.hardNat ? 'warning' : 'success'" size="small" effect="light">
              {{ app.network?.nat?.udp || '未知' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="NAT 类型 (TCP)">
            {{ app.network?.nat?.tcp || '未知' }}
          </el-descriptions-item>
          <el-descriptions-item label="端口映射">
            <el-tag :type="app.network?.portMappingOk ? 'success' : 'info'" size="small" effect="plain">
              {{ app.network?.portMappingOk ? '已生效' : '未生效' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="虚拟网卡">
            {{ app.network?.virtualNicUp ? '已启用' : '未启用' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="app.network?.hardNat && !app.network?.portMappingOk"
          class="gap-sm"
          type="warning"
          :closable="false"
          show-icon
          title="对称型 NAT 且端口未映射"
          description="点对点直连基本不会成功。建议开启社区公共节点作为中继兜底，或改用强制统一中继。"
        />

        <el-divider />

        <div class="nodes-head">
          <span class="sp-section-title" style="margin: 0">
            <el-icon><Position /></el-icon>
            社区公共节点
          </span>
          <el-button size="small" :loading="probing" @click="probeNodes">重新探测</el-button>
        </div>
        <ul v-if="publicNodes.length" class="nodes">
          <li v-for="n in publicNodes" :key="n" class="node sp-mono">
            {{ n }}
            <el-tag v-if="n === app.publicNodes?.recommended" size="small" type="success" effect="plain">
              推荐
            </el-tag>
          </li>
        </ul>
        <p v-else class="sp-hint">尚未探测。点重新探测将尝试连接公共节点。</p>

        <el-button class="gap-sm" size="small" plain @click="addFirewall">
          <el-icon><Lock /></el-icon>
          添加放行规则
        </el-button>
      </div>
    </div>

    <div class="sp-card pad gap">
      <div class="diag-head">
        <span class="sp-section-title" style="margin: 0">
          <el-icon><Tickets /></el-icon>
          诊断信息
        </span>
        <div class="diag-actions">
          <el-button size="small" :loading="loadingText" @click="loadDiagnostics">刷新</el-button>
          <el-button size="small" plain @click="saveDiagnostics">
            <el-icon><Download /></el-icon>
            保存
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="session.divergence"
        class="gap-sm"
        type="error"
        :closable="false"
        show-icon
        title="两端参数不一致"
        :description="session.divergence"
      />

      <CopyField class="gap-sm" :value="diagText" copy-text="" multiline />
      <p class="sp-hint" style="margin-top: 8px">
        反馈问题时附上此文本。其中同时包含界面声明与内核实际参数。
      </p>
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
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.checks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.check .ok {
  color: var(--sp-success);
}
.check .bad {
  color: var(--sp-danger);
}
.check-label {
  font-weight: 600;
  color: var(--sp-text-secondary);
  min-width: 72px;
}
.check-detail {
  color: var(--sp-text-muted);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nodes-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.nodes {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.node {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--sp-text-secondary);
  background: var(--sp-bg-sunken);
  padding: 6px 10px;
  border-radius: 6px;
}
.diag-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.diag-actions {
  display: flex;
  gap: 8px;
}
.tiny {
  font-size: 11.5px;
}
</style>
