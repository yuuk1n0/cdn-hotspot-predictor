<script setup>
import { computed, inject, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import CsvImportPanel from '../CsvImportPanel.vue'
import PredictionPanel from '../PredictionPanel.vue'
import LegacyTop50Panel from '../LegacyTop50Panel.vue'
import Top50Table from '../Top50Table.vue'
import FileDetailDialog from '../components/FileDetailDialog.vue'
import { uiContextKey } from '../ui/context.js'
import { appOptions, getMockFiles } from '../ui/mock-data.js'
const route = useRoute()
const context = inject(uiContextKey, { previewAppId:ref('10350'), selectedDataset:ref(null), selectDataset:()=>{} })
const { previewAppId, selectedDataset, selectDataset } = context
const mode = ref(route.query.taskId || route.query.runId || ['#top50','#prediction'].includes(route.hash) ? 'api' : 'mock')
const granularity = ref('DAILY')
const cutoffDate = ref('2026-10-13')
const model = ref('hotspot-simple-v1')
const notice = ref('')
const detailFile = ref(null)
const detailAction = ref('details')
const rows = computed(() => getMockFiles(previewAppId.value,granularity.value).map(item=>({...item,modelVersion:model.value,predictionDate:windowDates.value.start,predictionEndDate:windowDates.value.end})))
const windowDates = computed(() => {
  const date = new Date(`${cutoffDate.value}T00:00:00Z`)
  if (!Number.isFinite(date.getTime())) return {start:'未选择',end:'未选择'}
  date.setUTCDate(date.getUTCDate()+1)
  const start = date.toISOString().slice(0,10)
  date.setUTCDate(date.getUTCDate()+(granularity.value==='WEEKLY' ? 6 : 0))
  return {start,end:date.toISOString().slice(0,10)}
})
function openDetail(item,action) { detailAction.value=action;detailFile.value=item }
</script>
<template>
  <div data-page="prediction">
    <header class="page-heading"><div><h1>热点预测 <span class="page-subtitle">Top50</span></h1><p>按 App 和日 / 周窗口查看热点文件，保留原有数据导入与预测任务流程。</p></div><div class="segmented segmented--plain" aria-label="预测页面数据来源"><button :class="{active:mode==='mock'}" :aria-pressed="mode==='mock'" @click="mode='mock'">UI 演示</button><button :class="{active:mode==='api'}" :aria-pressed="mode==='api'" @click="mode='api'">接口数据 · 已有功能</button></div></header>
    <template v-if="mode==='mock'">
      <div class="prediction-toolbar"><div class="prediction-granularity" aria-label="预测粒度"><button :class="{active:granularity==='DAILY'}" @click="granularity='DAILY'">日榜预测</button><button :class="{active:granularity==='WEEKLY'}" @click="granularity='WEEKLY'">周榜预测</button></div><span class="source-badge">UI MOCK · 静态演示</span></div>
      <section class="card filter-card"><div class="filter-field"><label for="prediction-app">AppID</label><select id="prediction-app" v-model="previewAppId"><option v-for="option in appOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></div><div class="filter-field"><label for="prediction-cutoff">预测截止日期</label><input id="prediction-cutoff" v-model="cutoffDate" type="date"></div><div class="filter-field"><label for="prediction-model">模型版本</label><select id="prediction-model" v-model="model"><option value="hotspot-simple-v1">hotspot-simple-v1 · UI 样例</option><option value="contract-demo-v1">contract-demo-v1 · UI 样例</option></select></div><button class="btn btn-primary filter-spacer" @click="notice='当前为 UI 演示，未创建预测任务。请切换“接口数据 · 已有功能”使用现有预测流程。'"><AppIcon name="play" :size="14"/>开始预测</button></section>
      <div v-if="notice" class="notice notice--amber" role="status"><AppIcon name="info" :size="16"/>{{ notice }}</div>
      <div class="task-metric-grid"><article class="card task-metric"><small>候选文件总数</small><strong>{{ previewAppId==='10350' ? '428' : '316' }} <em>个</em></strong><span>静态样例</span></article><article class="card task-metric"><small>返回 Top50 数量</small><strong>50 <em>条</em></strong><span>版式指标样例</span></article><article class="card task-metric task-metric--window"><small>预测目标窗口 · {{ granularity==='DAILY' ? '日榜' : '周榜' }}</small><strong>{{ windowDates.start }} <em>—</em> {{ windowDates.end }}</strong><span>{{ granularity==='DAILY' ? '截止日后的下一自然日' : '截止日后的连续七天累计' }}</span></article><article class="card task-metric"><small>预测耗时</small><strong>—</strong><span>未执行真实任务</span></article><article class="card task-metric"><small>任务状态</small><div><span class="status-pill status-pill--processing">UI 样例</span></div><span>无后台任务</span></article></div>
      <section class="card table-card mock-prediction-table"><header class="card-heading"><div><h2>Top50 预测结果</h2><span class="table-context">App {{ previewAppId }} · {{ model }} · {{ granularity==='DAILY' ? '日榜' : '周榜' }}</span></div><span class="sample-bullet">静态展示数据</span></header><Top50Table :items="rows" show-actions><template #actions="{item}"><div class="row-actions"><button class="text-button" @click="openDetail(item,'preview')">预览</button><button class="text-button" @click="openDetail(item,'download')">下载</button><button class="text-button" @click="openDetail(item,'details')">查看详情</button><button class="text-button" @click="openDetail(item,'compression')">标记压缩</button></div></template></Top50Table><footer class="table-footer"><span>展示 8 条 UI 版式样例；“返回 50 条”为静态指标示意，未运行预测</span><span>UI MOCK · 与真实接口结果分离</span></footer></section>
      <FileDetailDialog :file="detailFile" ui-mock :action="detailAction" @close="detailFile=null"/>
    </template>
    <section v-else class="api-workspace" aria-label="已有接口功能"><div class="notice"><AppIcon name="info" :size="16"/><strong>已有接口功能</strong><span>保留原 CSV 直传、导入查询、READY 选择、日周任务与榜单读取。实际来源以接口返回的 REAL/FAKE、MOCK/MODEL 为准。</span></div><CsvImportPanel @dataset-selected="selectDataset"/><p v-if="selectedDataset" class="notice notice--muted">已选数据集 {{ selectedDataset.datasetId }} / App {{ selectedDataset.appId }} · {{ selectedDataset.datasetSource }}</p><PredictionPanel :dataset="selectedDataset"/><details class="legacy-disclosure" :open="route.hash==='#top50'"><summary>原 FAKE 接口筛选与分页（保留）</summary><LegacyTop50Panel/></details></section>
  </div>
</template>
<style scoped>.page-subtitle{font-size:14px;font-weight:400;color:#8b9fb9;margin-left:8px}.prediction-toolbar{display:flex;align-items:center;justify-content:space-between;margin:0 0 12px;gap:10px}.prediction-granularity{display:flex;gap:4px;border-bottom:1px solid #e1e9f4}.prediction-granularity button{border:1px solid transparent;border-bottom:0;background:none;color:#8597ae;border-radius:5px 5px 0 0;padding:12px 29px;font-size:12px;margin-bottom:-1px}.prediction-granularity button.active{background:white;border-color:#aacfff;color:#1677ff;border-bottom:2px solid white}.task-metric-grid{display:grid;grid-template-columns:1fr 1fr 2.4fr 1fr 1.1fr;gap:13px;margin-bottom:18px}.task-metric{padding:16px 17px;min-height:105px;display:flex;flex-direction:column;gap:10px;justify-content:space-between}.task-metric>small{color:#8194ad;font-size:10px}.task-metric>strong{font-size:24px;line-height:1.1;color:#2b4569;letter-spacing:-.5px}.task-metric>strong em{font-size:10px;font-weight:400;font-style:normal;color:#8c9eb6}.task-metric>span{font-size:9px;color:#a1aebf}.task-metric--window>strong{font-size:15px;letter-spacing:0;white-space:nowrap}.table-context{display:block;font-size:10px;color:#9aabc0;margin-top:8px}.legacy-disclosure>summary{font-size:12px;color:#7c91ae;padding:16px;background:#fff;border:1px solid #e7edf5;border-radius:5px;cursor:pointer}.mock-prediction-table .row-actions{gap:9px}@media(max-width:1200px){.task-metric-grid{grid-template-columns:1fr 1fr 2fr}.task-metric--window>strong{font-size:13px}.task-metric{min-height:100px}}@media(max-width:760px){.task-metric-grid{grid-template-columns:1fr 1fr}.task-metric--window{grid-column:1/-1}.page-heading{flex-wrap:wrap}}</style>
