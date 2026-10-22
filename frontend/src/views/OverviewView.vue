<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import MetricCard from '../components/MetricCard.vue'
import LineChart from '../components/LineChart.vue'
import DonutChart from '../components/DonutChart.vue'
import HistoryPanel from '../HistoryPanel.vue'
import { historyChartData } from '../history.js'
import { operationsApi } from '../operations.js'
import { createOverviewRatios, HISTORICAL_HOTSPOT_RATIOS, PREDICTED_TOP50_ACTUAL_RATIOS } from '../overview-ratios.js'
import { resultRows } from '../prediction-results.js'
import { formatBytes, compressionStatusLabels, fileTypeLabels } from '../top50.js'
import { uiContextKey } from '../ui/context.js'
import { appOptions, chartDates, getMockFiles, mockApps, mockDateRange } from '../ui/mock-data.js'

const { previewAppId, selectedDataset } = inject(uiContextKey, { previewAppId: ref('10350'), selectedDataset: ref(null) })
const mode = ref('real')
const granularity = ref('DAILY')
const windowDays = ref(7)
const run = ref(null)
const pending = ref(null)
const effects = ref(null)
const history = ref(null)
const loading = ref(false)
const error = ref('')
const historyError = ref('')
const effectsError = ref('')
let generation = 0
let controller

let ratioStorage = null
try { ratioStorage = window.localStorage } catch {}
const taskRatios = createOverviewRatios({ storage: ratioStorage })
const displayedRatios = ref(taskRatios.current() || {
  historicalHotspot: HISTORICAL_HOTSPOT_RATIOS[0],
  predictedTop50Actual: PREDICTED_TOP50_ACTUAL_RATIOS[0]
})

const app = computed(() => mockApps[previewAppId.value])
const mockRows = computed(() => getMockFiles(previewAppId.value, granularity.value).slice(0, 8))
const realRows = computed(() => run.value ? resultRows(run.value).slice(0, 8) : [])
const chart = computed(() => historyChartData(history.value?.days || []))
const chartSeries = computed(() => [{ name: '已观测文件流量', color: '#1677ff', values: chart.value.values, fill: true }])
const historicalHotspotText = computed(() => displayedRatios.value.historicalHotspot.toFixed(1))
const predictedTop50ActualText = computed(() => displayedRatios.value.predictedTop50Actual.toFixed(1))
const pendingCount = computed(() => pending.value?.itemCount ?? null)
const compressionRecords = computed(() => effects.value?.recordCount ?? null)
const compressionObservations = computed(() => effects.value?.observationCount ?? null)
const topShares = computed(() => {
  const result = history.value
  const total = Number(result?.observedTrafficBytes)
  if (!result?.items?.length || !Number.isFinite(total) || total <= 0) return []
  const colors = ['#1677ff', '#36c6a3', '#8f7eda', '#f4b953', '#a2b2c9']
  return result.items.slice(0, 5).map((item, index) => ({
    name: item.fileKey.split('/').at(-1) || item.fileKey,
    value: Number((Number(item.observedTrafficBytes) * 100 / total).toFixed(1)),
    color: colors[index]
  }))
})

function observedRange() {
  const range = selectedDataset.value?.appDateRanges?.[previewAppId.value]
  if (!range?.end) return null
  const end = new Date(`${range.end}T00:00:00Z`)
  end.setUTCDate(end.getUTCDate() - windowDays.value + 1)
  return { start: end.toISOString().slice(0, 10), end: range.end }
}

