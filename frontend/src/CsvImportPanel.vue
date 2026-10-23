<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import { createCsvImport, createImportMonitor } from './imports.js'

const emit = defineEmits(['dataset-selected'])

let storage = null
try { storage = window.localStorage } catch {}
const upload = createCsvImport({ storage })
const { state } = upload
const monitor = createImportMonitor()
const progress = monitor.state
const datasetId = ref('')
const appId = ref('')
const chosenContext = ref(null)
const report = computed(() => progress.phase === 'FAILED' ? progress.quality : progress.selectedQuality || progress.quality)
const reportMetadata = computed(() => progress.phase === 'FAILED' ? null : progress.selectedDataset || progress.dataset)
const progressLabels = { IDLE: '等待导入', QUERYING: '查询导入', POLLING: '导入处理中', SUCCEEDED: '导入成功', FAILED: '导入失败', PAUSED: '查询暂停', QUERY_ERROR: '查询失败' }
const fileInput = ref(null)
const locked = computed(() => state.busy || state.phase === 'ACCEPTED')
const phaseLabel = computed(() => ({
  IDLE: '等待选择与提交', UPLOADING: '提交中', ACCEPTED: '上传已接收', FAILED: '提交失败'
})[state.phase])

function reset() {
  monitor.dispose()
  upload.reset()
  if (fileInput.value) fileInput.value.value = ''
}

watch(() => state.importTaskId, taskId => { if (taskId) monitor.queryTask(taskId) })
watch(() => progress.dataset, dataset => {
  if (dataset) { datasetId.value = dataset.datasetId; chooseDataset() }
})

async function chooseDataset() {
  appId.value = ''
  chosenContext.value = null
  emit('dataset-selected', null)
  const dataset = await monitor.selectDataset(datasetId.value)
  if (dataset?.datasetType === 'FILE_DAILY' && dataset.appIds.length === 1) appId.value = dataset.appIds[0]
}

function useDataset() {
  const dataset = progress.selectedDataset
  if (!dataset || dataset.datasetType !== 'FILE_DAILY' || dataset.datasetId !== datasetId.value || !dataset.appIds.includes(appId.value)) return
  chosenContext.value = { datasetId: dataset.datasetId, appId: appId.value, datasetSource: dataset.datasetSource, dateRange: dataset.dateRange, appDateRanges: dataset.appDateRanges }
  emit('dataset-selected', chosenContext.value)
}

onMounted(() => { upload.restore(); monitor.loadDatasets() })
onUnmounted(() => { upload.dispose(); monitor.dispose() })
</script>

