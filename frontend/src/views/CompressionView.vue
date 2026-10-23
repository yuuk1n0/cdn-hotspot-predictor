<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import MetricCard from '../components/MetricCard.vue'
import FileDetailDialog from '../components/FileDetailDialog.vue'
import { listRunCompressions } from '../compressions.js'
import { operationsApi } from '../operations.js'
import { formatBytes, compressionStatusLabels } from '../top50.js'
import { uiContextKey } from '../ui/context.js'
import { appOptions, getMockFiles, mockApps } from '../ui/mock-data.js'

const { previewAppId } = inject(uiContextKey, { previewAppId: ref('10350') })
const mode = ref('real')
const granularity = ref('DAILY')
const status = ref('ALL')
const run = ref(null)
const pending = ref(null)
const records = ref([])
const effects = ref(null)
const loading = ref(false)
const error = ref('')
const detailFile = ref(null)
let generation = 0
let controller

const app = computed(() => mockApps[previewAppId.value])
const mockItems = computed(() => getMockFiles(previewAppId.value))
const recordMap = computed(() => Object.fromEntries(records.value.map(record => [record.fileKey, record])))
const allRealItems = computed(() => (pending.value?.items || []).map(item => ({ ...item, status: item.compressionStatus || 'PENDING' })))
const items = computed(() => allRealItems.value.filter(item => status.value === 'ALL' || item.status === status.value))
const pendingCount = computed(() => allRealItems.value.filter(item => item.status !== 'COMPRESSED').length)
const processingCount = computed(() => allRealItems.value.filter(item => item.status === 'PROCESSING').length)
const compressedCount = computed(() => allRealItems.value.filter(item => item.status === 'COMPRESSED').length)
const predictedTotal = computed(() => allRealItems.value.reduce((sum, item) => sum + BigInt(item.predictedBytes || '0'), 0n).toString())

async function load() {
  generation++
  controller?.abort()
  const current = generation
  const abort = new AbortController()
  controller = abort
  loading.value = true
  error.value = ''
  run.value = pending.value = effects.value = null
  records.value = []
  try {
    run.value = await operationsApi.latest(previewAppId.value, granularity.value === 'WEEKLY' ? 7 : 1, fetch, abort.signal)
    const [pendingResult, recordResult, effectResult] = await Promise.all([
      operationsApi.pending(previewAppId.value, run.value.runId, fetch, abort.signal),
      listRunCompressions(run.value, fetch),
      operationsApi.effects(previewAppId.value, fetch, abort.signal)
    ])
    if (current !== generation || abort.signal.aborted) return
    pending.value = pendingResult
    records.value = recordResult
    effects.value = effectResult
  } catch (requestError) {
    if (current === generation && !abort.signal.aborted) error.value = requestError.status === 404 ? '尚无匹配粒度的真实成功预测运行；没有用 UI Mock 补位。' : requestError.message || '压缩业务数据查询失败。'
  } finally {
    if (current === generation) loading.value = false
  }
}

function closeDetail() { detailFile.value = null; load() }
watch([previewAppId, granularity], load, { immediate: true })
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>