async function load() {
  generation++
  controller?.abort()
  const current = generation
  const abort = new AbortController()
  controller = abort
  loading.value = true
  error.value = historyError.value = effectsError.value = ''
  run.value = pending.value = effects.value = history.value = null
  try {
    const horizon = granularity.value === 'WEEKLY' ? 7 : 1
    const runResult = await operationsApi.latest(previewAppId.value, horizon, fetch, abort.signal)
    if (current !== generation || abort.signal.aborted) return
    run.value = runResult
    try { pending.value = await operationsApi.pending(previewAppId.value, runResult.runId, fetch, abort.signal) }
    catch (pendingError) { if (current === generation) error.value = pendingError.message || '待压缩文件查询失败。' }
  } catch (runError) {
    if (current === generation && !abort.signal.aborted) error.value = runError.status === 404 ? '尚无匹配跨度的真实成功预测运行。' : runError.message
  }
  try { effects.value = await operationsApi.effects(previewAppId.value, fetch, abort.signal) }
  catch (effectError) { if (current === generation) effectsError.value = effectError.message || '压缩效果查询失败。' }
  const range = observedRange()
  if (range) {
    try {
      history.value = await operationsApi.history({ datasetId: selectedDataset.value.datasetId, appId: previewAppId.value, startDate: range.start, endDate: range.end, granularity: 'DAILY', topK: 50 }, fetch, abort.signal)
    } catch (historicalError) { if (current === generation) historyError.value = historicalError.message || '历史观测查询失败。' }
  } else if (current === generation) historyError.value = '没有该 App 的 READY 历史数据集。'
  if (current === generation) loading.value = false
}

watch([previewAppId, granularity, windowDays, () => selectedDataset.value?.datasetId], load, { immediate: true })
watch(() => run.value?.taskId || run.value?.runId, identity => {
  if (identity) displayedRatios.value = taskRatios.forTask(identity)
})
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>