<template>
  <section class="upload-panel" aria-labelledby="csv-upload-title">
    <header class="upload-heading">
      <div>
        <p class="section-code">CSV / 数据上传</p>
        <h2 id="csv-upload-title">上传流量观测文件</h2>
        <p>CSV直传数据服务；接收后查询导入进度、真实质量报告和 READY 数据集。</p>
      </div>
      <span class="upload-state" :class="`upload-state--${state.phase.toLowerCase()}`" role="status">{{ phaseLabel }}</span>
    </header>

    <form aria-label="CSV 上传" @submit.prevent="upload.submit">
      <fieldset :disabled="locked">
        <legend class="upload-sr-only">文件与导入配置</legend>
        <div class="upload-file">
          <label for="csv-file">CSV 文件</label>
          <input id="csv-file" ref="fileInput" type="file" accept=".csv,text/csv" @change="upload.selectFile($event.target.files?.[0])">
          <p v-if="state.file" class="upload-filename">{{ state.file.name }} <span>{{ state.file.size.toLocaleString() }} B</span></p>
          <p class="upload-hint">上限 200 MiB，支持 UTF-8 / GB18030，由导入服务解析。请先确认实际表头及单位。</p>
        </div>

        <div class="upload-options">
          <label>
            <span>数据类型</span>
            <select :value="state.options.datasetType" @change="upload.setDatasetType($event.target.value)">
              <option value="FILE_DAILY">文件日流量 · FILE_DAILY</option>
              <option value="APP_DAILY_TOTAL">App 每日总流量 · APP_DAILY_TOTAL</option>
            </select>
          </label>
          <label>
            <span>数据来源</span>
            <select v-model="state.options.datasetSource">
              <option value="REAL">真实业务数据 · REAL</option>
              <option value="FAKE">演示数据 · FAKE</option>
            </select>
          </label>
          <label>
            <span>流量单位</span>
            <select v-model="state.options.sourceUnit">
              <option value="">请选择已确认的单位</option>
              <option v-if="state.options.datasetType === 'FILE_DAILY'" value="B">B · 已确认是字节</option>
              <option v-else value="GB_DECIMAL">十进制 GB · 1 GB = 10^9 B</option>
            </select>
          </label>
          <label>
            <span>默认 App ID（无 App 列时必填）</span>
            <input v-model="state.options.appId" type="text" placeholder="不从文件名推断" autocomplete="off">
          </label>
        </div>

        <details class="upload-mapping" open>
          <summary>CSV 字段映射 · 按文件实际列名填写</summary>
          <div v-if="state.options.datasetType === 'FILE_DAILY'" class="upload-mapping-grid">
            <label><span>文件标识列</span><input v-model="state.options.mapping.fileKey" type="text" autocomplete="off"></label>
            <label><span>日期列</span><input v-model="state.options.mapping.date" type="text" autocomplete="off"></label>
            <label><span>流量列</span><input v-model="state.options.mapping.trafficBytes" type="text" autocomplete="off"></label>
            <label><span>App ID 列（可留空）</span><input v-model="state.options.mapping.appId" type="text" autocomplete="off"></label>
          </div>
          <div v-else class="upload-mapping-grid">
            <label><span>日期列</span><input v-model="state.options.mapping.date" type="text" autocomplete="off"></label>
            <label><span>App 每日总流量列（flux GB）</span><input v-model="state.options.mapping.totalTraffic" type="text" autocomplete="off"></label>
            <label><span>App ID 列（可留空）</span><input v-model="state.options.mapping.appId" type="text" autocomplete="off"></label>
          </div>
          <p v-if="state.options.datasetType === 'FILE_DAILY'" class="upload-hint">文件日流量用于热点候选；完整 URL 由 Python 提取 path 作为 fileKey，查询参数和域名不进入业务身份。</p>
          <p v-else class="upload-hint">该数据是独立的全量分母，不会与文件观测求和混用；必须确认 flux 列为十进制 GB，缺日不补零。默认映射适用于“日期、AppId、费用、flux(GB)”。</p>
        </details>
      </fieldset>

      <div v-if="state.error" class="upload-error" role="alert"><strong>未确认接收</strong><p>{{ state.error }}</p></div>

      <div v-if="state.phase === 'ACCEPTED'" class="upload-receipt" aria-live="polite">
        <strong>{{ state.file ? '上传请求已接收' : '已恢复上次上传的接收记录' }}</strong>
        <p>导入任务编号（importTaskId）</p>
        <code>{{ state.importTaskId }}</code>
        <p>状态：{{ progressLabels[progress.phase] }}<template v-if="progress.task"> · {{ progress.task.status }}</template>。202仅代表接收，导入成功以状态及 READY 元数据为准。</p>
        <p v-if="progress.dataset">本次数据集：<code>{{ progress.dataset.datasetId }}</code></p>
        <p v-if="progress.error" role="alert">{{ progress.error }}</p>
        <p v-if="progress.notice" role="status">{{ progress.notice }}</p>
        <button v-if="progress.busy" type="button" class="button button--ghost" @click="monitor.pause()">暂停状态查询</button>
        <button v-else type="button" class="button button--ghost" @click="monitor.resume">重查原导入任务</button>
      </div>

      <footer class="upload-actions">
        <p v-if="state.busy" aria-live="polite">正在上传，请等待接收回执…</p>
        <p v-else-if="state.phase !== 'ACCEPTED'">文件仅提交给数据导入服务，不改变下方演示榜单。</p>
        <p v-else>刷新后会重新 GET 原任务；查询失败不会重新上传文件。</p>
        <button v-if="state.phase === 'ACCEPTED'" class="button button--ghost" type="button" @click="reset">选择另一份文件</button>
        <button v-else class="button button--primary" type="submit" :disabled="state.busy || !state.file">
          {{ state.busy ? '提交中…' : state.phase === 'FAILED' ? '重试上传' : '上传 CSV' }}
        </button>
      </footer>
    </form>

    <section class="dataset-selection" aria-labelledby="dataset-title">
      <h3 id="dataset-title">READY 数据集与 App</h3>
      <p v-if="chosenContext" class="upload-hint">当前使用：{{ chosenContext.datasetId }} / {{ chosenContext.appId }}。这是已确认的选择；新的失败导入不会生成数据集。</p>
      <p v-if="progress.phase === 'FAILED' && chosenContext" class="upload-hint">保留的是此前选择，不是本次失败导入的输出。</p>
      <p v-if="progress.listError" class="upload-error" role="alert">{{ progress.listError }}</p>
      <div class="upload-options">
        <label><span>数据集（第 {{ progress.page }} 页 / 共 {{ progress.total }} 份）</span><select v-model="datasetId" :disabled="progress.listBusy" @change="chooseDataset"><option value="">选择 READY 数据集</option><option v-for="dataset in progress.datasets" :key="dataset.datasetId" :value="dataset.datasetId">{{ dataset.datasetType === 'APP_DAILY_TOTAL' ? '总流量' : '文件日流量' }} · {{ dataset.datasetSource }} · {{ dataset.datasetId }} · {{ dataset.rowCount }}行</option></select></label>
        <label><span>业务 App</span><select v-model="appId" :disabled="progress.selectionBusy || !progress.selectedDataset" @change="emit('dataset-selected', null); chosenContext = null"><option value="">选择 App</option><option v-for="app in progress.selectedDataset?.appIds || []" :key="app" :value="app">{{ app }}</option></select></label>
        <button class="button button--primary" type="button" :disabled="!appId || progress.selectionBusy || progress.selectedDataset?.datasetType !== 'FILE_DAILY'" @click="useDataset">使用所选文件数据集和 App</button>
      </div>
      <div class="upload-actions">
        <button class="button button--ghost" type="button" :disabled="progress.listBusy" @click="monitor.loadDatasets(progress.page)">刷新数据集列表</button>
        <button class="button button--ghost" type="button" :disabled="progress.listBusy || progress.page <= 1" @click="monitor.loadDatasets(progress.page - 1)">上一页数据集</button>
        <button class="button button--ghost" type="button" :disabled="progress.listBusy || progress.page * 20 >= progress.total" @click="monitor.loadDatasets(progress.page + 1)">下一页数据集</button>
      </div>
      <p v-if="progress.selectionError" class="upload-error" role="alert">{{ progress.selectionError }}</p>
      <article v-if="report" class="quality-report" aria-labelledby="quality-title">
        <h3 id="quality-title">{{ progress.phase !== 'FAILED' && progress.selectedDataset ? '所选数据集' : '本次导入' }}质量报告</h3>
        <p v-if="reportMetadata">{{ reportMetadata.datasetId }} · {{ reportMetadata.datasetSource }} · 完整性 {{ reportMetadata.completeness }} · 单位来源 {{ reportMetadata.unitConfirmationSource }}</p>
        <dl><dt>总行数 / 有效行数 / 重复行 / 冲突行 / 错误行</dt><dd>{{ report.totalRows ?? '未知' }} / {{ report.validRows ?? '未知' }} / {{ report.duplicateRows ?? '未知' }} / {{ report.conflictRows ?? '未知' }} / {{ report.invalidRows ?? '未知' }}</dd><dt>编码 / 缺失日期数</dt><dd>{{ report.encoding ?? '未知' }} / {{ report.missingDateCount ?? '未知' }}</dd><dt>数据日期范围</dt><dd>{{ report.dateRange?.start ?? '未知' }} — {{ report.dateRange?.end ?? '未知' }}</dd><dt>App日期范围</dt><dd v-for="(range, app) in report.appDateRanges || reportMetadata?.appDateRanges || {}" :key="app">{{ app }}：{{ range.start }} — {{ range.end }}</dd></dl>
        <ul><li v-for="warning in report.warnings || []" :key="warning">{{ warning }}</li></ul>
        <details v-if="report.missingDates?.length"><summary>缺失日期（{{ report.missingDatesTruncated ? '仅前1000条' : '已返回明细' }}）</summary><p v-for="missing in report.missingDates" :key="`${missing.appId}-${missing.date}`">{{ missing.appId }} / {{ missing.date }}：无观测，未补零</p></details>
        <details v-if="report.dailyRows?.length"><summary>每日观测行数</summary><p v-for="day in report.dailyRows" :key="`${day.appId}-${day.date}`">{{ day.appId }} / {{ day.date }}：{{ day.rowCount }}行</p></details>
      </article>
    </section>
  </section>
