<script setup>
import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from './components/AppIcon.vue'
import LineChart from './components/LineChart.vue'
import MetricCard from './components/MetricCard.vue'
import { createHistoryQuery, historyChartData, historyDate, historyWeekEnd } from './history.js'
import { formatBytes } from './top50.js'

const props = defineProps({ dataset: { type: Object, default: null } })
const query = createHistoryQuery()
const { state } = query
const form = reactive({ appId: '', startDate: '', endDate: '', granularity: 'DAILY', fileKey: '', topK: 50 })
const apps = computed(() => Object.keys(props.dataset?.appDateRanges || {}))
const available = computed(() => props.dataset?.appDateRanges?.[form.appId])
const result = computed(() => state.result)
const chart = computed(() => historyChartData(result.value?.days || []))
const chartSeries = computed(() => [{ name: '已观测文件流量 · 接口数据', color: '#1677ff', values: chart.value.values }])

function setDates() {
  const range = available.value
  form.startDate = range?.start || ''
  form.endDate = range?.end || ''
  if (range && historyDate(range.end) - historyDate(range.start) > 365 * 86400000) {
    const start = historyDate(range.end)
    start.setUTCDate(start.getUTCDate() - 365)
    form.startDate = start.toISOString().slice(0, 10)
  }
  if (form.granularity === 'WEEKLY') form.endDate = historyWeekEnd(form.startDate)
}
watch(() => props.dataset, () => {
  query.clear()
  form.appId = props.dataset?.appId || ''
  form.fileKey = ''
  setDates()
}, { immediate: true, flush: 'sync' })
watch(() => form.appId, () => { form.fileKey = ''; setDates() }, { flush: 'sync' })
watch(() => [form.startDate, form.granularity], () => {
  if (form.granularity === 'WEEKLY') form.endDate = historyWeekEnd(form.startDate)
}, { flush: 'sync' })
watch(() => [props.dataset?.datasetId, ...Object.values(form)], () => query.clear(), { flush: 'sync' })
function load() {
  if (!props.dataset) return
  return query.load({ datasetId: props.dataset.datasetId, ...form })
}
onBeforeUnmount(query.dispose)
</script>

