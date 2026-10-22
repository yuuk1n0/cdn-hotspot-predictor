<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import MetricCard from '../components/MetricCard.vue'
import { operationsApi } from '../operations.js'
import { uiContextKey } from '../ui/context.js'
import { appOptions } from '../ui/mock-data.js'

const { previewAppId } = inject(uiContextKey, { previewAppId: ref('10350') })
const granularity = ref('DAILY')
const evidence = ref(null)
const loading = ref(false)
const error = ref('')
let generation = 0
let controller

const labels = {
  recallAt50: 'Recall@50',
  ndcgAt50: 'NDCG@50',
  observedTrafficCoverageAt50: 'Top50 观测流量覆盖率',
  candidateCoverageAt50: 'Top50 候选覆盖率'
}

const metricRows = computed(() => {
  const definitions = evidence.value?.metricDefinitions || {}
  const values = evidence.value?.proxyMetrics
  return Object.keys({ ...labels, ...definitions }).map(key => ({
    key,
    label: labels[key] || key,
    definition: definitions[key] || '部署配置未提供该指标定义。',
    value: values?.[key] ?? null,
    verified: values?.[key] != null
  }))
})
const report = computed(() => evidence.value?.report || null)
const metricsAvailable = computed(() => Boolean(evidence.value?.proxyMetrics))
const formalEligible = computed(() => evidence.value?.formalSelectionEligible === true)
const windowText = computed(() => {
  if (!report.value) return '未提供评价窗口'
  return `${report.value.cutoffStartDate} — ${report.value.cutoffEndDate}；实际观测 ${report.value.observationStartDate} — ${report.value.observationEndDate}`
})

