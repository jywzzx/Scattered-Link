/**
 * 运行态：前端此刻连的是真后端还是演示数据。
 *
 * 三条判定路径，优先级从高到低：
 *   1. Electron 里跑 —— 问主进程要后端信息（主进程已经拉起过 Java 进程）
 *   2. 浏览器里跑 + 配了 VITE_API_BASE —— 直连该地址（方便单独调后端）
 *   3. 其余情况 —— 演示模式
 */
import { reactive } from 'vue'

export const backendState = reactive({
  mode: 'mock', // 'http' | 'mock' | 'down'
  baseUrl: '',
  token: '',
  error: '',
  source: '',
  resolved: false
})

function apply(info) {
  if (!info) return
  backendState.mode = info.mode || 'mock'
  backendState.baseUrl = info.baseUrl || ''
  backendState.token = info.token || ''
  backendState.error = info.error || ''
  backendState.source = info.source || ''
  backendState.resolved = true
}

let initPromise = null

export function initRuntime() {
  if (initPromise) return initPromise

  initPromise = (async () => {
    const bridge = typeof window !== 'undefined' ? window.sparsa : null

    if (bridge?.backend) {
      try {
        const info = await bridge.backend.info()
        apply(info)
        // 主进程可能比界面晚就绪，补一次异步刷新
        bridge.backend.onReady?.(apply)
        return backendState
      } catch (e) {
        apply({ mode: 'down', error: String(e?.message || e) })
        return backendState
      }
    }

    const base = import.meta.env.VITE_API_BASE
    if (base) {
      apply({ mode: 'http', baseUrl: base, token: import.meta.env.VITE_API_TOKEN || '', source: 'VITE_API_BASE' })
    } else {
      apply({ mode: 'mock', source: '浏览器直开，使用演示数据' })
    }
    return backendState
  })()

  return initPromise
}

export async function restartBackend() {
  const bridge = window.sparsa
  if (!bridge?.backend) return backendState
  const info = await bridge.backend.restart()
  apply(info)
  return backendState
}

export const isDesktop = () => !!window.sparsa
export const isMock = () => backendState.mode !== 'http'
