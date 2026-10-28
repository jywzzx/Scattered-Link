<script setup>
/**
 * 主布局：左侧导航 + 顶部状态条 + 内容区 + 底部状态栏。
 *
 * 布局职责边界：
 *   这里只放「所有页面都需要」的东西（导航、全局状态、断开操作）。
 *   页面自己的按钮与表单留在各自 view 里，避免布局变成全局变量的堆放处。
 */
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useSessionStore } from '@/stores/session'
import { BRAND } from '@/config/brand'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const session = useSessionStore()

const NAV = [
  { path: '/home', title: '开始', icon: 'HomeFilled' },
  { path: '/host', title: '创建房间', icon: 'Plus' },
  { path: '/join', title: '加入房间', icon: 'Connection' },
  { path: '/phone', title: '手机端配置', icon: 'Iphone' },
  { path: '/diagnostics', title: '网络诊断', icon: 'FirstAidKit' },
  { path: '/logs', title: '运行日志', icon: 'Document' },
  { path: '/settings', title: '设置', icon: 'Setting' }
]

const pageTitle = computed(() => route.meta?.title || '')
const statusText = computed(() => {
  if (!session.active) return '未连接'
  return session.isHost ? '已建房 · 等待接入' : '已连接'
})
const statusType = computed(() => {
  if (!session.active) return 'info'
  if (session.isHost) return session.hasOthers ? 'success' : 'warning'
  return 'success'
})

const backendLabel = computed(() => {
  if (app.backendMode === 'http') return '后端已连接'
  if (app.backendMode === 'down') return '后端异常'
  return '演示模式'
})
const backendDotClass = computed(() => {
  if (app.backendMode === 'http') return 'is-ok'
  if (app.backendMode === 'down') return 'is-bad'
  return 'is-mock'
})

async function onStop() {
  try {
    await ElMessageBox.confirm('断开后双方无法继续通信。恢复连接需重新建房或加入。', '断开虚拟局域网', {
      confirmButtonText: '断开',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await session.stop()
    ElMessage.success('已断开')
    router.push('/home')
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  }
}

// 主进程菜单「编辑手机端配置 / 导出诊断信息」会推导航指令过来
let offNavigate = null
onMounted(() => {
  offNavigate = window.sparsa?.onNavigate?.((path) => {
    if (path) router.push(path)
  })
})
onBeforeUnmount(() => {
  offNavigate?.()
  session.stopPolling()
})
</script>

<template>
  <div class="layout">
    <aside class="sider">
      <div class="brand">
        <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
          <path d="M8 22 L16 9 L24 22" fill="none" stroke="url(#g)" stroke-width="1.6" stroke-linecap="round" />
          <path d="M8 22 L24 22" fill="none" stroke="url(#g)" stroke-width="1.6" stroke-linecap="round" opacity="0.45" />
          <circle cx="16" cy="9" r="2.6" fill="#7c5cff" />
          <circle cx="8" cy="22" r="2" fill="#3b6ef5" />
          <circle cx="24" cy="22" r="2" fill="#3b6ef5" />
          <defs>
            <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#3b6ef5" />
              <stop offset="100%" stop-color="#7c5cff" />
            </linearGradient>
          </defs>
        </svg>
        <div class="brand-text">
          <div class="brand-name">{{ BRAND.name }}</div>
          <div class="brand-sub">{{ BRAND.nameZh }} · 虚拟局域网</div>
        </div>
      </div>

      <el-menu :default-active="route.path" router class="nav">
        <el-menu-item v-for="item in NAV" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>

      <div class="sider-foot">
        <span class="dot" :class="backendDotClass" />
        <span>{{ backendLabel }}</span>
        <el-tooltip v-if="app.isMock" content="未检测到 Java 后端，界面数据为演示内容" placement="top">
          <el-icon class="foot-hint"><InfoFilled /></el-icon>
        </el-tooltip>
      </div>
    </aside>

    <div class="main-wrap">
      <header class="header">
        <div class="header-left">
          <h1 class="header-title">{{ pageTitle }}</h1>
          <span v-if="session.myIp" class="header-ip sp-mono">{{ session.myIp }}</span>
        </div>
        <div class="header-right">
          <el-tag :type="statusType" effect="light" round size="small">
            {{ statusText }}
          </el-tag>
          <el-button v-if="session.active" size="small" plain @click="onStop">断开连接</el-button>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>

      <footer class="statusbar">
        <span>{{ BRAND.name }} {{ app.system?.appVersion || '1.0.0' }}</span>
        <el-divider direction="vertical" />
        <span>成员 {{ session.memberCount }}</span>
        <el-divider direction="vertical" />
        <span v-if="session.session?.noP2p" class="warn-text">强制统一中继</span>
        <span v-else>直连优先</span>
        <span class="sp-spacer" />
        <span v-if="session.session?.relayUri" class="sp-mono muted">中继 {{ session.session.relayUri }}</span>
        <span v-if="app.isMock" class="mock-badge">演示数据</span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
  background: var(--sp-bg);
}

.sider {
  width: var(--sp-sider-w);
  flex: 0 0 var(--sp-sider-w);
  background: #fbfcfe;
  border-right: 1px solid var(--sp-border);
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 18px 14px;
}
.brand-mark {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
}
.brand-name {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.4px;
  line-height: 1.1;
}
.brand-sub {
  font-size: 11px;
  color: var(--sp-text-muted);
  margin-top: 3px;
}

.nav {
  border-right: none;
  background: transparent;
  padding: 4px 10px;
  flex: 1;
}
.nav :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  border-radius: 8px;
  margin-bottom: 2px;
  font-size: 13px;
  color: var(--sp-text-secondary);
}
.nav :deep(.el-menu-item:hover) {
  background: #eef2f9;
}
.nav :deep(.el-menu-item.is-active) {
  background: var(--sp-primary-weak);
  color: var(--sp-primary);
  font-weight: 600;
}

.sider-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 18px 14px;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  border-top: 1px solid var(--sp-border);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sp-text-muted);
}
.dot.is-ok {
  background: var(--sp-success);
}
.dot.is-bad {
  background: var(--sp-danger);
}
.dot.is-mock {
  background: var(--sp-warning);
}
.foot-hint {
  margin-left: auto;
  cursor: help;
}

.main-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.header {
  height: var(--sp-header-h);
  flex: 0 0 var(--sp-header-h);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: var(--sp-bg-elevated);
  border-bottom: 1px solid var(--sp-border);
}
.header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.header-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}
.header-ip {
  font-size: 11.5px;
  color: var(--sp-text-muted);
}
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.statusbar {
  height: var(--sp-status-h);
  flex: 0 0 var(--sp-status-h);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 16px;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  background: var(--sp-bg-elevated);
  border-top: 1px solid var(--sp-border);
}
.statusbar .warn-text {
  color: var(--sp-warning);
  font-weight: 600;
}
.muted {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mock-badge {
  margin-left: 10px;
  padding: 1px 7px;
  border-radius: 999px;
  background: #fff4e0;
  color: var(--sp-warning);
  font-size: 10.5px;
}
</style>