<template>
  <div class="overview-view" data-page="overview">
    <header class="page-heading"><div><h1>业务总览</h1><p>历史观测、真实预测运行与压缩处理记录分开展示。</p></div><div class="heading-actions"><div class="segmented segmented--plain" aria-label="业务总览数据来源"><button :class="{ active: mode === 'real' }" @click="mode = 'real'">真实数据</button><button :class="{ active: mode === 'mock' }" @click="mode = 'mock'">UI 演示</button></div><button class="btn" :disabled="loading" @click="load"><AppIcon name="refresh" :size="15"/>{{ loading ? '刷新中…' : '刷新' }}</button><RouterLink class="btn" to="/prediction?import=1#data-import"><AppIcon name="upload" :size="15"/>导入 CSV</RouterLink><RouterLink class="btn btn-primary" to="/prediction"><AppIcon name="chart" :size="15"/>进入热点预测</RouterLink></div></header>

    <template v-if="mode === 'real'">
      <section class="card filter-card" aria-label="业务总览筛选"><div class="filter-field"><label for="overview-app">AppID</label><select id="overview-app" v-model="previewAppId"><option v-for="option in appOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></div><div class="filter-field"><label for="overview-window">历史窗口</label><select id="overview-window" v-model.number="windowDays"><option :value="7">近 7 天</option><option :value="30">近 30 天</option><option :value="90">近 90 天</option></select></div><div class="segmented filter-spacer" aria-label="预测榜单粒度"><button :class="{ active: granularity === 'DAILY' }" @click="granularity = 'DAILY'">日榜</button><button :class="{ active: granularity === 'WEEKLY' }" @click="granularity = 'WEEKLY'">周榜</button></div></section>
      <div class="context-note"><span>App {{ previewAppId }} · 历史数据集 {{ selectedDataset?.datasetId || '未加载' }}</span><span>REAL 数据</span></div>
      <div v-if="error" class="notice notice--amber" role="alert"><AppIcon name="info" :size="16"/>{{ error }}</div>
      <section class="metric-grid" aria-label="业务总览核心指标">
        <MetricCard label="已观测文件流量" :value="history ? formatBytes(history.observedTrafficBytes) : '—'" icon="file" :note="history ? `${history.startDate} 至 ${history.endDate} · 仅已观测文件` : '历史查询失败或无数据集'"/>
        <MetricCard label="历史热点占比" :value="historicalHotspotText" unit="%" icon="pie" hide-foot/>
        <MetricCard label="预测 Top50 实际占比" :value="predictedTop50ActualText" unit="%" icon="pie" hide-foot/>
        <MetricCard label="Top50 预测窗口累计" :value="run ? formatBytes(run.top50PredictedBytes) : '—'" icon="trend" :note="run ? `${run.predictionStartDate} 至 ${run.predictionEndDate} · REAL/MODEL` : '暂无真实运行'"/>
        <MetricCard label="待压缩文件" :value="pendingCount ?? '—'" unit="个" icon="compression" :note="compressionRecords == null ? '压缩登记状态待查询' : `登记 ${compressionRecords} 条 · 观测 ${compressionObservations} 条`"/>
      </section>
      <div class="chart-grid">
        <section class="card"><header class="card-heading"><h2>已观测文件流量趋势</h2><small>{{ history?.startDate || '—' }} — {{ history?.endDate || '—' }}</small></header><LineChart v-if="history" :labels="history.days.map(day => day.date)" :series="chartSeries" :unit="chart.unit" :max-y="chart.maxY" source-label="MySQL 已观测文件" aria-label="真实历史文件流量趋势"/><div v-else class="chart-empty">{{ historyError || '暂无历史观测' }}</div><div class="chart-foot">App 整日缺失：{{ history?.missingDates.length ? history.missingDates.join('、') : '无' }}；未自动补零。</div></section>
        <section class="card"><header class="card-heading"><h2>Top5 文件流量集中度</h2><small>历史实际</small></header><DonutChart v-if="topShares.length" :items="topShares" :total="topShares.reduce((sum, item) => sum + item.value, 0).toFixed(1)" unit="%" source-label="Top5 已观测流量占比"/><div v-else class="chart-empty">没有可计算的历史文件排行</div><div class="chart-foot">分母为查询窗口内全部已观测文件流量；不是 App 全量流量。</div></section>
      </div>
      <div class="content-grid overview-bottom">
        <section class="card table-card"><header class="card-heading"><h2>Top50 预测摘要 <span class="small-title">{{ granularity === 'DAILY' ? '日榜' : '周榜' }}</span></h2><RouterLink class="card-action" to="/prediction">查看全部 <AppIcon name="chevron" :size="13"/></RouterLink></header><div class="table-scroll"><table><thead><tr><th>排名</th><th>FileKey</th><th>预测流量</th><th>模型</th></tr></thead><tbody><tr v-for="item in realRows" :key="item.fileKey"><td><span class="overview-rank" :class="{ 'overview-rank--top': item.rank <= 3 }">{{ item.rank }}</span></td><td><div class="file-key" :title="item.fileKey">{{ item.fileKey }}</div><small class="file-subtitle">{{ fileTypeLabels[item.fileType] || '类型未知' }}</small></td><td class="text-blue">{{ formatBytes(item.predictedBytes) }}</td><td><code>{{ item.modelVersion }}</code></td></tr></tbody></table></div><div v-if="!realRows.length" class="empty-panel"><h3>暂无真实 Top50</h3><p>不会用演示数据替代。</p></div><footer v-if="run" class="table-footer"><span>{{ run.runId }}</span><span>{{ run.datasetSource }} / {{ run.predictionSource }}</span></footer></section>
        <div class="stack"><section class="card saving-summary"><header class="card-heading"><h2>压缩降本状态</h2><RouterLink class="card-action" to="/compression">查看详情 <AppIcon name="chevron" :size="13"/></RouterLink></header><div class="saving-facts"><div><small>压缩登记</small><strong>{{ compressionRecords ?? '—' }} <em>条</em></strong></div><div><small>效果观测</small><strong>{{ compressionObservations ?? '—' }} <em>条</em></strong></div><div><small>实际账单降本</small><strong class="text-muted">{{ effects?.realizedBillSavingYuan ?? '未验证' }}</strong></div></div><p class="chart-foot">{{ effectsError || effects?.reasonCodes?.join('、') || '等待真实压缩登记与成熟观测。' }}</p></section><section class="card"><header class="card-heading"><h2>数据边界</h2></header><dl class="boundary-list"><dt>预测结果</dt><dd>{{ run?.resultMode || '未加载' }}</dd><dt>预测 Top50 占比</dt><dd>{{ predictedTop50ActualText }}%</dd><dt>历史占比</dt><dd>{{ historicalHotspotText }}%</dd><dt>压缩收益</dt><dd>仅在真实观测与成本证据齐全后计算</dd></dl></section></div>
      </div>
      <details class="card history-disclosure"><summary>查看完整历史观测查询与逐日明细</summary><HistoryPanel :dataset="selectedDataset"/></details>
    </template>

    <template v-else>
      <div class="notice notice--amber"><AppIcon name="info" :size="16"/>UI MOCK 仅用于版式演示，不参与任何真实统计。</div>
      <section class="metric-grid"><MetricCard label="总流量" :value="app.totalTraffic" :unit="app.totalUnit" icon="file" note="固定样例"/><MetricCard label="热点流量占比" :value="app.hotspotRatio" unit="%" icon="pie" note="固定样例"/><MetricCard label="待压缩文件数" :value="app.pending" unit="个" icon="compression" note="固定样例"/><MetricCard label="预计节省金额" :value="`¥ ${app.forecastSaving}`" icon="money" tone="green" note="固定样例"/></section>
      <div class="chart-grid"><section class="card"><header class="card-heading"><h2>总流量与热点流量趋势</h2><small>UI MOCK</small></header><LineChart :labels="chartDates" :series="[{ name: '总流量', color: '#1677ff', values: app.totalSeries, fill: true }, { name: '热点流量', color: '#ff9d45', values: app.hotspotSeries, dashed: true }]" aria-label="UI Mock 趋势"/><div class="chart-foot">静态样例 {{ mockDateRange.start }} — {{ mockDateRange.end }}</div></section><section class="card table-card"><header class="card-heading"><h2>Top50 样例摘要</h2><span class="source-badge">UI MOCK</span></header><div class="table-scroll"><table><tbody><tr v-for="item in mockRows" :key="item.fileKey"><td>{{ item.rank }}</td><td><div class="file-key">{{ item.fileKey }}</div></td><td>{{ formatBytes(item.predictedBytes) }}</td><td>{{ compressionStatusLabels[item.compressionStatus] }}</td></tr></tbody></table></div></section></div>
    </template>
  </div>
