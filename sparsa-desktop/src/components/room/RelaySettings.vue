<script setup>
/**
 * 链路参数：强制统一中继 / 中继节点 / 社区公共节点。
 *
 * 这三项是「连上但不通」的绝大多数来源，所以它们不是三个裸开关，而是一组带解释的联动：
 *   1. 强制中继开关 → 决定是否禁用打洞，只走一个节点
 *   2. 中继节点     → 强制中继时必须填，且**两端必须完全一致**
 *   3. 公共节点     → 与强制中继互斥（公共节点彼此独立，各挂一个等于没有通道）
 *
 * 组件自身不请求后端，只把状态往上抛，方便复用在创建/加入两个页面。
 */
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
  probing: { type: Boolean, default: false },
  showPublicNodes: { type: Boolean, default: true },
  /** 是否处于「运行中」，用于显示改动不生效的提醒 */
  running: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'recommend'])

const v = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

function patch(p) {
  emit('update:modelValue', { ...props.modelValue, ...p })
}

const modeText = computed(() => (v.value.noP2p ? '强制统一中继' : '直连优先'))
</script>

<template>
  <div class="relay">
    <div class="relay-head">
      <span class="sp-section-title" style="margin: 0">
        <el-icon><Link /></el-icon>
        链路设置
      </span>
      <el-tag :type="v.noP2p ? 'warning' : 'success'" size="small" effect="light" round>{{ modeText }}</el-tag>
    </div>

    <div class="row">
      <div class="row-label">
        <span>强制统一中继</span>
        <el-tooltip placement="top" :show-after="200">
          <template #content>
            <div class="tip-pop">
              开启后不再尝试打洞与直连，全部流量只走下面填写的这一个节点。<br />
              建链更快更稳，适合热点、校园网、运营商大内网。<br />
              <b>前提：两端必须填同一个节点地址。</b>
            </div>
          </template>
          <el-icon class="q"><QuestionFilled /></el-icon>
        </el-tooltip>
      </div>
      <div class="row-control">
        <el-switch
          :model-value="v.noP2p"
          :disabled="disabled"
          @update:model-value="(val) => patch(val
            ? { noP2p: true, usePublicNodes: false }
            : { noP2p: false })"
        />
        <span class="row-hint">{{ v.noP2p ? '只走中继，已禁用打洞' : '先尝试直连，失败再退到中继' }}</span>
      </div>
    </div>

    <div class="row">
      <div class="row-label">中继节点</div>
      <div class="row-control">
        <el-input
          :model-value="v.relayUri"
          :disabled="disabled"
          placeholder="tcp://节点地址:11010"
          class="relay-input"
          clearable
          @update:model-value="(val) => patch({ relayUri: val })"
        >
          <template #append>
            <el-button :loading="probing" :disabled="disabled" @click="emit('recommend')">
              {{ probing ? '探测中' : '填入推荐' }}
            </el-button>
          </template>
        </el-input>
        <span class="row-hint">
          {{ v.noP2p ? '必填。两端必须填完全相同的地址，否则彼此没有数据路径' : '留空表示只用直连。填写后作为直连失败时的备用通道。' }}
        </span>
      </div>
    </div>

    <div v-if="showPublicNodes" class="row">
      <div class="row-label">
        <span>社区公共节点</span>
        <el-tooltip placement="top" :show-after="200">
          <template #content>
            <div class="tip-pop">
              免费、免注册的公共中继，作为直连失败时的兜底。<br />
              与「强制统一中继」互斥：公共节点彼此独立，<br />
              各挂一个节点等于没有共同通道。
            </div>
          </template>
          <el-icon class="q"><QuestionFilled /></el-icon>
        </el-tooltip>
      </div>
      <div class="row-control">
        <el-switch
          :model-value="v.usePublicNodes"
          :disabled="disabled || v.noP2p"
          @update:model-value="(val) => patch({ usePublicNodes: val })"
        />
        <span class="row-hint">
          {{ v.noP2p ? '强制中继模式下不使用公共节点' : v.usePublicNodes ? '已启用，直连失败时经其转发' : '已关闭，仅尝试点对点直连' }}
        </span>
      </div>
    </div>

    <el-alert
      v-if="v.relayUri && !v.noP2p"
      type="info"
      :closable="false"
      show-icon
      class="mode-alert"
      title="当前按直连优先运行"
      description="已填写的中继节点仅作兜底通道。只走该节点需打开强制统一中继。"
    />

    <el-alert
      v-if="running"
      type="warning"
      :closable="false"
      show-icon
      class="restart-alert"
      title="参数改动不会作用于已运行的内核"
      description="内核启动时即固定命令行参数。新设置生效需先断开连接，再重新创建或加入房间。"
    />
  </div>
</template>

<style scoped>
.relay {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.relay-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.row {
  display: grid;
  grid-template-columns: 118px 1fr;
  gap: 12px;
  align-items: start;
}
.row-label {
  font-size: 12.5px;
  color: var(--sp-text-secondary);
  padding-top: 6px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.q {
  font-size: 13px;
  color: var(--sp-text-muted);
  cursor: help;
}
.row-control {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.row-hint {
  font-size: 11.5px;
  color: var(--sp-text-muted);
  line-height: 1.6;
}
.relay-input {
  max-width: 420px;
}
.restart-alert {
  margin-top: 2px;
}
.mode-alert {
  margin-top: 2px;
}
.tip-pop {
  max-width: 300px;
  line-height: 1.7;
  font-size: 12px;
}
</style>
