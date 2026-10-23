<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import BaseDialog from '../BaseDialog.vue'
import { uiContextKey } from '../../ui/context.js'
import { appOptions } from '../../ui/mock-data.js'
import { notificationApi, createNotificationSession } from '../../notifications.js'
import { shanghaiBusinessDate } from '../../agent.js'

const { previewAppId: appId } = inject(uiContextKey, { previewAppId: ref('10350') })
const session = createNotificationSession()
const type = ref('DAILY'), purpose = ref('DEMO'), evidenceSource = ref('TEST')
const runId = ref(''), businessDate = ref('')
const destinations = ref([]), runs = ref([]), config = ref(null), records = ref(null), overdue = ref(null), monitor = ref(null)
const destinationId = ref(''), busy = reactive({ setup: false, runs: false, records: false, config: false, overdue: false })
const error = ref(''), recordsError = ref(''), configError = ref(''), monitorError = ref(''), confirmOpen = ref(false), notice = ref('')
const settings = reactive({ notificationsEnabled: false, destinationId: '', handlingHours: 24, modelReviewEnabled: false })
let generation = 0, runGeneration = 0, recordGeneration = 0
const chosenDestination = computed(() => destinations.value.find(item => item.id === destinationId.value))
const sendAllowed = computed(() => session.state.preview?.canSend && chosenDestination.value?.configured && (purpose.value === 'DEMO' ? chosenDestination.value.demoOnly : !chosenDestination.value.demoOnly))
const validSettings = computed(() => Number.isInteger(settings.handlingHours) && settings.handlingHours >= 1 && settings.handlingHours <= 168)
const reasonText = computed(() => session.state.preview?.reasonCodes?.join('、') || '')

watch([appId, runId, type, purpose, evidenceSource], () => { session.invalidate(); confirmOpen.value = false; notice.value = ''; overdue.value = null })
watch(destinationId, value => { session.setDestination(value); confirmOpen.value = false })
watch(appId, () => { runId.value = ''; runGeneration++; runs.value = []; void loadSetup(); void loadRecords(); void loadMonitor() })
watch(type, value => { if (value === 'OVERDUE') evidenceSource.value = 'REAL' })
watch([runId, type], () => { void loadRecords() })
watch(businessDate, () => { void loadRuns() })

async function loadRuns() {
  const current = ++runGeneration
  busy.runs = true; error.value = ''; runs.value = []
  try {
    const result = await notificationApi.runs(appId.value, 1, businessDate.value)
    if (current !== runGeneration) return
    if (!Array.isArray(result?.items)) throw new Error('运行列表响应无效。')
    runs.value = result.items
  } catch (cause) { if (current === runGeneration) error.value = cause.message || '运行列表查询失败。' }
  finally { if (current === runGeneration) busy.runs = false }
}

