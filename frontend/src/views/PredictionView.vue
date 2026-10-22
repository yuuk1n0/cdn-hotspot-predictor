<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import CsvImportPanel from '../CsvImportPanel.vue'
import PredictionPanel from '../PredictionPanel.vue'
import LegacyTop50Panel from '../LegacyTop50Panel.vue'
import Top50Table from '../Top50Table.vue'
import FileDetailDialog from '../components/FileDetailDialog.vue'
import { metricsMatchRun, operationsApi, ratioReason, ratioText } from '../operations.js'
import { resultRows } from '../prediction-results.js'
import { uiContextKey } from '../ui/context.js'
import { appOptions, getMockFiles } from '../ui/mock-data.js'

const route = useRoute()
const context = inject(uiContextKey, { previewAppId: ref('10350'), selectedDataset: ref(null), selectDataset: () => {} })
const { previewAppId, selectedDataset, selectDataset } = context
const mode = ref('real')
const granularity = ref('DAILY')
const cutoffDate = ref('2026-10-13')
const model = ref('hotspot-simple-v1')
const run = ref(null)
const trafficMetrics = ref(null)
const loading = ref(false)
const error = ref('')
const metricsError = ref('')
const detailFile = ref(null)
const detailAction = ref('details')
const importOpen = ref(Boolean(route.query.import || route.query.taskId))
let generation = 0
let controller

const rows = computed(() => run.value ? resultRows(run.value) : [])
const mockRows = computed(() => getMockFiles(previewAppId.value, granularity.value).map(item => ({ ...item, modelVersion: model.value, predictionDate: windowDates.value.start, predictionEndDate: windowDates.value.end })))
const windowDates = computed(() => {
  const date = new Date(`${cutoffDate.value}T00:00:00Z`)
  if (!Number.isFinite(date.getTime())) return { start: '未选择', end: '未选择' }
  date.setUTCDate(date.getUTCDate() + 1)
  const start = date.toISOString().slice(0, 10)
  date.setUTCDate(date.getUTCDate() + (granularity.value === 'WEEKLY' ? 6 : 0))
  return { start, end: date.toISOString().slice(0, 10) }
})

async function load() {
  generation++
  controller?.abort()
  const current = generation
  const abort = new AbortController()
  controller = abort
  loading.value = true
  error.value = ''
  run.value = null
  trafficMetrics.value = null
  metricsError.value = ''
  try {
    const result = route.query.runId
      ? await operationsApi.result(route.query.runId, fetch, abort.signal)
      : await operationsApi.latest(previewAppId.value, granularity.value === 'WEEKLY' ? 7 : 1, fetch, abort.signal)
    if (current === generation && !abort.signal.aborted) {
      run.value = result
      await loadMetrics(result.runId, abort.signal)
    }
  } catch (requestError) {
    if (current === generation && !abort.signal.aborted) {
      error.value = requestError.status === 404
        ? `App ${previewAppId.value} 尚无 ${granularity.value === 'WEEKLY' ? '周' : '日'}跨度成功预测运行；未用 Mock 补位。`
        : requestError.message || '真实预测结果查询失败。'
    }
  } finally {
    if (current === generation) loading.value = false
  }
}
async function loadMetrics(runId, signal) {
  metricsError.value = ''
  try {
    const value = await operationsApi.metrics(runId, fetch, signal)
    if (!metricsMatchRun(value, run.value) || !value?.actualPredictedTop50 || !value?.actualTop50) throw new Error('流量占比响应与当前运行不匹配。')
    trafficMetrics.value = value
  } catch (cause) {
    if (!signal?.aborted) metricsError.value = cause.message || 'Top50 实际占比查询失败。'
  }
}

