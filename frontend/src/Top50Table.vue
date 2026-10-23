<script setup>
import { compressionStatusLabels, fileTypeLabels, formatBytes, formatScore, granularityLabels } from './top50.js'

defineProps({ items: { type: Array, required: true }, showExactBytes: { type: Boolean, default: false }, showActions: { type: Boolean, default: false } })
const statusClass = value => Object.hasOwn(compressionStatusLabels, value) ? `status-pill--${value.toLowerCase()}` : ''
</script>

<template>
  <div class="table-scroll" tabindex="0" aria-label="Top50 榜单，可横向滚动">
    <table>
      <thead><tr><th>排名</th><th>文件标识</th><th>类型</th><th>文件大小</th><th>预测流量</th><th>预测分数</th><th>推荐理由</th><th>最近实际流量</th><th>压缩状态</th><th>模型版本</th><th>预测窗口</th><th v-if="showActions">操作</th></tr></thead>
      <tbody>
        <tr v-for="item in items" :key="`${item.predictionDate}-${item.appId}-${item.granularity}-${item.modelVersion}-${item.rank}`">
          <td><div class="rank-cell" :style="{ '--rank-heat': `${Math.max(18, 104 - item.rank * 8)}%` }"><strong>#{{ String(item.rank).padStart(2, '0') }}</strong><span><i></i></span></div></td>
          <td><div class="file-key" :title="item.fileKey">{{ item.fileKey }}</div><small class="app-id">APP {{ item.appId }}</small></td>
          <td><span class="type-tag">{{ fileTypeLabels[item.fileType] || item.fileType || '未知' }}</span></td>
          <td class="number-cell" :title="item.fileSizeBytes == null ? '大小未知' : `${item.fileSizeBytes} B`"><slot name="file-size" :item="item">{{ formatBytes(item.fileSizeBytes) }}</slot></td>
          <td class="number-cell number-cell--primary" :title="`${item.predictedBytes} B（目标窗口累计）`">{{ formatBytes(item.predictedBytes) }}<small v-if="showExactBytes" class="exact-bytes">{{ item.predictedBytes }} B</small></td>
          <td class="score-cell">{{ formatScore(item.score) }}</td>
          <td><p class="reason" :title="item.reason || ''">{{ item.reason || '—' }}</p></td>
          <td class="number-cell" :title="item.recentActualBytes == null ? '实际流量未知' : `${item.recentActualBytes} B`">{{ formatBytes(item.recentActualBytes) }}</td>
          <td><span class="status-pill" :class="statusClass(item.compressionStatus)">{{ compressionStatusLabels[item.compressionStatus] || item.compressionStatus || '未知' }}</span></td>
          <td><code>{{ item.modelVersion }}</code></td>
          <td><strong class="date-cell">{{ item.predictionDate }}</strong><small v-if="showExactBytes">至 {{ item.predictionEndDate }}<br>{{ item.granularity === 'WEEKLY' ? '连续 7 天累计' : '下一自然日' }}</small><small v-else>{{ granularityLabels[item.granularity] || item.granularity }}预测</small></td>
          <td v-if="showActions"><slot name="actions" :item="item" /></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.exact-bytes { display: block; margin-top: 0.45rem; font-size: 0.65rem; white-space: normal; overflow-wrap: anywhere; color: #8facad; }
.table-scroll table { min-width: 1450px; }
.file-key { width: 220px; }
th, td { padding: 12px 13px; }
td > small { display: block; margin-top: 5px; color: #8b9bb0; font-size: 9px; }
td > code { font-size: 10px; }
.reason { max-width: 190px; }
</style>