async function loadSetup() {
  const current = ++generation
  busy.setup = true; error.value = ''
  try {
    const [nextDestinations, nextConfig] = await Promise.all([
      notificationApi.destinations(), notificationApi.config(appId.value)
    ])
    if (current !== generation) return
    if (!Array.isArray(nextDestinations)) throw new Error('目的地列表响应无效。')
    destinations.value = nextDestinations
    config.value = nextConfig
    Object.assign(settings, { notificationsEnabled: Boolean(nextConfig.notificationsEnabled), destinationId: nextConfig.destinationId || '', handlingHours: nextConfig.handlingHours ?? 24, modelReviewEnabled: Boolean(nextConfig.modelReviewEnabled) })
    destinationId.value = nextConfig.destinationId || nextDestinations.find(item => item.configured && item.demoOnly)?.id || ''
    void loadRuns()
  } catch (cause) { if (current === generation) error.value = cause.message || '配置加载失败。' }
  finally { if (current === generation) busy.setup = false }
}
async function loadRecords(page = 1) {
  const current = ++recordGeneration
  busy.records = true; recordsError.value = ''; records.value = null
  try {
    const result = await notificationApi.list({ appId: appId.value, runId: runId.value, type: type.value, page, pageSize: 20 })
    if (current !== recordGeneration) return
    if (!Array.isArray(result?.items)) throw new Error('通知记录响应无效。')
    records.value = result
  } catch (cause) { if (current === recordGeneration) recordsError.value = cause.message || '通知记录查询失败。' }
  finally { if (current === recordGeneration) busy.records = false }
}
async function loadMonitor() {
  monitorError.value = ''
  try {
    const result = await notificationApi.monitor(appId.value)
    if (result?.appId !== appId.value || !result?.statusCounts) throw new Error('飞书投递监控响应与当前 App 不匹配。')
    monitor.value = result
  } catch (cause) { monitorError.value = cause.message || '飞书投递监控查询失败。' }
}
async function preview() {
  notice.value = ''
  if (!runId.value.trim()) { error.value = '请选择或输入运行编号。'; return }
  error.value = ''
  await session.preview({ appId: appId.value, runId: runId.value.trim(), type: type.value, purpose: purpose.value, evidenceSource: evidenceSource.value })
  if (session.state.preview?.type === 'OVERDUE') {
    const requested = JSON.stringify([appId.value, runId.value.trim(), type.value, evidenceSource.value])
    busy.overdue = true
    try {
      const result = await notificationApi.overdue({ appId: appId.value, runId: runId.value.trim(), evidenceSource: evidenceSource.value })
      if (requested === JSON.stringify([appId.value, runId.value.trim(), type.value, evidenceSource.value])) overdue.value = result
    }
    catch (cause) { if (requested === JSON.stringify([appId.value, runId.value.trim(), type.value, evidenceSource.value])) error.value = cause.message || '逾期详情查询失败。' }
    finally { busy.overdue = false }
  }
}
async function sendConfirmed() {
  if (!sendAllowed.value) return
  confirmOpen.value = false
  try {
    const receipt = await session.send(destinationId.value)
    if (receipt) {
      notice.value = `已收到发送请求（${receipt.notificationId}，${receipt.status}）。请刷新记录查看最终状态。`
      await loadRecords()
      await loadMonitor()
    }
  } catch { /* The session retains the actionable error and idempotency key. */ }
}
async function saveConfig() {
  if (!config.value) return
  busy.config = true; configError.value = ''
  try {
    if (!validSettings.value) throw new Error('处理时限必须是 1 至 168 的整数小时。')
    const result = await notificationApi.updateConfig(appId.value, { expectedVersion: config.value.version, notificationsEnabled: settings.notificationsEnabled, destinationId: settings.destinationId || null, handlingHours: Number(settings.handlingHours), modelReviewEnabled: settings.modelReviewEnabled })
    config.value = result
    notice.value = '配置已保存；自动通知由服务端按配置调度。'
  } catch (cause) { configError.value = cause.message || '配置保存失败。' }
  finally { busy.config = false }
}
onMounted(() => { void loadSetup(); void loadRecords(); void loadMonitor() })
onBeforeUnmount(() => { generation++; runGeneration++; recordGeneration++; session.invalidate() })
</script>

