<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import Top50Table from './Top50Table.vue'
import FileDetailDialog from './components/FileDetailDialog.vue'
import { createPredictionLookup, createPredictionSubmit, resultRows } from './predictions.js'
import { resolveResultMode } from './prediction-results.js'
import { fetchFileMetadata } from './files.js'
import { formatBytes } from './top50.js'

const props = defineProps({ dataset: { type: Object, default: null } })
const emit = defineEmits(['run-loaded'])

let storage = null
try { storage = window.localStorage } catch {}
const lookup = createPredictionLookup({ storage })
const { state } = lookup
const submission = createPredictionSubmit({ storage })
const submitState = submission.state
const form = reactive({ cutoffDate: '', horizonDays: 1, modelVersion: 'contract-demo-v1' })
const dateRange = computed(() => props.dataset?.appDateRanges?.[props.dataset.appId])
const kind = ref('task')
const id = ref('')
const detailFile = ref(null)
const detailAction = ref('details')
const fileMetadata = ref({})
const metadataBusy = ref(false)
const metadataError = ref('')
let metadataGeneration = 0
watch(() => state.result?.runId, () => {
  metadataGeneration++
  fileMetadata.value = {}
  metadataBusy.value = false
  metadataError.value = ''
  detailFile.value = null
})
async function loadFileSizes() {
  if (metadataBusy.value || state.result?.datasetSource !== 'REAL' || !rows.value.length) return
  const generation = ++metadataGeneration
  const run = state.result
  metadataBusy.value = true
  metadataError.value = ''
  try {
    const items = await fetchFileMetadata(run, rows.value.map(item => item.fileKey))
    if (generation === metadataGeneration) fileMetadata.value = Object.fromEntries(items.map(item => [item.fileKey, item]))
  } catch (error) { if (generation === metadataGeneration) metadataError.value = error.message }
  finally { if (generation === metadataGeneration) metadataBusy.value = false }
}
function openDetail(item, action) { detailAction.value = action; detailFile.value = item }
const rows = computed(() => state.result ? resultRows(state.result) : [])
const resultMode = computed(() => state.result ? resolveResultMode(state.result) : null)
const statusLabels = { PENDING: '等待处理', RUNNING: '处理中', SUCCEEDED: '业务任务成功', FAILED: '业务任务失败' }
const phaseLabels = { QUERYING: '正在查询', POLLING: '自动查询中', LOADING_RESULT: '正在读取榜单', READY: '榜单已读取', PAUSED: '查询已暂停', QUERY_ERROR: '查询失败', TASK_FAILED: '业务任务失败', IDLE: '等待编号' }

watch(() => [state.kind, state.id], ([savedKind, savedId]) => { kind.value = savedKind; id.value = savedId })
watch(() => props.dataset, () => { form.cutoffDate = dateRange.value?.end || '' }, { immediate: true })
watch(() => state.resultIsCurrent && state.result, result => { if (result) emit('run-loaded', result) })
async function submitPrediction(retry = false) {
  if (submitState.busy) return
  if (state.busy) lookup.pause()
  state.resultIsCurrent = false
  const taskId = retry ? await submission.retry() : await submission.submit({
    datasetId: props.dataset?.datasetId, appId: props.dataset?.appId, ...form
  }, props.dataset)
  if (taskId) return lookup.queryTask(taskId)
}
function query() {
  if (state.busy || submitState.busy) return
  return kind.value === 'task' ? lookup.queryTask(id.value) : lookup.queryRun(id.value)
}
onMounted(() => {
  submission.restore()
  const params = new URLSearchParams(window.location.search)
  lookup.restore({ taskId: params.get('taskId'), runId: params.get('runId') })
})
onUnmounted(() => { metadataGeneration++; lookup.dispose(); submission.dispose() })
</script>