function percent(value) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`
}

function decimal(value) {
  return value == null ? '—' : Number(value).toFixed(4)
}

async function load() {
  generation++
  controller?.abort()
  const current = generation
  const abort = new AbortController()
  controller = abort
  loading.value = true
  error.value = ''
  evidence.value = null
  try {
    const result = await operationsApi.modelMetrics(previewAppId.value, granularity.value === 'WEEKLY' ? 7 : 1, fetch, abort.signal)
    if (current === generation && !abort.signal.aborted) evidence.value = result
  } catch (requestError) {
    if (current === generation && !abort.signal.aborted) {
      error.value = requestError.message || '真实模型证据查询失败；未使用静态样例代替。'
    }
  } finally {
    if (current === generation) loading.value = false
  }
}

watch([previewAppId, granularity], load, { immediate: true })
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>

<template>
  <div data-page="backtest">
    <header class="page-heading">
      <div><h1>模型回测与比较</h1><p>读取已部署配置中的历史离线评估报告；不在线重跑回测，也不把代理指标当作正式选模结论。</p></div>
      <span class="source-badge">REAL REPORT · 只读</span>
    </header>

    <section class="card filter-card">
      <div class="filter-field"><label for="backtest-app">AppID</label><select id="backtest-app" v-model="previewAppId"><option v-for="option in appOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></div>
      <div class="segmented filter-spacer" aria-label="回测粒度"><button :class="{ active: granularity === 'DAILY' }" @click="granularity = 'DAILY'">日粒度</button><button :class="{ active: granularity === 'WEEKLY' }" @click="granularity = 'WEEKLY'">周粒度</button></div>
      <button class="btn" :disabled="loading" @click="load"><AppIcon name="refresh" :size="14"/>{{ loading ? '查询中' : '刷新部署报告' }}</button>
    </section>

    <div v-if="error" class="notice notice--amber" role="alert"><AppIcon name="info" :size="16"/>{{ error }}</div>
    <div v-else-if="loading" class="notice notice--muted" role="status">正在读取真实模型证据…</div>

    <template v-if="evidence">
      <div class="notice notice--amber" role="status">
        <AppIcon name="info" :size="16"/>
        <span>{{ metricsAvailable ? '以下为部署配置内保存的历史离线代理指标，不是本次请求重新计算的结果。' : '部署配置未提供可用的离线评估指标；缺失值保持未验证。' }}</span>
      </div>

      <section class="metric-grid">
        <MetricCard label="Recall@50" :value="percent(evidence.proxyMetrics?.recallAt50)" icon="file" :note="metricsAvailable ? '历史离线代理指标' : '尚未验证'"/>
        <MetricCard label="NDCG@50" :value="decimal(evidence.proxyMetrics?.ndcgAt50)" icon="chart" :note="metricsAvailable ? 'log1p 观测流量增益' : '尚未验证'"/>
        <MetricCard label="Top50 流量覆盖率" :value="percent(evidence.proxyMetrics?.observedTrafficCoverageAt50)" icon="pie" tone="green" :note="metricsAvailable ? '观测未来流量口径' : '尚未验证'"/>
        <MetricCard label="MAPE" value="—" icon="backtest" note="部署报告未提供，未以其他指标替代"/>
      </section>

      <div class="backtest-grid">
        <section class="card evidence-card">
          <header class="card-heading"><div><h2>证据窗口与来源</h2><span class="table-context">{{ windowText }}</span></div><span class="source-badge source-badge--muted">{{ report?.source || 'NO_REPORT' }}</span></header>
          <dl class="evidence-list">
            <dt>App / 粒度</dt><dd>{{ evidence.appId }} · {{ evidence.horizonDays === 7 ? 'WEEK' : 'DAY' }}</dd>
            <dt>部署模型</dt><dd>{{ evidence.deployedModelVersion }} · {{ evidence.deployedMode }}</dd>
            <dt>选型方法</dt><dd>{{ evidence.selectedMethod || '未提供' }}</dd>
            <dt>指标来源</dt><dd>{{ evidence.metricSource || 'NONE' }}</dd>
            <dt>标签口径</dt><dd>{{ evidence.labelBasis || 'UNCONFIRMED' }}</dd>
            <dt>报告新鲜度</dt><dd>{{ report?.freshness || 'NO_REPORT' }} · age {{ report?.ageDays ?? '—' }} 天</dd>
            <dt>本次重算</dt><dd>{{ report?.generatedByThisRequest === true ? '是' : '否' }}</dd>
            <dt>正式选模资格</dt><dd>{{ formalEligible ? '是（需人工确认）' : '否' }}</dd>
          </dl>
          <div class="reason-list"><span v-for="reason in evidence.reasonCodes" :key="reason">{{ reason }}</span></div>
        </section>

        <section class="card evidence-card">
          <header class="card-heading"><h2>指标口径与验证状态</h2><span class="source-badge source-badge--muted">未确认标签不计为正式成绩</span></header>
          <div class="table-scroll"><table><thead><tr><th>指标</th><th>值</th><th>状态</th><th>口径</th></tr></thead><tbody><tr v-for="row in metricRows" :key="row.key"><td><strong>{{ row.label }}</strong></td><td>{{ row.value == null ? '—' : row.key === 'ndcgAt50' ? decimal(row.value) : percent(row.value) }}</td><td><span class="status-pill" :class="row.verified ? 'status-pill--processing' : 'status-pill--unknown'">{{ row.verified ? '历史代理报告' : '尚未验证' }}</span></td><td class="metric-definition">{{ row.definition }}</td></tr></tbody></table></div>
        </section>
      </div>

      <section class="card conclusion-card">
        <AppIcon name="backtest" :size="24"/>
        <div><strong>{{ formalEligible ? '存在可进入人工复核的评估证据' : '当前证据不能用于正式选模' }}</strong><p>正式结论需要同一 App、同一跨度、成熟观测窗口及完整标签；本页只展示已部署报告，不自动切换模型。</p></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.backtest-grid{display:grid;grid-template-columns:1fr 1.35fr;gap:18px}.evidence-card{padding:20px}.evidence-card .card-heading{align-items:flex-start}.evidence-list{display:grid;grid-template-columns:112px 1fr;gap:11px 10px;margin:16px 0;font-size:11px}.evidence-list dt{color:#91a3b9}.evidence-list dd{margin:0;color:#506b8c;overflow-wrap:anywhere}.reason-list{display:flex;flex-wrap:wrap;gap:7px}.reason-list span{font-size:9px;color:#8a6d3d;background:#fff7e8;border:1px solid #f3dfb9;border-radius:4px;padding:4px 6px}.metric-definition{max-width:320px;color:#8193a9;line-height:1.6}.conclusion-card{display:flex;align-items:flex-start;gap:15px;padding:20px;margin-top:18px;color:#5d7ca3}.conclusion-card strong{font-size:13px;color:#3f5f86}.conclusion-card p{margin:7px 0 0;font-size:11px;color:#8b9db3;line-height:1.8}@media(max-width:1050px){.backtest-grid{grid-template-columns:1fr}}@media(max-width:760px){.evidence-list{grid-template-columns:1fr}.filter-card{align-items:stretch}.filter-card .btn{width:100%}}
</style>