<template>
  <div data-page="notification-delivery">
    <header class="page-heading"><div><h1>发送记录与例行通知</h1><p>查询例行清单、逾期提醒和事故卡片的投递证据。</p></div><span class="source-badge">业务接口 · 预览不发送</span></header>
    <div v-if="error || session.state.error || configError || recordsError || monitorError" class="notice notice--amber" role="alert">{{ error || session.state.error || configError || recordsError || monitorError }}</div>
    <div v-if="notice" class="notice" role="status">{{ notice }}</div>
    <section class="card nt-section" aria-labelledby="nt-monitor-title"><div class="nt-heading"><h2 id="nt-monitor-title">飞书投递监控</h2><span>只读 · {{ monitor?.monitorMode || 'DELIVERY_RECORDS_ONLY' }} · {{ monitor?.generatedAt || '未加载' }}</span></div>
      <div v-if="monitor" class="nt-monitor-grid"><div><small>记录总数</small><strong>{{ monitor.total }}</strong></div><div><small>SENT</small><strong>{{ monitor.statusCounts.SENT }}</strong></div><div><small>PENDING</small><strong>{{ monitor.statusCounts.PENDING }}</strong></div><div><small>失败 / 未确认</small><strong>{{ monitor.statusCounts.FAILED }} / {{ monitor.statusCounts.UNKNOWN }}</strong></div><div><small>已配置目的地</small><strong>{{ monitor.destinations.length }}</strong></div></div>
      <p class="nt-hint">目的地：{{ monitor?.destinations?.map(item => `${item.label}(${item.id})`).join('、') || '未配置' }}。告警规则：每日 09:00 清单、每小时逾期检查；投递失败按退避重试，不阻断业务任务。</p>
      <div v-if="monitor?.recentProblems?.length" class="table-scroll"><table><thead><tr><th>更新时间 / 类型</th><th>状态</th><th>运行 / 目标</th><th>错误</th></tr></thead><tbody><tr v-for="item in monitor.recentProblems" :key="item.notificationId"><td>{{ item.sentAt || item.createdAt || '—' }}<small>{{ item.type }} / {{ item.purpose }}</small></td><td><strong>{{ item.status }}</strong><small>{{ item.attemptCount }} 次尝试</small></td><td>{{ item.runId }}<small>{{ item.destinationId }}</small></td><td>{{ item.lastError || '—' }}</td></tr></tbody></table></div>
      <p v-else class="nt-hint">暂无 FAILED / UNKNOWN 投递记录。</p>
    </section>
    <section class="card nt-section" aria-labelledby="nt-preview-title"><div class="nt-heading"><h2 id="nt-preview-title">通知预览</h2><span>DEMO 仅供测试目标；BUSINESS 由服务端校验 REAL / MODEL 与业务日</span></div>
      <div class="nt-fields">
        <label>App<select v-model="appId"><option v-for="app in appOptions" :key="app.id" :value="app.id">{{ app.label }}</option></select></label>
        <label>通知类型<select v-model="type"><option value="DAILY">每日清单</option><option value="OVERDUE">逾期提醒</option></select></label>
        <label>用途<select v-model="purpose"><option value="DEMO">DEMO · 测试</option><option value="BUSINESS">BUSINESS · 正式</option></select></label>
        <label>处理证据<select v-model="evidenceSource"><option value="TEST">TEST · 测试登记</option><option value="REAL">REAL · 真实登记</option></select></label>
        <label>运行日期筛选（可选）<input v-model="businessDate" type="date" aria-describedby="nt-date-help"></label>
      </div>
      <p id="nt-date-help" class="nt-hint">筛选仅用于查找运行；预览正文日期以服务端快照为准。上海今日：{{ shanghaiBusinessDate() }}</p>
      <div class="nt-fields nt-run"><label>历史运行<select v-model="runId"><option value="">选择运行或下方输入</option><option v-for="run in runs" :key="run.runId" :value="run.runId">{{ run.runId }} · {{ run.predictionStartDate || run.businessDate || '日期未知' }} · {{ run.predictionSource || '来源未知' }}</option></select></label><label>运行编号<input v-model.trim="runId" placeholder="输入已成功运行的 runId"></label><button class="btn" type="button" :disabled="busy.setup" @click="loadSetup">刷新运行</button><button class="btn btn-primary" type="button" :disabled="session.state.busy || !runId" @click="preview">预览内容</button></div>
      <div v-if="session.state.preview" class="nt-preview"><div class="nt-meta"><strong>{{ session.state.preview.type }} · {{ session.state.preview.purpose }}</strong><span>业务日 {{ session.state.preview.businessDate }} · 预测 {{ session.state.preview.predictionStartDate }} 至 {{ session.state.preview.predictionEndDate }} · {{ session.state.preview.horizonDays }} 天</span><span>模型 {{ session.state.preview.modelVersion }} · 数据 {{ session.state.preview.datasetSource }} / 预测 {{ session.state.preview.predictionSource }} · {{ session.state.preview.itemCount }} 项</span></div><pre>{{ session.state.preview.text }}</pre><p v-if="reasonText" class="nt-hint">发送限制：{{ reasonText }}</p><p v-if="overdue">逾期查询时间 {{ overdue.asOf }} · 处理时限 {{ overdue.handlingHours }} 小时 · {{ overdue.items?.length || 0 }} 项</p></div>
      <div class="nt-send"><label>飞书目标<select v-model="destinationId"><option value="">选择已配置目的地</option><option v-for="item in destinations" :key="item.id" :value="item.id" :disabled="!item.configured">{{ item.label }} · {{ item.demoOnly ? '测试目标' : '业务目标' }} · {{ item.configured ? '已配置' : '未配置' }}</option></select></label><button class="btn btn-primary" type="button" :disabled="!sendAllowed || session.state.busy" @click="confirmOpen=true">确认目标与内容后发送</button><span v-if="session.state.sent">{{ session.state.sent.status }} · {{ session.state.sent.notificationId }}</span></div>
    </section>
    <section class="card nt-section" aria-labelledby="nt-record-title"><div class="nt-heading"><h2 id="nt-record-title">发送记录</h2><button class="btn btn-sm" type="button" :disabled="busy.records" @click="loadRecords()">刷新状态</button></div><p v-if="!records?.items?.length" class="nt-hint">{{ busy.records ? '正在查询记录…' : '当前筛选条件下暂无发送记录。' }}</p><div v-else class="table-scroll"><table><thead><tr><th>创建时间 / 业务日</th><th>运行 / 目标</th><th>类型 / 阶段</th><th>状态</th><th>尝试 / 后续</th></tr></thead><tbody><tr v-for="item in records.items" :key="item.notificationId"><td>{{ item.createdAt || '—' }}<small>{{ item.businessDate || '—' }}</small></td><td>{{ item.runId || item.incidentId || '—' }}<small>{{ item.chatId || item.destinationId }}</small></td><td>{{ item.type }} / {{ item.purpose }}<small>{{ item.alertPhase || item.evidenceSource }}</small><small v-if="item.feishuMessageId">{{ item.feishuMessageId }}</small></td><td><strong>{{ item.status }}</strong><small v-if="item.lastError">{{ item.lastError }}</small></td><td>{{ item.attemptCount }} 次<small>{{ item.sentAt ? `发送于 ${item.sentAt}` : item.nextAttemptAt ? `下次 ${item.nextAttemptAt}` : '—' }}</small></td></tr></tbody></table></div><div v-if="records?.total > 20" class="nt-pages"><button class="btn btn-sm" :disabled="records.page<=1" @click="loadRecords(records.page-1)">上一页</button><span>第 {{ records.page }} 页 / {{ records.total }} 条</span><button class="btn btn-sm" :disabled="records.page*records.pageSize>=records.total" @click="loadRecords(records.page+1)">下一页</button></div></section>
    <section class="card nt-section" aria-labelledby="nt-config-title"><div class="nt-heading"><h2 id="nt-config-title">例行清单配置</h2><span>版本 {{ config?.version ?? '—' }} · 与事故路由配置独立</span></div><div v-if="config" class="nt-fields"><label class="nt-check"><input v-model="settings.notificationsEnabled" type="checkbox">启用服务端自动通知</label><label>默认目标<select v-model="settings.destinationId"><option value="">未选择</option><option v-for="item in destinations" :key="item.id" :value="item.id" :disabled="!item.configured">{{ item.label }}</option></select></label><label>处理时限（小时）<input v-model.number="settings.handlingHours" type="number" min="1" max="168"></label><label class="nt-check"><input v-model="settings.modelReviewEnabled" type="checkbox">启用周期模型评估建议</label><button class="btn" type="button" :disabled="busy.config || !validSettings" @click="saveConfig">保存配置</button></div></section>
    <BaseDialog :open="confirmOpen" title="确认飞书发送" width="620px" @close="confirmOpen=false"><div class="dialog-body"><p>目标：<strong>{{ chosenDestination?.label }}（{{ destinationId }}）</strong></p><p>用途：{{ session.state.preview?.purpose }} · 运行：{{ session.state.preview?.runId }} · 业务日：{{ session.state.preview?.businessDate }}</p><pre class="nt-confirm-text">{{ session.state.preview?.text }}</pre><p class="nt-hint">提交后返回 PENDING 仅表示收到请求。最终状态请在发送记录中刷新。</p><div class="detail-button-row"><button class="btn" type="button" @click="confirmOpen=false">取消</button><button class="btn btn-primary" type="button" :disabled="!sendAllowed" @click="sendConfirmed">确认发送到此目标</button></div></div></BaseDialog>
  </div>
