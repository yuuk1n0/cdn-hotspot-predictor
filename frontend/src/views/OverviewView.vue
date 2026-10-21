<script setup>
import { computed, inject, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import MetricCard from '../components/MetricCard.vue'
import LineChart from '../components/LineChart.vue'
import DonutChart from '../components/DonutChart.vue'
import HistoryPanel from '../HistoryPanel.vue'
import { uiContextKey } from '../ui/context.js'
import { appOptions, chartDates, getMockFiles, mockApps, mockDateRange } from '../ui/mock-data.js'
import { compressionStatusLabels, fileTypeLabels, formatBytes } from '../top50.js'

const { previewAppId, selectedDataset } = inject(uiContextKey, { previewAppId: ref('10350'), selectedDataset: ref(null) })
const mode = ref(selectedDataset.value ? 'api' : 'mock')
const granularity = ref('DAILY')
const startDate = ref(mockDateRange.start)
const endDate = ref(mockDateRange.end)
const quick = ref('')
const app = computed(() => mockApps[previewAppId.value])
const items = computed(() => getMockFiles(previewAppId.value, granularity.value))
const indices = computed(() => chartDates.flatMap((label, i) => `2026-${label}` >= startDate.value && `2026-${label}` <= endDate.value ? [i] : []))
const labels = computed(() => indices.value.map(i => chartDates[i]))
const trendSeries = computed(() => [
  { name: '总流量 · 样例', color: '#1677ff', values: indices.value.map(i => app.value.totalSeries[i]), fill: true },
  { name: '热点流量 · 样例', color: '#ff9d45', values: indices.value.map(i => app.value.hotspotSeries[i]), dashed: true }
])
const comparisonSeries = computed(() => [
  { name: '实际流量 · 样例', color: '#1677ff', values: indices.value.map(i => app.value.hotspotSeries[i]), fill: true },
  { name: '预测流量 · 样例', color: '#ff9d45', values: indices.value.map(i => app.value.predictedSeries[i]), dashed: true }
])
function setQuick(days) {
  quick.value = String(days)
  const date = new Date(`${mockDateRange.end}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() - days + 1)
  startDate.value = date.toISOString().slice(0,10)
  endDate.value = mockDateRange.end
}
</script>

<template>
  <div class="overview-view" data-page="overview">
    <header class="page-heading"><div><h1>业务总览</h1><p>按 App 洞察流量与热点，把预测结果转化为可追踪的压缩降本决策。</p></div><div class="heading-actions"><div class="segmented segmented--plain" aria-label="业务总览数据来源"><button :class="{active:mode==='mock'}" :aria-pressed="mode==='mock'" @click="mode='mock'">UI 演示</button><button :class="{active:mode==='api'}" :aria-pressed="mode==='api'" @click="mode='api'">历史观测 · 接口数据</button></div><span v-if="mode==='mock'" class="source-badge">UI MOCK · 静态演示</span><RouterLink class="btn btn-primary" to="/prediction"><AppIcon name="chart" :size="15"/>进入热点预测</RouterLink></div></header>
    <HistoryPanel v-if="mode==='api'" :dataset="selectedDataset"/>
    <template v-else>
    <section class="card filter-card" aria-label="业务总览筛选">
      <div class="filter-field"><label for="overview-app">AppID</label><select id="overview-app" v-model="previewAppId"><option v-for="option in appOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></div>
      <div class="filter-field"><label for="overview-start">历史日期</label><div class="date-range"><input id="overview-start" v-model="startDate" type="date" aria-label="历史开始日期" @change="quick = ''"><span class="date-separator">—</span><input v-model="endDate" type="date" aria-label="历史结束日期" @change="quick = ''"></div></div>
      <div class="quick-dates"><button v-for="days in [7,30,180]" :key="days" :class="{active:quick===String(days)}" @click="setQuick(days)">{{ days === 180 ? '近半年' : `近${days}天` }}</button></div>
      <div class="segmented filter-spacer" aria-label="预测榜单粒度"><button :class="{active:granularity==='DAILY'}" :aria-pressed="granularity==='DAILY'" @click="granularity='DAILY'">日榜</button><button :class="{active:granularity==='WEEKLY'}" :aria-pressed="granularity==='WEEKLY'" @click="granularity='WEEKLY'">周榜</button></div>
    </section>
    <div class="context-note"><span>App {{ previewAppId }} · 历史趋势按日期展示，预测摘要按日榜 / 周榜展示</span><span>样例截止日期 2026-10-13</span></div>
    <section class="metric-grid" aria-label="业务总览核心指标">
      <MetricCard label="总流量" :value="app.totalTraffic" :unit="app.totalUnit" icon="file" note="固定样例窗口累计 · UI Mock"/>
      <MetricCard label="热点流量占比" :value="app.hotspotRatio" unit="%" icon="pie" note="完整分母场景的 UI 示例"/>
      <MetricCard label="待压缩文件数" :value="app.pending" unit="个" icon="compression" note="UI 示例 · 非真实待办数量"/>
      <MetricCard label="预计节省金额" :value="`¥ ${app.forecastSaving}`" icon="money" tone="green" note="预测情景演示 · 未产生实际收益"/>
    </section>
    <div class="chart-grid">
      <section class="card"><header class="card-heading"><h2>总流量与热点流量趋势</h2><small>历史每日流量</small></header><LineChart v-if="labels.length" :labels="labels" :series="trendSeries" aria-label="UI Mock 总流量与热点流量历史趋势"/><div v-else class="chart-empty">所选范围没有 UI 样例数据</div><div class="chart-foot">UI Mock · 样例覆盖 2026-10-01 至 2026-10-13，空白日期不补零。</div></section>
      <section class="card"><header class="card-heading"><h2>流量类型分布</h2><small>App {{ previewAppId }}</small></header><DonutChart :items="app.distribution" :total="app.totalTraffic"/><div class="chart-foot">固定样例窗口累计分布 · UI Mock</div></section>
    </div>
    <div class="content-grid overview-bottom">
      <section class="card table-card"><header class="card-heading"><h2>Top50 热点预测摘要 <span class="small-title">{{ granularity === 'DAILY' ? '日榜' : '周榜' }}</span></h2><RouterLink class="card-action" to="/prediction">查看全部 <AppIcon name="chevron" :size="13"/></RouterLink></header><div class="table-scroll"><table><thead><tr><th>排名</th><th>文件路径（FileKey）</th><th>类型</th><th>预测流量</th><th>压缩状态</th></tr></thead><tbody><tr v-for="item in items" :key="item.fileKey"><td><span class="overview-rank" :class="{'overview-rank--top':item.rank<=3}">{{ item.rank }}</span></td><td><div class="file-key" :title="item.fileKey">{{ item.fileKey }}</div></td><td><span class="file-subtitle">{{ fileTypeLabels[item.fileType] }}</span></td><td class="text-blue">{{ formatBytes(item.predictedBytes) }}</td><td><span class="status-pill" :class="`status-pill--${item.compressionStatus.toLowerCase()}`">{{ compressionStatusLabels[item.compressionStatus] }}</span></td></tr></tbody></table></div><footer class="table-footer"><span>展示 8 条静态版式样例 · UI MOCK</span><span>模型版本 hotspot-simple-v1 · 仅用于界面展示</span></footer></section>
      <div class="stack">
        <section class="card"><header class="card-heading"><h2>实际流量与预测流量对比</h2><small>历史样例窗口</small></header><LineChart v-if="labels.length" :labels="labels" :series="comparisonSeries" aria-label="UI Mock 历史实际流量与预测流量对比"/><div v-else class="chart-empty">所选范围没有 UI 样例数据</div></section>
        <section class="card saving-summary"><header class="card-heading"><h2>压缩降本情况</h2><RouterLink class="card-action" to="/compression">查看详情 <AppIcon name="chevron" :size="13"/></RouterLink></header><div class="saving-highlight"><span class="saving-icon"><AppIcon name="money" :size="24"/></span><div><small>预计节省金额 · UI Mock</small><strong>¥ {{ app.forecastSaving }}</strong></div><span class="status-pill status-pill--success">预测情景</span></div><div class="saving-facts"><div><small>预计节省流量</small><strong>{{ app.savedTraffic }} TB</strong></div><div><small>示例已压缩数</small><strong>{{ app.completed }} <em>个</em></strong></div><div><small>实际节省金额</small><strong class="text-muted">—</strong></div></div><p class="chart-foot">实际收益需完成压缩登记、成熟访问观测与成本核验后计算。</p></section>
      </div>
    </div>
    </template>
  </div>
</template>
<style scoped>.small-title{font-size:10px;color:#8b9eb5;font-weight:400;margin-left:7px}.overview-rank{display:inline-grid;place-items:center;width:20px;height:20px;border-radius:4px;color:#8a9bb1;font-size:10px}.overview-rank--top{background:#edf5ff;color:#1677ff}.overview-bottom table{min-width:600px}.overview-bottom th,.overview-bottom td{padding:12px 14px}.overview-bottom .file-key{max-width:235px;font-size:10px}.saving-highlight{display:flex;align-items:center;gap:13px;padding:0 20px 20px}.saving-icon{display:grid;place-items:center;background:#fff6df;color:#eaa228;width:45px;height:45px;border-radius:50%}.saving-highlight small{font-size:11px;color:#8b9cb1;display:block;margin-bottom:7px}.saving-highlight strong{color:#235896;font-size:25px;font-weight:650;letter-spacing:-.4px}.saving-highlight>.status-pill{margin-left:auto}.saving-facts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px;margin:0 20px 14px}.saving-facts>div{padding:10px 9px;border:1px solid #edf1f7;border-radius:4px}.saving-facts small{color:#8c9cb1;font-size:9px;display:block;margin-bottom:7px}.saving-facts strong{font-size:15px;color:#476282;font-weight:600}.saving-facts em{font-size:10px;font-style:normal;font-weight:400}.overview-bottom>.stack .chart-wrap{max-height:230px}.saving-summary .chart-foot{margin-bottom:0;font-size:9px}@media(min-width:1700px){.overview-bottom th,.overview-bottom td{padding-top:15px;padding-bottom:15px}}</style>