<template>
  <section class="history-panel" aria-labelledby="history-title" :aria-busy="state.busy">
    <div class="history-heading"><div><h2 id="history-title">历史流量与观测看板</h2><p>仅已观测文件 · 从 READY 数据集读取标准化观测；未确认 App 全量分母，不代表权威 App 总流量。</p></div><span class="source-badge source-badge--muted">接口数据 · 无替代样例</span></div>
    <div v-if="!dataset" class="card empty-panel"><AppIcon name="chart" :size="34"/><h3>先选择 READY 数据集</h3><p>在热点预测页的已有接口功能中选择数据集和 App，再返回业务总览。</p><RouterLink class="btn btn-primary" to="/prediction#prediction">选择 READY 数据集</RouterLink></div>
    <template v-else>
      <div class="notice notice--muted history-identity"><span class="source-badge">{{ dataset.datasetSource }} · {{ dataset.datasetSource==='FAKE' ? '演示输入观测' : '声明为真实的输入观测' }}</span><span class="mono">{{ dataset.datasetId }}</span><RouterLink class="text-button" to="/prediction#prediction">更换数据集</RouterLink></div>
      <form class="card filter-card history-filters" aria-label="历史观测查询" @submit.prevent="load">
        <div class="filter-field"><label for="history-app">AppID</label><select id="history-app" v-model="form.appId" required><option v-for="app in apps" :key="app" :value="app">{{ app }}</option></select></div>
        <div class="filter-field"><label for="history-granularity">统计窗口</label><select id="history-granularity" v-model="form.granularity"><option value="DAILY">日视图 · 自选日期</option><option value="WEEKLY">周视图 · 连续七天</option></select></div>
        <div class="filter-field"><label for="history-start">历史开始日期</label><input id="history-start" v-model="form.startDate" type="date" required></div>
        <div class="filter-field"><label for="history-end">历史结束日期</label><input id="history-end" v-model="form.endDate" type="date" :readonly="form.granularity==='WEEKLY'" required></div>
        <div class="filter-field history-file-filter"><label for="history-file">FileKey 精确匹配（可选）</label><input id="history-file" v-model="form.fileKey" placeholder="保留原始文件标识，不做模糊匹配" autocomplete="off"></div>
        <div class="filter-field"><label for="history-topk">TopK</label><input id="history-topk" v-model.number="form.topK" type="number" min="1" max="50" step="1" required></div>
        <button class="btn btn-primary" type="submit" :disabled="state.busy">{{ state.busy ? '正在查询…' : '查询历史观测' }}</button>
      </form>
      <div class="context-note"><span>App {{ form.appId }} 可用观测：{{ available?.start }} — {{ available?.end }}；允许查询范围外日期并保留缺口。</span><span>{{ form.granularity==='WEEKLY' ? '结束日固定为开始日 + 6；累计统计，不是未来周预测' : '最多366天，含首尾；与预测日榜/周榜分别设置' }}</span></div>
      <div v-if="state.error" class="notice notice--amber" role="alert"><AppIcon name="info" :size="16"/><strong>历史查询失败</strong><span>{{ state.error }}</span><span>未返回零流量或 UI Mock；请核对条件或重试。</span></div>
      <div v-else-if="!result" class="card empty-panel" role="status"><h3>{{ state.busy ? '正在读取历史观测' : '尚未查询' }}</h3><p>选择条件后点击查询。修改条件会清除上一结果，并忽略旧请求的迟到响应。</p></div>
      <template v-if="result">
        <div class="context-note history-result-context"><span>本次结果：{{ result.datasetId }} / App {{ result.appId }} / {{ result.startDate }} — {{ result.endDate }} / {{ result.granularity }}</span><span>{{ result.datasetSource }} · 完整性 {{ result.completeness }}</span></div>
        <section class="metric-grid" aria-label="历史观测指标">
          <MetricCard label="已观测文件流量合计" :value="formatBytes(result.observedTrafficBytes)" icon="file" note="仅查询窗口内已有记录，不是权威App总量"/>
          <MetricCard label="App 整日缺失" :value="String(result.missingDates.length)" unit="天" icon="clock" note="缺失日期保留 null；不会自动补零"/>
          <MetricCard label="窗口排行返回" :value="String(result.items.length)" unit="条" icon="chart" note="按文件窗口累计排序，由后端真实统计"/>
          <MetricCard label="热点流量占比" value="—" icon="pie" note="未确认 App 全量分母，不可计算"/>
        </section>
        <section class="card history-trend"><header class="card-heading"><h2>已观测文件每日流量</h2><small>App {{ result.appId }} · {{ state.request.fileKey ? '指定文件' : '全部已观测文件' }}</small></header><LineChart :labels="result.days.map(day=>day.date)" :series="chartSeries" :unit="chart.unit" :max-y="chart.maxY" source-label="接口观测" aria-label="接口历史观测流量趋势"/><div class="chart-foot">图形按流量大小选择单位并近似显示；精确字节以下表为准。缺日断线，显式 0B 保留观测点。单文件无观测不等于该日 App 整体缺失。</div></section>
        <div class="history-tables">
          <section class="card table-card history-days"><header class="card-heading"><h2>逐日观测与缺口</h2><small>精确字节 B</small></header><div class="table-scroll"><table><thead><tr><th>日期</th><th>已观测流量（B）</th><th>文件数</th><th>观测状态</th></tr></thead><tbody><tr v-for="day in result.days" :key="day.date" :data-date="day.date"><td class="mono">{{ day.date }}</td><td class="mono exact-bytes">{{ day.observedTrafficBytes ?? '无观测（null）' }}</td><td>{{ day.fileCount }}</td><td><span class="status-pill" :class="day.status==='OBSERVED'?'status-pill--success':'status-pill--unknown'">{{ day.status==='OBSERVED'?'已观测':'无观测' }}</span></td></tr></tbody></table></div></section>
          <section class="card table-card history-ranking"><header class="card-heading"><h2>窗口 Top{{ state.request.topK }} 文件</h2><small>精确 FileKey · 累计B</small></header><div class="table-scroll"><table><thead><tr><th>排名</th><th>文件标识</th><th>累计流量（B）</th><th>观测天数</th></tr></thead><tbody><tr v-for="item in result.items" :key="item.fileKey"><td>{{ item.rank }}</td><td class="history-file-key mono">{{ item.fileKey }}</td><td class="mono exact-bytes">{{ item.observedTrafficBytes }}</td><td>{{ item.observedDays }}</td></tr></tbody></table></div><div v-if="!result.items.length" class="empty-panel"><h3>所选窗口没有匹配文件观测</h3><p>合计为 null，不能解释为实际流量为 0B。</p></div></section>
        </div>
        <div class="notice notice--muted history-footer"><span>App 整日无记录：{{ result.missingDates.length ? result.missingDates.join('、') : '查询窗口内无整日缺失' }}</span><span>FAKE 输入仍标记演示；REAL 声明也不证明数据集覆盖 App 全量。未确认 App 全量分母，热点占比不可计算。</span></div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.history-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin:6px 0 18px}.history-heading h2{font-size:18px;margin:0 0 9px;color:#274360}.history-heading p{font-size:12px;color:#8598af;margin:0;line-height:1.7}.history-identity{overflow-wrap:anywhere;flex-wrap:wrap}.history-identity .mono{font-size:11px;min-width:0}.history-filters{flex-wrap:wrap}.history-file-filter{flex:1;min-width:220px}.history-filters input[type=number]{width:75px}.history-result-context{overflow-wrap:anywhere}.history-trend{margin-bottom:20px}.history-tables{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.2fr);gap:20px;margin-bottom:20px}.history-days .table-scroll{max-height:480px}.history-days table{min-width:480px}.history-ranking table{min-width:540px}.history-file-key{max-width:280px;overflow-wrap:anywhere;white-space:normal}.exact-bytes{overflow-wrap:anywhere;max-width:240px;white-space:normal}.history-footer{display:flex;flex-direction:column;align-items:flex-start;line-height:1.8}.history-panel .empty-panel .btn{display:inline-flex;margin-top:12px}.history-panel .chart-foot{line-height:1.7}@media(max-width:1250px){.history-tables{grid-template-columns:1fr}.history-heading{align-items:flex-start;flex-wrap:wrap}}
</style>