</template>

<style scoped>
.upload-panel { margin-bottom: 1.5rem; border: 1px solid rgba(122, 167, 167, 0.25); background: #0a1f27; }
.dataset-selection { padding: 1.5rem; border-top: 1px solid rgba(122, 167, 167, 0.25); }
.dataset-selection h3 { color: #d8e9e7; }
.quality-report { padding: 1rem; background: #06151b; overflow-wrap: anywhere; color: #8facad; font-size: 0.8rem; line-height: 1.8; }
.quality-report dd { margin: 0 0 0.8rem; color: #d8e9e7; }
.quality-report li { color: #ffb178; }
.upload-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; padding: 1.5rem; border-bottom: 1px solid rgba(122, 167, 167, 0.18); }
.upload-heading h2 { margin: 0 0 0.65rem; color: #d8e9e7; font-size: 1.5rem; }
.upload-heading > div > p:last-child { margin: 0; color: #8facad; font-size: 0.85rem; line-height: 1.6; }
.upload-state { flex: 0 0 auto; padding: 0.5rem 0.7rem; color: #8facad; border: 1px solid currentColor; font-size: 0.75rem; }
.upload-state--uploading, .upload-state--accepted { color: #2ed3b7; }
.upload-state--failed { color: #ffb178; }
fieldset { min-width: 0; margin: 0; padding: 1.5rem; border: 0; }
fieldset:disabled { opacity: 0.65; }
.upload-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
label { display: grid; gap: 0.6rem; color: #b6ccca; font-size: 0.8rem; }
input, select { width: 100%; min-width: 0; min-height: 42px; padding: 0.65rem; color: #d8e9e7; border: 1px solid rgba(122, 167, 167, 0.32); border-radius: 0; background: #06151b; }
input::placeholder { color: #78979b; }
input[type='file'] { margin-top: 0.6rem; border-style: dashed; }
input[type='file']::file-selector-button { margin-right: 1rem; padding: 0.4rem 0.7rem; color: #2ed3b7; border: 1px solid rgba(46, 211, 183, 0.34); background: #0a1f27; cursor: pointer; }
.upload-filename { display: flex; flex-wrap: wrap; gap: 1rem; margin: 0.75rem 0 0; overflow-wrap: anywhere; font-family: 'Cascadia Code', monospace; font-size: 0.8rem; }
.upload-filename span { color: #8facad; }
.upload-hint { margin: 0.75rem 0 0; color: #8facad; font-size: 0.75rem; line-height: 1.7; }
.upload-options { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1rem; margin-top: 1.5rem; }
.upload-mapping { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid rgba(122, 167, 167, 0.18); }
.upload-mapping summary { color: #b6ccca; font-size: 0.8rem; cursor: pointer; }
.upload-mapping-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1rem; margin-top: 1rem; }
.upload-error, .upload-receipt { margin: 0 1.5rem 1.5rem; padding: 1rem; border-left: 3px solid currentColor; background: #06151b; }
.upload-error { color: #ffb178; }
.upload-receipt { color: #2ed3b7; }
.upload-error p, .upload-receipt p { margin: 0.6rem 0 0; font-size: 0.8rem; line-height: 1.7; }
.upload-receipt p { color: #8facad; }
.upload-receipt code { display: block; margin-top: 0.5rem; overflow-wrap: anywhere; white-space: normal; color: #d8e9e7; font-size: 0.9rem; }
.upload-actions { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1rem 1.5rem; border-top: 1px solid rgba(122, 167, 167, 0.18); }
.upload-actions p { margin: 0; color: #8facad; font-size: 0.8rem; line-height: 1.6; }
@media (max-width: 900px) { .upload-options, .upload-mapping-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .upload-heading, .upload-actions { align-items: stretch; flex-direction: column; } .upload-state { align-self: flex-start; } .upload-options, .upload-mapping-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
