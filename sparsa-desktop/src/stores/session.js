/**
 * 会话状态：建房 / 加入 / 成员 / 停止。
 *
 * 关键设计：**内核一旦启动，参数就固定了**。
 * 所以界面上「强制中继」「中继节点」这类开关只在建房前生效，
 * 运行中改动必须提示「需停止后重新建房」，否则两端参数不一致、互相看不到。
 * 这是旧版最常踩的坑，store 里用 needsRestart 与 divergence 两个字段把它显性化。
 */
import { defineStore } from 'pinia'
import { memberApi, sessionApi } from '@/api'

const POLL_MS = 2000

export const useSessionStore = defineStore('session', {
  state: () => ({
    session: null,
    members: [],
    loading: false,
    error: '',
    lastPing: null,
    // ip -> 延迟（null 表示通但没取到数值），只保留本轮会话的实测结果
    pingResults: {},
    pollTimer: null,
    // 建房时使用的参数快照，用于检测「界面改了但内核没跟上」
    launchedOptions: null
  }),

  getters: {
    active: (s) => !!s.session?.active,
    role: (s) => s.session?.role || null,
    isHost: (s) => s.session?.role === 'HOST',
    isJoiner: (s) => s.session?.role === 'JOINER',
    myIp: (s) => s.session?.myIp || '',
    invite: (s) => s.session?.invite || null,
    inviteCode: (s) => s.session?.invite?.code || '',
    memberCount: (s) => s.members.length,
    others: (s) => s.members.filter((m) => !m.self),
    hasOthers: (s) => s.members.some((m) => !m.self),
    /** 内核实际生效的参数（后端记录的真实命令行，不是界面上的勾选值） */
    kernel: (s) => s.session?.kernel || null,
    /** 后端给出的「界面与内核不一致」提示，非空即需要重启 */
    divergence: (s) => s.session?.divergence || '',
    /** 强制中继模式下必须始终有可用节点，否则两端会失去唯一通道 */
    relayMissing: (s) => !!s.session?.noP2p && !s.session?.relayUri
  },

  actions: {
    /**
     * 把连通性测试的结果并进成员列表。
     *
     * 后端只返回 EasyTier 的链路统计，不返回「我们实测的可达性」；
     * 而成员列表每 2 秒整体替换一次，若不做合并，刚测出的结果会在两秒后
     * 变回「未测」，界面就出现「上面说连通、下面还是未测」的矛盾。
     */
    withPingResult(list) {
      return (list || []).map((m) => {
        const hit = Object.prototype.hasOwnProperty.call(this.pingResults, m.ipv4)
        if (!hit) return m
        return { ...m, reachable: true, pingMs: this.pingResults[m.ipv4] }
      })
    },

    async refresh() {
      const [sess, mem] = await Promise.all([sessionApi.current(), memberApi.list()])
      this.session = sess
      this.members = this.withPingResult(mem.members)
      return sess
    },

    async host(options) {
      this.loading = true
      this.error = ''
      this.pingResults = {}
      try {
        const sess = await sessionApi.host(options)
        this.launchedOptions = { ...options }
        this.session = sess
        await this.refreshMembers()
        this.startPolling()
        return sess
      } catch (e) {
        this.error = String(e?.message || e)
        throw e
      } finally {
        this.loading = false
      }
    },

    async join(payload) {
      this.loading = true
      this.error = ''
      this.pingResults = {}
      try {
        const sess = await sessionApi.join(payload)
        this.launchedOptions = { ...payload }
        this.session = sess
        await this.refreshMembers()
        this.startPolling()
        return sess
      } catch (e) {
        this.error = String(e?.message || e)
        throw e
      } finally {
        this.loading = false
      }
    },

    async stop() {
      this.stopPolling()
      await sessionApi.stop()
      this.session = null
      this.members = []
      this.lastPing = null
      this.pingResults = {}
      this.launchedOptions = null
    },

    async refreshMembers() {
      try {
        const mem = await memberApi.list()
        this.members = this.withPingResult(mem.members)
      } catch {
        /* 成员列表刷新失败不打断主流程，下一轮轮询会补上 */
      }
    },

    /** 连通性测试成功后调用，把结果写回对应成员行 */
    markReachable(ip, ms) {
      if (!ip) return
      this.pingResults = { ...this.pingResults, [ip]: ms == null ? null : ms }
      this.members = this.withPingResult(this.members)
    },

    async ping(ip) {
      const r = await sessionApi.ping(ip)
      this.lastPing = r
      if (r.ok && r.target) this.markReachable(r.target, r.ms)
      return r
    },

    async recheckPublicIp() {
      const r = await sessionApi.recheckPublicIp()
      await this.refresh()
      return r
    },

    startPolling() {
      this.stopPolling()
      this.pollTimer = setInterval(async () => {
        if (!this.active) return
        try {
          await this.refresh()
        } catch {
          /* 轮询失败保持上次状态，避免界面闪烁 */
        }
      }, POLL_MS)
    },

    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },

    /**
     * 运行中修改启动参数 → 只改界面、不重启内核，会让两端参数不一致。
     * 这里做一次显式比对，返回人类可读的告警文案（无差异返回空串）。
     */
    checkNeedsRestart(current) {
      const base = this.launchedOptions
      if (!this.active || !base || !current) return ''
      const diffs = []
      if (!!current.noP2p !== !!base.noP2p) {
        diffs.push(`强制统一中继：已改为「${current.noP2p ? '开启' : '关闭'}」`)
      }
      if ((current.relayUri || '') !== (base.relayUri || '')) {
        diffs.push(`中继节点：已改为「${current.relayUri || '留空'}」`)
      }
      if (!!current.usePublicNodes !== !!base.usePublicNodes) {
        diffs.push(`社区公共节点：已改为「${current.usePublicNodes ? '开启' : '关闭'}」`)
      }
      const tail = this.isHost ? '创建房间' : '加入房间'
      return diffs.length ? `${diffs.join('，')}。内核仍在原参数下运行。停止后重新${tail}。` : ''
    }
  }
})