<template>
  <section id="prediction" class="prediction-panel" aria-labelledby="prediction-title" :aria-busy="state.busy">
    <header class="prediction-heading">
      <div><p class="section-code">TASK → RUN → SNAPSHOT</p><h2 id="prediction-title">预测任务与本次榜单</h2></div>
      <span class="lookup-phase" role="status">{{ phaseLabels[state.phase] }}</span>
    </header>
    <form class="submission-form" aria-label="提交日周预测" @submit.prevent="submitPrediction()">
      <p v-if="props.dataset">所选数据集 {{ props.dataset.datasetId }} / App {{ props.dataset.appId }} · {{ props.dataset.datasetSource }}</p>
      <p v-else class="lookup-help">请先在上方使用 READY 数据集和 App，再提交预测。</p>
      <fieldset :disabled="submitState.busy || !props.dataset">
        <legend>预测参数</legend>
        <div class="lookup-form">
          <label><span>特征截止日</span><input v-model="form.cutoffDate" type="date" :min="dateRange?.start" :max="dateRange?.end" required></label>
          <label><span>目标跨度</span><select v-model.number="form.horizonDays"><option :value="1">日预测 · 下一自然日</option><option :value="7">周预测 · 后续连续七天</option></select></label>
          <label><span>模型版本（须已部署）</span><input v-model="form.modelVersion" autocomplete="off" required maxlength="128"></label>
          <button class="button button--primary" type="submit">{{ submitState.busy ? '正在提交…' : '提交预测任务' }}</button>
        </div>
      </fieldset>
      <p class="lookup-help">默认版本 contract-demo-v1 用于 Mock 链路。其他版本仅在确认当前 Python 部署支持后填写；最终来源由后端响应确定。</p>
      <p v-if="submitState.error" class="lookup-message" role="alert">{{ submitState.error }}</p>
      <p v-if="submitState.request" class="lookup-help">{{ submitState.restored ? '已恢复上次提交参数，未自动POST' : '已冻结本次提交参数' }}：{{ submitState.request.datasetId }} / {{ submitState.request.appId }} / {{ submitState.request.cutoffDate }} / 后续{{ submitState.request.horizonDays }}天 / {{ submitState.request.modelVersion }}</p>
      <button v-if="submitState.hasRetry" type="button" class="button button--ghost" :disabled="submitState.busy" @click="submitPrediction(true)">重试本次提交（原参数与原键）</button>
      <p v-if="submitState.taskId" class="lookup-help">Java已接收任务 {{ submitState.taskId }}。202仅为接收，成功与榜单以随后GET为准。</p>
    </form>
    <p class="lookup-copy">也可查询已有的 Java 任务；仅在业务任务成功后读取对应榜单，来源与下方 Fake 原型独立。</p>
    <form class="lookup-form" aria-label="查询预测任务或榜单" @submit.prevent="query">
      <label><span>查询方式</span><select v-model="kind" :disabled="state.busy || submitState.busy"><option value="task">taskId · 跟踪任务</option><option value="run">runId · 读取榜单</option></select></label>
      <label class="lookup-id"><span>{{ kind === 'task' ? 'Java taskId' : 'runId' }}</span><input v-model="id" :disabled="state.busy || submitState.busy" :placeholder="kind === 'task' ? '输入已有预测任务编号' : '输入已发布榜单编号'" maxlength="128" required autocomplete="off"></label>
      <button class="button button--primary" type="submit" :disabled="state.busy || submitState.busy || !id.trim()">{{ state.busy ? '查询中' : '查询' }}</button>
      <button v-if="state.busy" class="button button--ghost" type="button" @click="lookup.pause">暂停查询</button>
      <button v-else-if="state.id" class="button button--ghost" type="button" :disabled="submitState.busy" @click="lookup.resume">重新查询原编号</button>
    </form>
    <p class="lookup-help">每 2 秒查询一次，自动查询最多持续 5 分钟；暂停或网络失败不会取消后台任务。刷新后重新查询最近编号。</p>
    <div v-if="state.error" class="lookup-message lookup-message--error" role="alert"><strong>{{ state.phase === 'TASK_FAILED' ? 'Java 业务任务失败' : '查询未完成' }}</strong><p>{{ state.error }}</p></div>
    <p v-if="state.notice" class="lookup-message" role="status">{{ state.notice }}</p>

    <div v-if="state.task" class="task-summary">
      <div class="task-state"><strong>{{ statusLabels[state.task.status] }}</strong><code>{{ state.task.status }} / {{ state.task.stage || '—' }}</code></div>
      <dl class="identity-grid">
        <div><dt>taskId</dt><dd>{{ state.task.taskId }}</dd></div>
        <div><dt>datasetId</dt><dd>{{ state.task.datasetId }}</dd></div>
        <div><dt>App / 截止日</dt><dd>{{ state.task.appId }} / {{ state.task.cutoffDate }}</dd></div>
        <div><dt>目标窗口 / 模型</dt><dd>后续 {{ state.task.horizonDays }} 天 / {{ state.task.modelVersion }}</dd></div>
        <div v-if="state.task.runId"><dt>runId</dt><dd>{{ state.task.runId }}</dd></div>
      </dl>
      <p v-if="state.task.lastDependencyError" class="dependency-warning">最近依赖异常：{{ state.task.lastDependencyError.code }} · {{ state.task.lastDependencyError.message }}。最终状态以 Java 为准。</p>
    </div>

    <article v-if="state.result" class="run-snapshot" aria-labelledby="snapshot-title">
      <p v-if="!state.resultIsCurrent" class="lookup-message">以下保留的是上一份已读取榜单（runId：{{ state.result.runId }}），不是本次查询结果。</p>
      <header class="snapshot-heading"><h3 id="snapshot-title">{{ state.resultIsCurrent ? '本次查询榜单' : '上一份榜单' }}</h3><div class="source-tags"><span>{{ state.result.datasetSource }} · {{ state.result.datasetSource === 'REAL' ? '真实数据' : '演示数据' }}</span><span :class="{ 'source-mock': resultMode === 'MOCK' }">结果模式：{{ resultMode }}</span><span :class="{ 'source-mock': state.result.predictionSource === 'MOCK' }">{{ state.result.predictionSource }} · {{ state.result.predictionSource === 'MOCK' ? '仅模拟计算，非真实算法效果' : '模型计算' }}</span></div></header>
      <dl class="identity-grid">
        <div><dt>runId <a :href="`?runId=${encodeURIComponent(state.result.runId)}#prediction`">固定链接</a></dt><dd>{{ state.result.runId }}</dd></div>
        <div><dt>datasetId / App</dt><dd>{{ state.result.datasetId }} / {{ state.result.appId }}</dd></div>
        <div><dt>截止日 / 模型</dt><dd>{{ state.result.cutoffDate }} / {{ state.result.modelVersion }}</dd></div>
        <div><dt>预测窗口（含首尾日）</dt><dd>{{ state.result.predictionStartDate }} — {{ state.result.predictionEndDate }} · {{ state.result.horizonDays === 7 ? '连续 7 天累计，非自然周' : '下一自然日' }}</dd></div>
        <div><dt>候选数 / 榜单条数</dt><dd>{{ state.result.candidateCount }} / {{ state.result.top50Count }}，不补足 50 条</dd></div>
        <div><dt>Top50 目标窗口累计流量</dt><dd>{{ state.result.top50PredictedBytes }} B</dd></div>
        <div><dt>热点流量占比</dt><dd>不可计算</dd><small>缺少已确认且匹配的全量流量分母；Mock 不作为真实算法效果指标。</small></div>
      </dl>
      <div v-if="state.result.datasetSource === 'REAL' && rows.length" class="file-size-tools"><button type="button" class="btn" :disabled="metadataBusy" @click="loadFileSizes">{{ metadataBusy ? '查询实际大小中…' : '查询 / 刷新实际文件大小' }}</button><span>从 CStore 按需查询；不改变预测快照。</span></div>
      <p v-if="metadataError" class="lookup-message" role="alert">{{ metadataError }}</p>
      <Top50Table v-if="rows.length" :items="rows" show-exact-bytes show-actions>
        <template #file-size="{item}"><template v-if="fileMetadata[item.fileKey]?.status === 'READY'">{{ formatBytes(fileMetadata[item.fileKey].fileSizeBytes) }}<small class="exact-file-size">{{ fileMetadata[item.fileKey].fileSizeBytes }} B · {{ fileMetadata[item.fileKey].dataSource }}</small></template><span v-else>{{ fileMetadata[item.fileKey] ? '大小不可用' : state.result.datasetSource === 'REAL' ? '待查询' : formatBytes(item.fileSizeBytes) }}</span></template>
        <template #actions="{item}"><div class="row-actions"><button class="text-button" @click="openDetail(item,'preview')">预览</button><button class="text-button" @click="openDetail(item,'download')">下载</button><button class="text-button" @click="openDetail(item,'details')">查看详情</button><button class="text-button" @click="openDetail(item,'compression')">标记压缩</button></div></template>
      </Top50Table>
      <p v-else class="lookup-message">本次运行没有候选文件，实际展示 0 条；不会生成文件补足榜单。</p>
    </article>
    <FileDetailDialog :file="detailFile" :run="state.result" :action="detailAction" @close="detailFile=null" />
  </section>