</template>

<style scoped>
.nt-section{padding:20px;margin-bottom:18px}.nt-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:17px}.nt-heading h2{font-size:15px;margin:0;color:#294668}.nt-heading span,.nt-hint{font-size:11px;color:#8293a9;line-height:1.7}.nt-fields{display:flex;flex-wrap:wrap;gap:12px 16px;align-items:end}.nt-fields label,.nt-send label{font-size:11px;color:#627995;display:grid;gap:6px;min-width:130px}.nt-fields input,.nt-fields select,.nt-send select{height:35px;font-size:12px}.nt-run{border-top:1px solid #edf1f7;padding-top:15px}.nt-run label{min-width:220px;flex:1}.nt-preview{border:1px solid #dce9f8;background:#f8fbff;border-radius:6px;margin-top:18px;padding:16px;color:#516983;font-size:12px}.nt-meta{display:flex;flex-wrap:wrap;gap:8px 18px;line-height:1.7}.nt-preview pre,.nt-confirm-text{white-space:pre-wrap;overflow-wrap:anywhere;background:#fff;border:1px solid #e6edf6;padding:14px;border-radius:5px;max-height:330px;overflow:auto;font-family:inherit;font-size:12px;line-height:1.7}.nt-send{display:flex;align-items:end;gap:12px;margin-top:17px;flex-wrap:wrap;font-size:11px;color:#68809d}.nt-send label{min-width:230px}.nt-section table{width:100%;border-collapse:collapse;font-size:11px;color:#536c8c}.nt-section th,.nt-section td{padding:11px;border-bottom:1px solid #edf1f6;text-align:left;vertical-align:top}.nt-section td small{display:block;color:#8b9cb0;margin-top:5px}.nt-pages{display:flex;justify-content:end;align-items:center;gap:10px;margin-top:14px;font-size:11px;color:#7b8ea7}.nt-check{display:flex!important;align-items:center;gap:8px!important}.nt-check input{height:auto}.nt-monitor-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px;margin-bottom:13px}.nt-monitor-grid>div{padding:12px;border:1px solid #e8eef6;border-radius:5px;background:#fafcff}.nt-monitor-grid small{display:block;color:#8b9cb0;margin-bottom:7px}.nt-monitor-grid strong{color:#2c4c73;font-size:18px}.nt-confirm-text{max-height:45vh}@media(max-width:760px){.nt-heading{align-items:start;flex-direction:column}.nt-fields label,.nt-send label{min-width:100%;width:100%}.nt-section{padding:14px}.nt-monitor-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
