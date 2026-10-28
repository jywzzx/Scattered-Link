/**
 * 应用级状态：后端连接、系统信息、预检、网络环境。
 *
 * 只放「全局一次就够」的数据。会话与成员在 session store，避免这里变成垃圾桶。
 */
import { defineStore } from 'pinia'
import { systemApi } from '@/api'
import { backendState, initRuntime, restartBackend } from '@/api/runtime'

export const useAppStore = defineStore('app', {
  state: () => ({
    booted: false,
    booting: false,
    system: null,
    preflight: null,
    network: null,
    publicNodes: null,
    lastError: ''
  }),

  getters: {
    isMock: (s) => backendState.mode !== 'http',
    backendMode: () => backendState.mode,
    backendError: () => backendState.error,
    isAdmin: (s) => !!s.system?.admin,
    coreFound: (s) => !!s.system?.coreFound,
    engineReady: (s) => !!s.system?.engineReady,
    /** 环境层面必须先解决的问题，首页顶部用来提示 */
    blockers: (s) => {
      const out = []
      if (s.system && !s.system.admin) out.push({ level: 'error', text: '未以管理员身份运行，无法创建虚拟网卡' })
      if (s.system && !s.system.coreFound) out.push({ level: 'error', text: '未找到内核文件 easytier-core.exe' })
      if (s.system && !s.system.cliFound) out.push({ level: 'warn', text: '未找到 easytier-cli.exe，成员列表将不可用' })
      if (s.preflight && !s.preflight.ok) out.push({ level: 'error', text: '启动预检未通过。查看网络诊断页。' })
      if (backendState.mode === 'down') out.push({ level: 'warn', text: `后端未就绪。${backendState.error}` })
      return out
    }
  },

  actions: {
    async boot(force = false) {
      if (this.booted && !force) return
      this.booting = true
      this.lastError = ''
      try {
        await initRuntime()
        const [info, preflight, network] = await Promise.allSettled([
          systemApi.info(),
          systemApi.preflight(),
          systemApi.network()
        ])
        if (info.status === 'fulfilled') this.system = info.value
        if (preflight.status === 'fulfilled') this.preflight = preflight.value
        if (network.status === 'fulfilled') this.network = network.value
        this.booted = true
      } catch (e) {
        this.lastError = String(e?.message || e)
      } finally {
        this.booting = false
      }
    },

    async refreshNetwork() {
      this.network = await systemApi.network()
      return this.network
    },

    async refreshPreflight() {
      this.preflight = await systemApi.preflight()
      return this.preflight
    },

    async loadPublicNodes(limit = 3) {
      this.publicNodes = await systemApi.publicNodes(limit)
      return this.publicNodes
    },

    async addFirewall() {
      return systemApi.addFirewall()
    },

    async restartBackend() {
      const info = await restartBackend()
      await this.boot(true)
      return info
    }
  }
})
