<script setup>
/**
 * 设置与关于。
 *
 * 后端连接状态放在这里而不是藏进菜单：演示模式与真后端的行为差异，
 * 必须在界面上说清楚，否则用户会以为「软件就是没功能」。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { backendState } from '@/api/runtime'
import { BRAND, PROTOCOL, fullName } from '@/config/brand'

const app = useAppStore()
const probe = ref(null)
const restarting = ref(false)
const appInfo = ref(null)

const modeText = computed(() => {
  if (backendState.mode === 'http') return '已连接 Java 后端'
  if (backendState.mode === 'down') return '后端启动失败'
  return '演示模式（未连接后端）'
})
const modeType = computed(() => (backendState.mode === 'http' ? 'success' : backendState.mode === 'down' ? 'danger' : 'warning'))

onMounted(async () => {
  await app.boot()
  if (window.sparsa?.backend) {
    probe.value = await window.sparsa.backend.probe()
    appInfo.value = await window.sparsa.app.info()
  }
})

async function restart() {
  restarting.value = true
  try {
    await app.restartBackend()
    probe.value = await window.sparsa?.backend?.probe?.()
    ElMessage.success('后端已重启')
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    restarting.value = false
  }
}

function openRepo() {
  if (window.sparsa?.shell) window.sparsa.shell.openExternal(BRAND.repo)
  else window.open(BRAND.repo, '_blank')
}
</script>

<template>
  <div class="sp-page">
    <h2 class="sp-page-title">设置</h2>
    <p class="sp-page-desc">查看后端连接状态与版本信息。</p>

    <div class="sp-card pad">
      <span class="sp-section-title">
        <el-icon><Link /></el-icon>
        后端连接
      </span>

      <div class="row">
        <span class="k">当前模式</span>
        <el-tag :type="modeType" effect="light">{{ modeText }}</el-tag>
      </div>
      <div class="row">
        <span class="k">接口地址</span>
        <span class="v sp-mono">{{ backendState.baseUrl || '无，演示模式不请求网络' }}</span>
      </div>
      <div class="row">
        <span class="k">来源</span>
        <span class="v sp-mono small">{{ backendState.source || '—' }}</span>
      </div>
      <div v-if="backendState.error" class="row">
        <span class="k">错误</span>
        <span class="v danger">{{ backendState.error }}</span>
      </div>

      <el-alert
        v-if="backendState.mode !== 'http'"
        class="gap-sm"
        type="info"
        :closable="false"
        show-icon
        title="当前界面使用演示数据"
        description="组网、成员列表与诊断信息需要 Java 后端。可点击重启后端重新加载。"
      />

      <div class="btns">
        <el-button :loading="restarting" @click="restart">
          <el-icon><RefreshRight /></el-icon>
          重启后端
        </el-button>
        <el-button plain @click="app.boot(true)">刷新系统信息</el-button>
      </div>

      <div v-if="probe" class="probe">
        <div class="probe-title">后端探测</div>
        <div class="probe-line">
          <span class="k">可执行文件</span>
          <span class="v sp-mono small">{{ probe.candidates?.length ? probe.candidates.join(' , ') : '未找到' }}</span>
        </div>
        <div class="probe-line">
          <span class="k">class 目录</span>
          <span class="v sp-mono small">{{ probe.classesDir || '未找到' }}</span>
        </div>

        <div v-if="probe.rejected?.length" class="rejected">
          <div class="rejected-title">未采用的启动器</div>
          <div v-for="r in probe.rejected" :key="r.path" class="rejected-item">
            <span class="v sp-mono small">{{ r.path }}</span>
            <span class="reason">{{ r.reason }}</span>
          </div>
          <p class="rejected-note">
            这些文件属于旧版界面入口。本程序只拉起 API 后端，不会启动旧界面。
          </p>
        </div>
      </div>
    </div>

    <div class="sp-card pad gap">
      <span class="sp-section-title">
        <el-icon><InfoFilled /></el-icon>
        关于
      </span>
      <div class="about">
        <div class="about-name">{{ fullName }}</div>
        <div class="about-tag">{{ BRAND.taglineEn }}</div>
      </div>

      <div class="rows">
        <div class="row">
          <span class="k">版本</span>
          <span class="v">{{ app.system?.appVersion || appInfo?.version || '1.0.0' }}</span>
        </div>
        <div class="row">
          <span class="k">协议标识</span>
          <span class="v sp-mono small">{{ PROTOCOL.networkName }} · 网段 {{ PROTOCOL.vnetPrefix }}0/24</span>
        </div>
        <div v-if="appInfo" class="row">
          <span class="k">运行环境</span>
          <span class="v sp-mono small">
            Electron {{ appInfo.electron }} · Chromium {{ appInfo.chrome }} · Node {{ appInfo.node }}
          </span>
        </div>
        <div class="row">
          <span class="k">项目主页</span>
          <el-button link type="primary" size="small" @click="openRepo">{{ BRAND.repo }}</el-button>
        </div>
      </div>

      <p class="license">
        网络内核 EasyTier v2.6.4（Apache-2.0）。界面基于 Vue 3 + Element Plus（MIT）。
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
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 7px 0;
  font-size: 12.5px;
  border-bottom: 1px dashed var(--sp-border);
}
.row:last-child {
  border-bottom: none;
}
.k {
  width: 92px;
  flex: 0 0 92px;
  color: var(--sp-text-muted);
}
.v {
  min-width: 0;
  color: var(--sp-text-secondary);
  word-break: break-all;
}
.v.danger {
  color: var(--sp-danger);
}
.small {
  font-size: 11.5px;
}
.btns {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
.probe {
  margin-top: 14px;
  padding: 10px 12px;
  background: var(--sp-bg-sunken);
  border-radius: 8px;
}
.probe-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sp-text-secondary);
  margin-bottom: 6px;
}
.probe-line {
  display: flex;
  gap: 10px;
  font-size: 11.5px;
  padding: 3px 0;
}
.rejected {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--sp-border);
}
.rejected-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--sp-warning);
  margin-bottom: 5px;
}
.rejected-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 3px 0;
}
.rejected-item .reason {
  font-size: 11px;
  color: var(--sp-text-muted);
}
.rejected-note {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--sp-text-muted);
  line-height: 1.6;
}
.about {
  margin-bottom: 8px;
}
.about-name {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.4px;
}
.about-tag {
  font-size: 11.5px;
  color: var(--sp-text-muted);
  margin-top: 3px;
}
.rows {
  margin-top: 6px;
}
.license {
  margin: 14px 0 0;
  font-size: 11px;
  color: var(--sp-text-muted);
  line-height: 1.7;
}
</style>
