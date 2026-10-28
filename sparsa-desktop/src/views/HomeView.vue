<script setup>
/**
 * 首页：二选一入口 + 环境体检结果。
 *
 * 对应 Swing 的 buildHomeCard。区别是把「环境有问题」从弹窗改成常驻横幅 ——
 * 弹窗会被条件反射地点掉，而缺管理员权限/缺内核是必须先解决的问题。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useSessionStore } from '@/stores/session'
import { BRAND, PROTOCOL } from '@/config/brand'

const router = useRouter()
const app = useAppStore()
const session = useSessionStore()

const blockers = computed(() => app.blockers)

const envRows = computed(() => [
  { label: '管理员权限', value: app.system?.admin ? '已提权' : '未提权', ok: !!app.system?.admin },
  { label: '内核文件', value: app.system?.coreFound ? '就位' : '缺失', ok: !!app.system?.coreFound },
  { label: '局域网 IP', value: app.network?.lanIp || '—', ok: !!app.network?.lanIp },
  { label: '公网 IP', value: app.network?.publicIp || '探测中', ok: !!app.network?.publicIp },
  {
    label: 'NAT 类型',
    value: app.network?.nat?.udp || '未知',
    ok: !app.network?.hardNat,
    warn: !!app.network?.hardNat
  }
])

const continueTo = computed(() => (session.isHost ? '/host' : '/join'))
</script>

<template>
  <div class="sp-page">
    <section class="hero">
      <div class="hero-mark">
        <svg viewBox="0 0 120 64" aria-hidden="true">
          <g stroke="url(#hg)" stroke-width="1.4" fill="none" opacity="0.9">
            <path d="M18 48 L44 16 L70 48" />
            <path d="M44 16 L92 26" />
            <path d="M70 48 L92 26" />
            <path d="M70 48 L104 46" opacity="0.5" />
            <path d="M18 48 L6 34" opacity="0.35" />
          </g>
          <g fill="#3b6ef5">
            <circle cx="18" cy="48" r="3" />
            <circle cx="70" cy="48" r="3" />
            <circle cx="92" cy="26" r="3" />
          </g>
          <circle cx="44" cy="16" r="4" fill="#7c5cff" />
          <circle cx="104" cy="46" r="2" fill="#9aa4b6" />
          <circle cx="6" cy="34" r="2" fill="#9aa4b6" />
          <defs>
            <linearGradient id="hg" x1="0" y1="1" x2="1" y2="0">
              <stop offset="0%" stop-color="#3b6ef5" />
              <stop offset="100%" stop-color="#7c5cff" />
            </linearGradient>
          </defs>
        </svg>
      </div>
      <h2 class="hero-title">{{ BRAND.name }} <span class="hero-zh">{{ BRAND.nameZh }}</span></h2>
      <p class="hero-en">{{ BRAND.taglineEn }}</p>
    </section>

    <el-alert
      v-for="(b, i) in blockers"
      :key="i"
      class="blocker"
      :type="b.level === 'error' ? 'error' : 'warning'"
      :title="b.text"
      :closable="false"
      show-icon
    />

    <div v-if="session.active" class="continue-card">
      <div>
        <div class="continue-title">{{ session.isHost ? '房间已创建，等待对方接入' : '已连接到房间' }}</div>
        <div class="continue-sub">
          本机虚拟 IP <span class="sp-mono">{{ session.myIp }}</span>
          · 成员 {{ session.memberCount }} 人
        </div>
      </div>
      <el-button type="primary" @click="router.push(continueTo)">
        {{ session.isHost ? '查看房间' : '查看连接' }}
      </el-button>
    </div>

    <div class="options">
      <button class="option" type="button" @click="router.push('/host')">
        <div class="option-icon option-icon--host">
          <el-icon><Promotion /></el-icon>
        </div>
        <div class="option-body">
          <div class="option-title">创建房间</div>
          <div class="option-desc">
            本机作为房主生成邀请码。对方凭邀请码接入，房主需保持程序运行。
          </div>
        </div>
        <el-icon class="option-arrow"><ArrowRight /></el-icon>
      </button>

      <button class="option" type="button" @click="router.push('/join')">
        <div class="option-icon option-icon--join">
          <el-icon><Connection /></el-icon>
        </div>
        <div class="option-body">
          <div class="option-title">加入房间</div>
          <div class="option-desc">
            粘贴对方发来的邀请码，或粘贴手机扫码得到的配置。加入后双方处于同一虚拟局域网。
          </div>
        </div>
        <el-icon class="option-arrow"><ArrowRight /></el-icon>
      </button>
    </div>

    <div class="env sp-card">
      <div class="env-head">
        <span class="sp-section-title" style="margin: 0">
          <el-icon><Monitor /></el-icon>
          本机环境
        </span>
        <el-button link type="primary" size="small" @click="app.boot(true)">重新检测</el-button>
      </div>
      <div class="env-grid">
        <div v-for="r in envRows" :key="r.label" class="env-item">
          <span class="env-label">{{ r.label }}</span>
          <span class="env-value" :class="{ bad: !r.ok, warn: r.warn }">{{ r.value }}</span>
        </div>
      </div>
      <p class="env-note">
        虚拟局域网建立后，成员获得 {{ PROTOCOL.vnetPrefix }}x 的地址。游戏内选择局域网联机时填写该地址。
      </p>
    </div>
  </div>
</template>

<style scoped>
.hero {
  text-align: center;
  padding: 18px 0 26px;
}
.hero-mark svg {
  width: 132px;
  height: 70px;
}
.hero-title {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 1px;
  margin: 4px 0 6px;
}
.hero-zh {
  font-size: 17px;
  font-weight: 500;
  color: var(--sp-text-muted);
  letter-spacing: 2px;
}
.hero-sub {
  margin: 0;
  font-size: 13.5px;
  color: var(--sp-text-secondary);
}
.hero-en {
  margin: 5px 0 0;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  letter-spacing: 0.4px;
}

.blocker {
  margin-bottom: 12px;
}

.continue-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  margin-bottom: 16px;
  border-radius: var(--sp-radius-lg);
  background: var(--sp-primary-weak);
  border: 1px solid #d5e0ff;
}
.continue-title {
  font-size: 13.5px;
  font-weight: 600;
}
.continue-sub {
  font-size: 12px;
  color: var(--sp-text-secondary);
  margin-top: 3px;
}

.options {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}
.option {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 20px;
  text-align: left;
  background: var(--sp-bg-elevated);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-lg);
  cursor: pointer;
  transition: border-color 0.16s, box-shadow 0.16s, transform 0.16s;
  font: inherit;
  color: inherit;
}
.option:hover {
  border-color: #b9caf5;
  box-shadow: var(--sp-shadow);
  transform: translateY(-1px);
}
.option:active {
  transform: translateY(0);
}
.option-icon {
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
}
.option-icon--host {
  background: var(--sp-primary-weak);
  color: var(--sp-primary);
}
.option-icon--join {
  background: #f2edff;
  color: var(--sp-star);
}
.option-body {
  min-width: 0;
}
.option-title {
  font-size: 14.5px;
  font-weight: 600;
  margin-bottom: 5px;
}
.option-desc {
  font-size: 12px;
  color: var(--sp-text-muted);
  line-height: 1.75;
}
.option-arrow {
  margin-left: auto;
  color: var(--sp-border-strong);
  align-self: center;
}

.env {
  padding: 16px 18px;
}
.env-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.env-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px 18px;
}
.env-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--sp-border);
}
.env-label {
  color: var(--sp-text-muted);
}
.env-value {
  color: var(--sp-text);
  font-weight: 600;
}
.env-value.bad {
  color: var(--sp-danger);
}
.env-value.warn {
  color: var(--sp-warning);
}
.env-note {
  margin: 14px 0 0;
  font-size: 11.5px;
  color: var(--sp-text-muted);
  line-height: 1.7;
}
</style>