</template>

<style scoped>
.file-size-tools { display:flex; align-items:center; gap:12px; margin:14px 0; flex-wrap:wrap; font-size:12px; color:#8499b2; }
.exact-file-size { display:block; font-size:10px; margin-top:5px; overflow-wrap:anywhere; }
.prediction-panel { margin: 1.5rem 0 2.5rem; padding: 1.5rem; border: 1px solid rgba(46, 211, 183, 0.3); background: #0a1f27; scroll-margin-top: 90px; min-width: 0; }
.submission-form { padding: 1rem; margin-top: 1rem; background: #06151b; color: #8facad; font-size: 0.8rem; overflow-wrap: anywhere; }
.submission-form fieldset { margin: 1rem 0; border: 1px solid rgba(122,167,167,0.2); padding: 1rem; }
.prediction-heading, .snapshot-heading { display: flex; justify-content: space-between; align-items: center; gap: 1rem; flex-wrap: wrap; }
h2, h3 { margin: 0; color: #d8e9e7; }
h2 { font-size: 1.35rem; }
h3 { font-size: 1.1rem; }
.lookup-phase, .source-tags span { padding: 0.45rem 0.6rem; border: 1px solid rgba(46, 211, 183, 0.3); color: #2ed3b7; font-size: 0.72rem; }
.lookup-copy { margin: 1rem 0; color: #8facad; font-size: 0.85rem; line-height: 1.7; }
.lookup-form { display: flex; align-items: end; gap: 0.8rem; flex-wrap: wrap; }
.lookup-form label { flex: 0 1 200px; min-width: 0; }
.lookup-form .lookup-id { flex: 1 1 280px; }
.lookup-form label > span { display: block; margin-bottom: 0.5rem; color: #8facad; font-size: 0.75rem; }
.lookup-help { margin: 0.8rem 0 0; color: #8facad; font-size: 0.72rem; line-height: 1.6; }
.lookup-message { margin: 1rem 0 0; padding: 1rem; color: #ffb178; border-left: 2px solid currentColor; background: rgba(255, 177, 120, 0.04); font-size: 0.83rem; overflow-wrap: anywhere; }
.lookup-message p { margin: 0.4rem 0 0; }
.lookup-message--error { color: #ddaaa0; }
.task-summary, .run-snapshot { margin-top: 1.5rem; padding-top: 1.5rem; border-top: 1px solid rgba(122, 167, 167, 0.18); }
.task-state { display: flex; align-items: center; flex-wrap: wrap; gap: 1rem; }
.task-state strong { color: #2ed3b7; }
.identity-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem 1.5rem; margin: 1.25rem 0; }
dt { margin-bottom: 0.4rem; color: #8facad; font-size: 0.72rem; }
dd { margin: 0; color: #d8e9e7; font: 0.78rem/1.6 "Cascadia Code", monospace; overflow-wrap: anywhere; }
.identity-grid small, .dependency-warning { color: #ffb178; font-size: 0.72rem; line-height: 1.6; }
.dependency-warning { margin-bottom: 0; }
.source-tags { display: flex; gap: 0.5rem; flex-wrap: wrap; }
.source-tags .source-mock { color: #ffb178; border-color: rgba(255, 177, 120, 0.4); }
a { color: #2ed3b7; margin-left: 0.5rem; }
@media (max-width: 720px) { .prediction-panel { padding: 1rem; } .identity-grid { grid-template-columns: 1fr; } .lookup-form label, .lookup-form .lookup-id { flex-basis: 100%; } }
</style>
