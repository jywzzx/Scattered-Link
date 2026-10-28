<script setup>
/**
 * 成员表。
 *
 * 只负责「把 peersDetailed() 的结果画出来」，不含业务动作 ——
 * 连通性测试、断开等按钮由所在页面提供，避免组件变成万能面板。
 */
import { computed } from 'vue'

const props = defineProps({
  members: { type: Array, default: () => [] },
  showSelf: { type: Boolean, default: true },
  emptyText: { type: String, default: '暂无其他成员接入' },
  emptyHint: { type: String, default: '对方接入后显示其虚拟 IP。游戏联机时填写该地址。' },
  /** 是否在每行显示「测试连通」按钮 */
  testable: { type: Boolean, default: false }
})

const emit = defineEmits(['test'])

function linkMeta(cost) {
  const c = String(cost || '').toLowerCase()
  if (c === 'local') return { text: '本机', type: 'info' }
  if (c === 'p2p') return { text: '直连', type: 'success' }
  if (c.includes('relay')) return { text: '中继', type: 'warning' }
  return { text: cost || '—', type: 'info' }
}

const rows = computed(() => (props.showSelf ? props.members : props.members.filter((m) => !m.self)))

const hasOthers = computed(() => props.members.some((m) => !m.self))
</script>

<template>
  <el-table v-if="rows.length" :data="rows" size="small" class="members" empty-text="暂无成员">
    <el-table-column label="虚拟 IP" min-width="132">
      <template #default="{ row }">
        <span class="ip sp-mono">{{ row.ipv4 }}</span>
        <el-tag v-if="row.self" size="small" effect="plain" class="self-tag">本机</el-tag>
      </template>
    </el-table-column>

    <el-table-column prop="hostname" label="主机名" min-width="130" show-overflow-tooltip>
      <template #default="{ row }">
        <span class="host">{{ row.hostname || '—' }}</span>
      </template>
    </el-table-column>

    <el-table-column label="链路" width="88">
      <template #default="{ row }">
        <el-tag :type="linkMeta(row.cost).type" size="small" effect="light" round>
          {{ linkMeta(row.cost).text }}
        </el-tag>
      </template>
    </el-table-column>

    <el-table-column label="延迟" width="92" align="right">
      <template #default="{ row }">
        <span class="sp-mono num">{{ row.latMs && row.latMs !== '-' ? row.latMs + ' ms' : '—' }}</span>
      </template>
    </el-table-column>

    <el-table-column label="丢包" width="82" align="right">
      <template #default="{ row }">
        <span class="sp-mono num">{{ row.lossRate || '—' }}</span>
      </template>
    </el-table-column>

    <el-table-column label="隧道" width="76">
      <template #default="{ row }">
        <span class="sp-mono num">{{ row.tunnel && row.tunnel !== '-' ? row.tunnel : '—' }}</span>
      </template>
    </el-table-column>

    <el-table-column label="可达" width="86">
      <template #default="{ row }">
        <span v-if="row.self" class="muted">—</span>
        <el-tag v-else-if="row.reachable === true" type="success" size="small" effect="plain">
          {{ row.pingMs != null ? row.pingMs + 'ms' : '可达' }}
        </el-tag>
        <el-tag v-else-if="row.reachable === false" type="danger" size="small" effect="plain">不通</el-tag>
        <span v-else class="muted">未测</span>
      </template>
    </el-table-column>

    <el-table-column v-if="testable" label="连通测试" width="92">
      <template #default="{ row }">
        <el-button v-if="!row.self" link type="primary" size="small" @click="emit('test', row.ipv4)">
          测试连通
        </el-button>
        <span v-else class="muted">—</span>
      </template>
    </el-table-column>
  </el-table>

  <el-empty v-else :description="emptyText" :image-size="72" class="members-empty">
    <p class="hint">{{ emptyHint }}</p>
  </el-empty>

  <p v-if="rows.length && !hasOthers" class="wait-hint">
    <el-icon><Loading /></el-icon>
    等待对方接入。房主需保持程序运行。
  </p>
</template>

<style scoped>
.members {
  width: 100%;
}
.ip {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--sp-text);
}
.self-tag {
  margin-left: 6px;
  transform: scale(0.88);
}
.host {
  font-size: 12.5px;
  color: var(--sp-text-secondary);
}
.num {
  font-size: 12px;
  color: var(--sp-text-secondary);
}
.muted {
  color: var(--sp-text-muted);
  font-size: 12px;
}
.members-empty {
  padding: 8px 0 4px;
}
.hint {
  margin: 0;
  font-size: 12px;
  color: var(--sp-text-muted);
  line-height: 1.7;
}
.wait-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--sp-text-muted);
}
</style>