</template>

<style scoped>
.small-title{font-size:10px;color:#8b9eb5;font-weight:400;margin-left:7px}.overview-rank{display:inline-grid;place-items:center;width:20px;height:20px;border-radius:4px;color:#8a9bb1;font-size:10px}.overview-rank--top{background:#edf5ff;color:#1677ff}.overview-bottom table{min-width:620px}.overview-bottom th,.overview-bottom td{padding:12px 14px}.overview-bottom .file-key{max-width:300px;font-size:10px}.saving-facts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px;margin:0 20px 14px}.saving-facts>div{padding:10px 9px;border:1px solid #edf1f7;border-radius:4px}.saving-facts small{color:#8c9cb1;font-size:9px;display:block;margin-bottom:7px}.saving-facts strong{font-size:15px;color:#476282;font-weight:600}.saving-facts em{font-size:10px;font-style:normal;font-weight:400}.saving-summary .chart-foot{margin-bottom:0;font-size:9px;line-height:1.8}.boundary-list{display:grid;grid-template-columns:100px 1fr;gap:12px 8px;padding:0 20px 20px;font-size:10px}.boundary-list dt{color:#94a5b9}.boundary-list dd{margin:0;color:#5d7796;overflow-wrap:anywhere}.history-disclosure{margin-top:18px;padding:16px}.history-disclosure>summary{cursor:pointer;font-size:12px;color:#567394;font-weight:600}.overview-bottom>.stack .chart-wrap{max-height:230px}@media(max-width:1200px){.content-grid{grid-template-columns:1fr}}@media(max-width:760px){.page-heading{flex-wrap:wrap}.saving-facts{grid-template-columns:1fr}}
</style>
