/**
 * 接口层入口。
 *
 * 所有页面只调用本文件导出的领域方法，不直接碰 axios、也不判断当前是 mock 还是 http。
 * 需要新增接口时，只在对应领域对象里加一个方法，并在 mock.js 的路由表里补一条 —— 两处而已。
 *
 * 后端契约见 docs/02-前后端接口契约.md，路径前缀 /api/v1。
 */
import axios from 'axios'
import { backendState, initRuntime } from './runtime'
import * as mock from './mock'

const PREFIX = '/api/v1'

let http = null

function client() {
  if (http) return http
  http = axios.create({
    baseURL: (backendState.baseUrl || '') + PREFIX,
    timeout: 20000,
    headers: backendState.token ? { 'X-Sparsa-Token': backendState.token } : {}
  })

  http.interceptors.response.use(
    (res) => res,
    (err) => {
      const data = err.response?.data
      const msg =
        data?.message ||
        data?.error ||
        (err.code === 'ECONNABORTED'
          ? '后端响应超时'
          : err.code === 'ERR_NETWORK'
            ? '后端未启动或已退出，可在「设置」里重启后端'
            : err.message)
      return Promise.reject(new Error(msg))
    }
  )
  return http
}

/**
 * 统一请求入口。
 * GET/DELETE 走 params，POST/PUT 走 body —— 与 Java 侧 HttpExchange 的读取方式对应。
 */
export async function request(method, path, { body, query } = {}) {
  await initRuntime()

  if (backendState.mode !== 'http') {
    return mock.handle(method, path, { body, query })
  }

  const res = await client().request({
    method,
    url: path,
    params: query,
    data: body
  })
  return res.data
}

/* ── 系统 ─────────────────────────────────────────────── */
export const systemApi = {
  info: () => request('GET', '/system/info'),
  preflight: () => request('GET', '/system/preflight'),
  network: () => request('GET', '/system/network'),
  publicNodes: (limit = 3) => request('GET', '/system/public-nodes', { query: { limit } }),
  addFirewall: () => request('POST', '/system/firewall')
}

/* ── 会话 ─────────────────────────────────────────────── */
export const sessionApi = {
  current: () => request('GET', '/session'),
  host: (payload) => request('POST', '/session/host', { body: payload }),
  join: (payload) => request('POST', '/session/join', { body: payload }),
  stop: () => request('DELETE', '/session'),
  ping: (ip) => request('POST', '/session/ping', { body: ip ? { ip } : {} }),
  recheckPublicIp: () => request('POST', '/session/public-ip')
}

/* ── 成员 ─────────────────────────────────────────────── */
export const memberApi = {
  list: () => request('GET', '/members')
}

/* ── 日志 ─────────────────────────────────────────────── */
export const logApi = {
  tail: (n = 200) => request('GET', '/logs', { query: { tail: n } })
}

/* ── 手机端配置 ───────────────────────────────────────── */
export const phoneApi = {
  profile: (params) => request('GET', '/phone/profile', { query: params }),
  parseConfig: (text) => request('POST', '/config/parse', { body: { text } })
}

/* ── 解析与诊断 ───────────────────────────────────────── */
export const miscApi = {
  parseInvite: (text) => request('POST', '/invite/parse', { body: { text } }),
  diagnostics: () => request('GET', '/diagnostics')
}

export { backendState, initRuntime, mock }