<template>
  <div data-page="compression">
    <header class="page-heading"><div><h1>压缩降本决策</h1><p>从真实 Top50 运行登记处理结果；预测收益、测试证据和实际账单收益分开呈现。</p></div><div class="segmented segmented--plain" aria-label="压缩页面数据来源"><button :class="{ active: mode === 'real' }" @click="mode = 'real'">真实业务</button><button :class="{ active: mode === 'mock' }" @click="mode = 'mock'">UI 演示</button></div></header>

    <template v-if="mode === 'real'">
      <section class="card filter-card"><div class="filter-field"><label for="compression-app">AppID</label><select id="compression-app" v-model="previewAppId"><option v-for="option in appOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></div><div class="filter-field"><label for="compression-status">处理状态</label><select id="compression-status" v-model="status"><option value="ALL">全部状态</option><option value="PENDING">待处理</option><option value="PROCESSING">处理中</option><option value="COMPRESSED">已压缩</option></select></div><div class="segmented filter-spacer"><button :class="{ active: granularity === 'DAILY' }" @click="granularity = 'DAILY'">日榜</button><button :class="{ active: granularity === 'WEEKLY' }" @click="granularity = 'WEEKLY'">周榜</button></div><button class="btn" :disabled="loading" @click="load"><AppIcon name="refresh" :size="14"/>{{ loading ? '刷新中…' : '刷新' }}</button></section>
      <div v-if="error" class="notice notice--amber" role="alert"><AppIcon name="info" :size="16"/>{{ error }}</div>
      <div v-if="run" class="context-note"><span>App {{ previewAppId }} · {{ run.predictionStartDate }} — {{ run.predictionEndDate }} · {{ run.modelVersion }}</span><span class="mono">{{ run.runId }}</span></div>
      <section class="metric-grid metric-grid--five"><MetricCard label="待压缩文件" :value="pendingCount" unit="个" icon="compression" note="来自当前真实运行 Top50 与登记状态"/><MetricCard label="处理中" :value="processingCount" unit="个" icon="clock" note="已登记 PROCESSING"/><MetricCard label="已压缩" :value="compressedCount" unit="个" icon="check" note="已登记 COMPRESSED"/><MetricCard label="预测窗口累计" :value="allRealItems.length ? formatBytes(predictedTotal) : '—'" icon="trend" note="仅当前运行 Top50，不等于实际节省"/><MetricCard label="实际账单降本" value="未验证" icon="money" tone="green" :note="effects?.reasonCodes?.join('、') || '等待真实观测与成本证据'"/></section>
      <section class="card table-card"><header class="card-heading"><div><h2>真实压缩候选与登记状态</h2><p class="compression-table-copy">点击“登记/详情”进入文件详情；原大小仅接受人工 TEST 证据。</p></div><span class="source-badge">{{ items.length }} 条</span></header><div class="table-scroll"><table><thead><tr><th>排名</th><th>FileKey</th><th>预测流量</th><th>处理状态</th><th>证据来源</th><th>处理人</th><th>操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.fileKey"><td>{{ item.rank }}</td><td><div class="file-key" :title="item.fileKey">{{ item.fileKey }}</div><small class="file-subtitle">App {{ item.appId }} · {{ run?.modelVersion }}</small></td><td>{{ formatBytes(item.predictedBytes) }}</td><td><span class="status-pill" :class="`status-pill--${String(item.status).toLowerCase()}`">{{ compressionStatusLabels[item.status] || item.status }}</span></td><td>{{ recordMap[item.fileKey]?.evidenceSource || '未登记' }}</td><td>{{ recordMap[item.fileKey]?.handler || '—' }}</td><td><button class="text-button" @click="detailFile = item">{{ item.status === 'COMPRESSED' ? '查看记录' : '登记处理' }}</button></td></tr></tbody></table></div><div v-if="!items.length && !loading" class="empty-panel"><AppIcon name="compression" :size="36"/><h3>当前筛选没有文件</h3><p>没有用演示文件填充该列表。</p></div><footer class="table-footer"><span>预测总额与实际节省独立；压缩率与收益未凭空估算。</span><span>{{ run?.runId || '' }}</span></footer></section>
      <div class="compression-info-grid"><section class="card"><header class="card-heading"><h2>从推荐到效果追踪</h2><span class="source-badge source-badge--muted">真实流程</span></header><div class="compression-steps"><div><span>1</span><strong>真实 Top50</strong><small>runId 对应快照</small></div><AppIcon name="chevron" :size="14"/><div><span>2</span><strong>登记处理证据</strong><small>REAL 或显式 TEST</small></div><AppIcon name="chevron" :size="14"/><div><span>3</span><strong>成熟观测</strong><small>实际效果与账单</small></div></div></section><section class="card compression-evidence"><AppIcon name="info" :size="24"/><div><h3>收益证据边界</h3><p>{{ effects?.reasonCodes?.includes('NO_COMPRESSION_OBSERVATION') ? '当前尚未登记效果观测，实际节省金额保持未验证。' : '已读取压缩效果接口；仍需核对观测窗口、承接比例与成本。' }}</p><span class="status-pill status-pill--unknown">真实账单降本未验证</span></div></section></div>
      <FileDetailDialog :file="detailFile" :run="run" action="compression" @close="closeDetail"/>
    </template>

    <template v-else>
      <div class="notice notice--amber">UI MOCK 仅用于版式展示，不读写压缩登记。</div>
      <section class="metric-grid metric-grid--five"><MetricCard label="可压缩文件数" :value="app.pending" unit="个" icon="compression" note="固定样例"/><MetricCard label="预计节省流量" :value="app.savedTraffic" unit="TB" icon="file" note="固定样例"/><MetricCard label="预计节省金额" :value="`¥ ${app.forecastSaving}`" icon="money" tone="green" note="固定样例"/><MetricCard label="已完成压缩数" :value="app.completed" unit="个" icon="check" note="固定样例"/><MetricCard label="实际节省金额" value="—" icon="money" note="未验证"/></section>
      <section class="card table-card"><header class="card-heading"><h2>压缩推荐样例</h2><span class="source-badge">UI MOCK</span></header><div class="table-scroll"><table><tbody><tr v-for="item in mockItems" :key="item.fileKey"><td><div class="file-key">{{ item.fileKey }}</div></td><td>{{ formatBytes(item.predictedBytes) }}</td><td>{{ compressionStatusLabels[item.compressionStatus] }}</td><td>样例</td></tr></tbody></table></div></section>
    </template>
  </div>
</template>

<style scoped>
.compression-table-copy{font-size:10px;color:#91a3ba;margin:8px 0 1px}.compression-info-grid{display:grid;grid-template-columns:1.5fr 1fr;gap:18px;margin-top:18px}.compression-steps{display:flex;align-items:center;justify-content:space-around;padding:10px 20px 26px;gap:15px}.compression-steps>div{display:flex;align-items:center;flex-direction:column;gap:8px;text-align:center}.compression-steps>div>span{background:#edf5ff;color:#1677ff;width:28px;height:28px;display:grid;place-items:center;border-radius:50%;font-size:11px;margin-bottom:4px}.compression-steps strong{font-size:11px;color:#6a85a7;font-weight:500}.compression-steps small{font-size:9px;color:#a1afc0}.compression-steps>svg{color:#b9c8dc}.compression-evidence{display:flex;align-items:flex-start;gap:15px;padding:24px;color:#80a8da}.compression-evidence h3{font-size:13px;color:#5a769a;font-weight:550}.compression-evidence p{font-size:11px;color:#8ea1b9;line-height:1.9}.compression-evidence>.svg{flex:none}@media(max-width:1200px){.compression-info-grid{grid-template-columns:1fr}.compression-steps{justify-content:center;gap:30px}}@media(max-width:760px){.compression-steps{gap:12px;padding-left:12px;padding-right:12px}.context-note{flex-wrap:wrap}}
</style>
