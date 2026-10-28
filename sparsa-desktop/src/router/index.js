/**
 * 路由表。
 *
 * 用 hash 模式：打包后 Electron 通过 file:// 加载，history 模式会 404。
 *
 * 路由与 Swing 版的功能区一一对应（对照表见 docs/03-从Swing到Vue迁移对照.md）：
 *   /home        首页二选一        ← buildHomeCard
 *   /host        创建房间          ← buildHostCard
 *   /join        加入房间          ← buildJoinCard
 *   /phone       手机端配置        ← buildRelayCard + QrCard
 *   /diagnostics 网络诊断          ← buildDiagnostics + detectNetworkAsync
 *   /logs        运行日志          ← buildLogPanel
 */
import { createRouter, createWebHashHistory } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

const routes = [
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '', redirect: '/home' },
      {
        path: 'home',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '开始', icon: 'HomeFilled' }
      },
      {
        path: 'host',
        name: 'host',
        component: () => import('@/views/HostView.vue'),
        meta: { title: '创建房间', icon: 'Plus' }
      },
      {
        path: 'join',
        name: 'join',
        component: () => import('@/views/JoinView.vue'),
        meta: { title: '加入房间', icon: 'Connection' }
      },
      {
        path: 'phone',
        name: 'phone',
        component: () => import('@/views/PhoneView.vue'),
        meta: { title: '手机端配置', icon: 'Iphone' }
      },
      {
        path: 'diagnostics',
        name: 'diagnostics',
        component: () => import('@/views/DiagnosticsView.vue'),
        meta: { title: '网络诊断', icon: 'FirstAidKit' }
      },
      {
        path: 'logs',
        name: 'logs',
        component: () => import('@/views/LogsView.vue'),
        meta: { title: '运行日志', icon: 'Document' }
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { title: '设置', icon: 'Setting' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · Sparsa 零星` : 'Sparsa 零星'
})

export default router