watch([previewAppId, granularity, () => route.query.runId], load, { immediate: true })
onBeforeUnmount(() => { generation++; controller?.abort() })
function openDetail(item, action) { detailAction.value = action; detailFile.value = item }
function adoptRun(result) { run.value = result; error.value = ''; trafficMetrics.value = null; void loadMetrics(result.runId) }
function openImport() {
  importOpen.value = true
  requestAnimationFrame(() => document.getElementById('data-import')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
</script>

<template>
  <div data-page="prediction">
    <header class="page-heading"><div><h1>热点预测 <span class="page-subtitle">Top50</span></h1><p>默认读取 Java 已持久化的真实预测快照；结果、排序、模型版本与 Python 运行保持一致。</p></div><div class="segmented segmented--plain" aria-label="预测页面数据来源"><button :class="{ active: mode === 'real' }" :aria-pressed="mode === 'real'" @click="mode = 'real'">真实运行</button><button :class="{ active: mode === 'mock' }" :aria-pressed="mode === 'mock'" @click="mode = 'mock'">UI 演示</button></div></header>

    <template v-if="mode === 'real'">
      <div class="prediction-toolbar"><div class="prediction-granularity" aria-label="预测粒度"><button :class="{ active: granularity === 'DAILY' }" @click="granularity = 'DAILY'">日榜预测</button><button :class="{ active: granularity === 'WEEKLY' }" @click="granularity = 'WEEKLY'">周榜预测</button></div><div class="toolbar-actions"><button class="btn btn-primary" type="button" @click="openImport"><AppIcon name="upload" :size="14"/>导入 CSV 数据</button><span class="source-badge">REAL / MODEL</span></div></div>
      <section class="card filter-card"><div class="filter-field"><label for="prediction-app">AppID</label><select id="prediction-app" v-model="previewAppId"><option v-for="option in appOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></div><div class="filter-field"><label>预测粒度</label><input :value="granularity === 'DAILY' ? '下一自然日' : '未来连续 7 天累计'" readonly></div><div class="filter-field"><label>模型版本</label><input value="hotspot-simple-v1" readonly></div><button class="btn btn-primary filter-spacer" :disabled="loading" @click="load"><AppIcon name="refresh" :size="14"/>{{ loading ? '查询中…' : '刷新最新运行' }}</button></section>
      <div v-if="error" class="notice notice--amber" role="alert"><AppIcon name="info" :size="16"/>{{ error }}</div>
      <div v-if="run" class="task-metric-grid">
        <article class="card task-metric"><small>候选文件总数</small><strong>{{ run.candidateCount }} <em>个</em></strong><span>Python 真实候选集</span></article>
        <article class="card task-metric"><small>返回 Top50 数量</small><strong>{{ run.top50Count }} <em>条</em></strong><span>最多 50 条，未补足</span></article>
        <article class="card task-metric task-metric--window"><small>预测目标窗口 · {{ granularity === 'DAILY' ? '日榜' : '周榜' }}</small><strong>{{ run.predictionStartDate }} <em>—</em> {{ run.predictionEndDate }}</strong><span>截止日 {{ run.cutoffDate }}</span></article>
        <article class="card task-metric"><small>数据 / 预测来源</small><strong class="metric-source">{{ run.datasetSource }} / {{ run.predictionSource }}</strong><span>{{ run.resultMode }} · {{ run.modelVersion }} · {{ run.modelInfo?.selectedMethod || '参数未返回' }}</span></article>
        <article class="card task-metric"><small>运行编号</small><div><span class="status-pill status-pill--success">SUCCEEDED</span></div><span class="mono run-id" :title="run.runId">{{ run.runId }}</span></article>
      </div>
      <section v-if="run" class="card table-card"><header class="card-heading"><div><h2>真实 Top50 预测结果</h2><span class="table-context">App {{ run.appId }} · {{ run.modelVersion }} · runId {{ run.runId }}</span></div><span class="source-badge source-badge--muted">{{ trafficMetrics?.actualPredictedTop50?.reasonCode || 'PREDICTED_TOP50' }}</span></header><Top50Table :items="rows" show-exact-bytes show-actions><template #actions="{ item }"><div class="row-actions"><button class="text-button" @click="openDetail(item, 'preview')">预览</button><button class="text-button" @click="openDetail(item, 'download')">下载</button><button class="text-button" @click="openDetail(item, 'details')">查看详情</button><button class="text-button" @click="openDetail(item, 'compression')">标记压缩</button></div></template></Top50Table><div v-if="metricsError" class="notice notice--amber" role="alert">{{ metricsError }}</div><footer class="table-footer"><span>预测Top50实际占比：{{ ratioText(trafficMetrics?.actualPredictedTop50) }} · {{ ratioReason(trafficMetrics?.actualPredictedTop50) }}</span><span>实际Top50集中度：{{ ratioText(trafficMetrics?.actualTop50) }} · {{ ratioReason(trafficMetrics?.actualTop50) }}</span><span>{{ run.runId }}</span></footer></section>
      <FileDetailDialog :file="detailFile" :run="run" :action="detailAction" @close="detailFile = null" />

      <details id="data-import" class="card real-workflow" :open="importOpen" @toggle="importOpen = $event.target.open"><summary>导入数据与发起新的真实预测任务 <span>CSV 上传入口</span></summary><div class="workflow-body"><CsvImportPanel @dataset-selected="selectDataset"/><p v-if="selectedDataset" class="notice notice--muted">当前数据集 {{ selectedDataset.datasetId }} / App {{ selectedDataset.appId }} · {{ selectedDataset.datasetSource }}</p><PredictionPanel :dataset="selectedDataset" @run-loaded="adoptRun"/></div></details>
      <details class="legacy-disclosure"><summary>旧 FAKE Top50 接口演示（不参与真实统计）</summary><LegacyTop50Panel/></details>
    </template>

    <template v-else>
      <div class="notice notice--amber"><AppIcon name="info" :size="16"/>UI MOCK 仅用于版式展示，不代表真实预测；不会发起任务或写入数据库。</div>
      <section class="card filter-card"><div class="filter-field"><label for="prediction-mock-app">AppID</label><select id="prediction-mock-app" v-model="previewAppId"><option v-for="option in appOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></div><div class="filter-field"><label for="prediction-cutoff">预测截止日期</label><input id="prediction-cutoff" v-model="cutoffDate" type="date"></div><div class="filter-field"><label>模型版本</label><input v-model="model" readonly></div></section>
      <section class="card table-card mock-prediction-table"><header class="card-heading"><div><h2>Top50 预测结果</h2><span class="table-context">App {{ previewAppId }} · {{ model }} · UI MOCK</span></div><span class="source-badge">UI MOCK</span></header><Top50Table :items="mockRows" show-actions><template #actions="{ item }"><div class="row-actions"><button class="text-button" @click="openDetail(item, 'details')">查看详情</button></div></template></Top50Table></section>
      <FileDetailDialog :file="detailFile" ui-mock :action="detailAction" @close="detailFile = null" />
    </template>
  </div>
</template>

<style scoped>
.page-subtitle{font-size:14px;font-weight:400;color:#8b9fb9;margin-left:8px}.prediction-toolbar{display:flex;align-items:center;justify-content:space-between;margin:0 0 12px;gap:10px}.toolbar-actions{display:flex;align-items:center;gap:10px}.prediction-granularity{display:flex;gap:4px;border-bottom:1px solid #e1e9f4}.prediction-granularity button{border:1px solid transparent;border-bottom:0;background:none;color:#8597ae;border-radius:5px 5px 0 0;padding:12px 29px;font-size:12px;margin-bottom:-1px}.prediction-granularity button.active{background:white;border-color:#aacfff;color:#1677ff;border-bottom:2px solid white}.task-metric-grid{display:grid;grid-template-columns:1fr 1fr 2.4fr 1.25fr 1.45fr;gap:13px;margin-bottom:18px}.task-metric{padding:16px 17px;min-height:105px;display:flex;flex-direction:column;gap:10px;justify-content:space-between}.task-metric>small{color:#8194ad;font-size:10px}.task-metric>strong{font-size:24px;line-height:1.1;color:#2b4569;letter-spacing:-.5px}.task-metric>strong em{font-size:10px;font-weight:400;font-style:normal;color:#8c9eb6}.task-metric>span{font-size:9px;color:#a1aebf}.task-metric--window>strong{font-size:15px;letter-spacing:0;white-space:nowrap}.metric-source{font-size:17px!important}.run-id{max-width:210px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#7188a4;font-size:9px!important}.table-context{display:block;font-size:10px;color:#9aabc0;margin-top:8px;overflow-wrap:anywhere}.real-workflow{margin-top:20px;padding:0}.real-workflow>summary,.legacy-disclosure>summary{font-size:12px;color:#7c91ae;padding:16px;cursor:pointer}.real-workflow>summary{font-weight:600;color:#47688e}.real-workflow>summary span{color:#8aa3c1;font-weight:400;font-size:10px}.workflow-body{padding:0 16px 16px}.legacy-disclosure{margin-top:16px;background:#fff;border:1px solid #e7edf5;border-radius:5px}.mock-prediction-table .row-actions{gap:9px}@media(max-width:1200px){.task-metric-grid{grid-template-columns:1fr 1fr 2fr}.task-metric--window>strong{font-size:13px}.task-metric{min-height:100px}}@media(max-width:760px){.task-metric-grid{grid-template-columns:1fr 1fr}.task-metric--window{grid-column:1/-1}.page-heading{flex-wrap:wrap}.toolbar-actions .source-badge{display:none}}
</style>
