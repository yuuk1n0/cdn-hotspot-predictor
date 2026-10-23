<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { alertApi } from '../../alerts.js'
import { notificationApi } from '../../notifications.js'
import { uiContextKey } from '../../ui/context.js'

const { previewAppId } = inject(uiContextKey, { previewAppId: ref('10350') })
const routes = ref([])
const appId = ref('')
const status = ref('')
const severity = ref('')
const page = ref(1)
const pageSize = 20
const incidents = ref(null)
const selected = ref(null)
const deliveries = ref([])
const busy = ref(false)
const detailBusy = ref(false)
const error = ref('')
const detailError = ref('')
const copied = ref('')
let generation = 0

const currentRoute = computed(() => routes.value.find(item => item.appId === appId.value))
const visibleOpen = computed(() => (incidents.value?.items || [])
  .filter(item => ['OPEN', 'ACKED', 'PROCESSING'].includes(item.status)).length)
const visibleP1 = computed(() => (incidents.value?.items || [])
  .filter(item => item.severity === 'P1' && item.status !== 'RECOVERED').length)
const totalPages = computed(() => Math.max(1, Math.ceil((incidents.value?.total || 0) / pageSize)))

watch(appId, value => {
  selected.value = null
  deliveries.value = []
  page.value = 1
  if (value) void loadIncidents()
})
watch([status, severity], () => {
  page.value = 1
  if (appId.value) void loadIncidents()
})

async function loadRoutes() {
  error.value = ''
  try {
    const result = await alertApi.listRoutes()
    if (!Array.isArray(result)) throw new Error('告警路由列表响应无效。')
    routes.value = result
    const preferred = result.some(item => item.appId === previewAppId.value)
      ? previewAppId.value
      : result[0]?.appId || ''
    if (!appId.value || !result.some(item => item.appId === appId.value)) appId.value = preferred
  } catch (cause) {
    error.value = cause.message || '告警路由加载失败。'
  }
}

async function loadIncidents() {
  const current = ++generation
  busy.value = true
  error.value = ''
  incidents.value = null
  try {
    const result = await alertApi.listIncidents({
      appId: appId.value,
      status: status.value,
      severity: severity.value,
      page: page.value,
      pageSize
    })
    if (current !== generation) return
    if (!Array.isArray(result?.items)) throw new Error('事故列表响应无效。')
    incidents.value = result
  } catch (cause) {
    if (current === generation) error.value = cause.message || '事故列表加载失败。'
  } finally {
    if (current === generation) busy.value = false
  }
}

async function selectIncident(incident) {
  const current = ++generation
  detailBusy.value = true
  detailError.value = ''
  selected.value = null
  deliveries.value = []
  try {
    const [detail, records] = await Promise.all([
      alertApi.getIncident(incident.incidentId),
      notificationApi.list({ appId: appId.value, page: 1, pageSize: 100 })
    ])
    if (current !== generation) return
    if (detail?.incidentId !== incident.incidentId) throw new Error('事故详情与当前选择不匹配。')
    selected.value = detail
    deliveries.value = (records?.items || []).filter(item => item.incidentId === incident.incidentId)
  } catch (cause) {
    if (current === generation) detailError.value = cause.message || '事故详情加载失败。'
  } finally {
    if (current === generation) detailBusy.value = false
  }
}

function changePage(next) {
  if (next < 1 || next > totalPages.value || next === page.value) return
  page.value = next
  void loadIncidents()
}

async function copyChatId() {
  const chatId = selected.value?.chatId
  if (!chatId || !navigator.clipboard) return
  await navigator.clipboard.writeText(chatId)
  copied.value = chatId
  window.setTimeout(() => { if (copied.value === chatId) copied.value = '' }, 1800)
}

function severityClass(value) {
  return `severity--${String(value || 'P3').toLowerCase()}`
}

function statusClass(value) {
  return `status--${String(value || 'open').toLowerCase()}`
}

function timeText(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(loadRoutes)
</script>

<template>
  <section class="incident-workspace" aria-labelledby="incident-title">
    <header class="incident-toolbar">
      <div>
        <p class="eyebrow">ON-CALL DISPATCH</p>
        <h2 id="incident-title">正在发生什么</h2>
        <p>按 AppID 查看活跃事故、通知群和投递结果。认领与恢复在飞书卡片内完成。</p>
      </div>
      <div class="incident-summary" aria-label="当前页告警摘要">
        <span><strong>{{ visibleOpen }}</strong> 本页处理中</span>
        <span class="incident-summary__danger"><strong>{{ visibleP1 }}</strong> P1 未恢复</span>
        <span><strong>{{ incidents?.total || 0 }}</strong> 总记录</span>
      </div>
    </header>

    <div v-if="error" class="notice notice--amber" role="alert">{{ error }}</div>
    <div v-if="!routes.length && !error" class="empty-panel incident-empty">
      <h3>还没有告警路由</h3>
      <p>先在“业务通知配置”新增 AppID、负责人和应急群，告警才有明确去向。</p>
    </div>

    <template v-else>
      <div class="incident-filters">
        <label>AppID
          <select v-model="appId">
            <option v-for="route in routes" :key="route.appId" :value="route.appId">
              {{ route.appId }} · {{ route.businessName || '未命名业务' }}
            </option>
          </select>
        </label>
        <label>状态
          <select v-model="status">
            <option value="">全部</option>
            <option value="OPEN">OPEN</option>
            <option value="ACKED">ACKED</option>
            <option value="PROCESSING">PROCESSING</option>
            <option value="RECOVERED">RECOVERED</option>
          </select>
        </label>
        <label>等级
          <select v-model="severity">
            <option value="">全部</option>
            <option value="P1">P1</option>
            <option value="P2">P2</option>
            <option value="P3">P3</option>
          </select>
        </label>
        <button class="btn btn-sm" type="button" :disabled="busy" @click="loadIncidents">刷新</button>
        <span class="filter-route">{{ currentRoute?.businessName || '未配置业务名称' }}</span>
      </div>

      <div class="incident-grid">
        <div class="incident-rail" aria-live="polite">
          <p v-if="busy" class="incident-loading">正在读取事故记录…</p>
          <p v-else-if="!incidents?.items?.length" class="incident-loading">当前筛选没有事故。</p>
          <button
            v-for="incident in incidents?.items || []"
            :key="incident.incidentId"
            class="incident-ticket"
            :class="{ active: selected?.incidentId === incident.incidentId }"
            type="button"
            @click="selectIncident(incident)"
          >
            <span class="incident-ticket__rail" :class="severityClass(incident.severity)"></span>
            <span class="incident-ticket__top">
              <span class="severity" :class="severityClass(incident.severity)">{{ incident.severity }}</span>
              <span class="status-pill" :class="statusClass(incident.status)">{{ incident.status }}</span>
              <time>{{ timeText(incident.lastSeenAt) }}</time>
            </span>
            <strong>{{ incident.details?.title || incident.incidentType }}</strong>
            <span class="incident-ticket__meta">
              {{ incident.incidentId }} · {{ incident.objectKey }}
            </span>
            <span class="incident-ticket__meta">
              发生 {{ incident.occurrenceCount }} 次 · 提醒 {{ incident.reminderCount }} 次 ·
              {{ incident.routingStatus }}
            </span>
          </button>

          <div v-if="incidents?.total > pageSize" class="incident-pages">
            <button class="btn btn-sm" type="button" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
            <span>{{ page }} / {{ totalPages }}</span>
            <button class="btn btn-sm" type="button" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
          </div>
        </div>

        <aside class="incident-detail" aria-live="polite">
          <p v-if="detailBusy" class="incident-loading">正在读取事故证据…</p>
          <div v-else-if="detailError" class="notice notice--amber" role="alert">{{ detailError }}</div>
          <div v-else-if="selected">
            <div class="detail-heading">
              <div>
                <span class="severity" :class="severityClass(selected.severity)">{{ selected.severity }}</span>
                <p class="eyebrow">{{ selected.appId }} · {{ selected.incidentType }}</p>
                <h3>{{ selected.details?.title || '线上事故' }}</h3>
              </div>
              <span class="status-pill" :class="statusClass(selected.status)">{{ selected.status }}</span>
            </div>
            <p class="detail-summary">{{ selected.details?.summary || '暂无影响摘要' }}</p>
            <dl class="incident-facts">
              <div><dt>当前值</dt><dd>{{ selected.details?.observedValue || '—' }}</dd></div>
              <div><dt>阈值</dt><dd>{{ selected.details?.threshold || '—' }}</dd></div>
              <div><dt>首次发生</dt><dd>{{ timeText(selected.firstSeenAt) }}</dd></div>
              <div><dt>最后更新</dt><dd>{{ timeText(selected.lastSeenAt) }}</dd></div>
              <div><dt>异常对象</dt><dd class="mono">{{ selected.objectKey }}</dd></div>
              <div><dt>路由状态</dt><dd>{{ selected.routingStatus }}{{ selected.routingError ? ` · ${selected.routingError}` : '' }}</dd></div>
            </dl>
            <div class="chat-route">
              <div><span>飞书群</span><code>{{ selected.chatId || '尚未绑定' }}</code></div>
              <button v-if="selected.chatId" class="btn btn-sm" type="button" @click="copyChatId">
                {{ copied === selected.chatId ? '已复制' : '复制群 ID' }}
              </button>
            </div>
            <p class="card-guidance">认领、处理中和恢复按钮只存在于飞书卡片；网页不接收自报身份。</p>

            <div class="delivery-trace">
              <h4>投递轨迹</h4>
              <p v-if="!deliveries.length" class="muted-copy">尚无该事故的投递记录。</p>
              <ol v-else>
                <li v-for="item in deliveries" :key="item.notificationId">
                  <span>{{ item.alertPhase || 'DELIVERY' }}</span>
                  <strong>{{ item.status }}</strong>
                  <small>{{ item.chatId || item.destinationId }} · {{ item.feishuMessageId || item.lastError || '无消息 ID' }}</small>
                </li>
              </ol>
            </div>
          </div>
          <div v-else class="detail-placeholder">
            <p class="eyebrow">SELECT A TICKET</p>
            <h3>选择左侧事故</h3>
            <p>查看当前指标、影响摘要、通知群和每一步投递结果。</p>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.incident-workspace{--dispatch-ink:#17324f;--dispatch-red:#c23b42;--dispatch-amber:#c8841c;--dispatch-green:#197c68;--dispatch-paper:#f7f9fc;--dispatch-line:#dce5ef;display:flex;flex-direction:column;gap:16px}
.incident-toolbar{display:flex;justify-content:space-between;gap:24px;padding:22px 24px;background:linear-gradient(115deg,#132d4e,#244d73);color:#edf6ff;border-radius:8px;border:1px solid #294f73}
.incident-toolbar h2{font-size:24px;margin:2px 0 6px;letter-spacing:-.5px}.incident-toolbar p{margin:0;color:#bed0e2;font-size:12px;line-height:1.7}.incident-toolbar .eyebrow{color:#76c9ef;font-family:'Cascadia Code',Consolas,monospace;letter-spacing:.14em;font-size:10px}
.incident-summary{display:flex;align-items:stretch;gap:8px;flex-wrap:wrap}.incident-summary span{min-width:106px;padding:12px 14px;border:1px solid rgba(255,255,255,.16);background:rgba(255,255,255,.07);border-radius:7px;color:#c9d8e7;font-size:10px}.incident-summary strong{display:block;color:white;font-size:22px;margin-bottom:3px}.incident-summary__danger strong{color:#ffb5b9}
.incident-filters{display:flex;align-items:end;gap:10px;flex-wrap:wrap;padding:13px 15px;background:white;border:1px solid var(--dispatch-line);border-radius:7px}.incident-filters label{display:grid;gap:5px;font-size:10px;color:#73849a}.incident-filters select{height:34px;min-width:142px;font-size:11px}.filter-route{margin-left:auto;font-size:11px;color:#56708f;background:#f3f7fc;padding:7px 10px;border-radius:4px}
.incident-grid{display:grid;grid-template-columns:minmax(320px,.92fr) minmax(0,1.35fr);gap:16px;min-height:470px}.incident-rail{display:flex;flex-direction:column;gap:9px;padding-left:11px;border-left:2px solid #dbe6f2}.incident-ticket{position:relative;display:grid;gap:7px;text-align:left;padding:14px 14px 13px;background:white;border:1px solid var(--dispatch-line);border-radius:6px;color:var(--dispatch-ink);overflow:hidden}.incident-ticket:hover{border-color:#9ebbd9;background:#fbfdff}.incident-ticket.active{border-color:#78aee5;box-shadow:0 0 0 2px #a9cff233}.incident-ticket__rail{position:absolute;left:0;top:0;bottom:0;width:4px;background:#9aa9bb}.incident-ticket__rail.severity--p1{background:var(--dispatch-red)}.incident-ticket__rail.severity--p2{background:var(--dispatch-amber)}.incident-ticket__rail.severity--p3{background:#5b87b1}.incident-ticket__top{display:flex;align-items:center;gap:7px}.incident-ticket time{margin-left:auto;color:#94a1b1;font-size:9px}.incident-ticket strong{font-size:13px;line-height:1.5}.incident-ticket__meta{font-size:10px;color:#8291a4;line-height:1.55;overflow-wrap:anywhere}
.severity{display:inline-flex;padding:3px 6px;border-radius:3px;font-family:'Cascadia Code',Consolas,monospace;font-size:9px;font-weight:700;background:#edf2f7;color:#60758f}.severity--p1{background:#fff0f0;color:var(--dispatch-red)}.severity--p2{background:#fff8e7;color:var(--dispatch-amber)}.severity--p3{background:#edf5fb;color:#497ba6}
.status--open.status-pill{color:var(--dispatch-red);border-color:#f1c9cb;background:#fff2f2}.status--acked.status-pill,.status--processing.status-pill{color:#b57412;border-color:#f2dfb7;background:#fff9eb}.status--recovered.status-pill{color:var(--dispatch-green);border-color:#c8e8df;background:#eefaf6}
.incident-detail{background:white;border:1px solid var(--dispatch-line);border-radius:7px;padding:22px}.detail-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.detail-heading h3{font-size:20px;margin:5px 0 0;color:var(--dispatch-ink)}.eyebrow{font-family:'Cascadia Code',Consolas,monospace;font-size:9px;letter-spacing:.12em;color:#8192a6;margin:0}.detail-summary{font-size:12px;line-height:1.8;color:#506781;margin:16px 0;padding:12px;background:var(--dispatch-paper);border-radius:5px}
.incident-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1px;background:var(--dispatch-line);border:1px solid var(--dispatch-line);border-radius:5px;overflow:hidden;margin:0}.incident-facts div{background:white;padding:11px 12px}.incident-facts dt{font-size:9px;color:#8a99aa;margin-bottom:5px}.incident-facts dd{font-size:11px;color:#2e4968;margin:0;overflow-wrap:anywhere}.chat-route{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-top:14px;padding:12px;border:1px dashed #cbd9e8;border-radius:5px}.chat-route span{display:block;font-size:9px;color:#8b9bad;margin-bottom:5px}.chat-route code{color:#355273}.card-guidance{font-size:10px;color:#8493a5;margin:11px 0 0;line-height:1.7}
.delivery-trace{border-top:1px solid var(--dispatch-line);margin-top:18px;padding-top:16px}.delivery-trace h4{font-size:12px;color:#304c6c;margin:0 0 10px}.delivery-trace ol{list-style:none;margin:0;padding:0;display:grid;gap:8px}.delivery-trace li{display:grid;grid-template-columns:110px 70px minmax(0,1fr);gap:10px;align-items:center;font-size:10px}.delivery-trace li>span{font-family:'Cascadia Code',Consolas,monospace;color:#5c7897}.delivery-trace li>strong{color:#2b536f}.delivery-trace small{color:#8b9aac;overflow-wrap:anywhere}.incident-loading,.muted-copy{font-size:11px;color:#8b9aac;line-height:1.7}.detail-placeholder{min-height:360px;display:grid;place-content:center;text-align:center;color:#8b9aac}.detail-placeholder h3{font-size:18px;color:#3d5876;margin:8px 0}.detail-placeholder p{font-size:11px;max-width:300px;line-height:1.8}.incident-pages{display:flex;justify-content:center;align-items:center;gap:8px;padding:8px 0;font-size:10px;color:#7f90a5}
@media(max-width:900px){.incident-grid{grid-template-columns:1fr}.incident-detail{min-height:340px}.incident-toolbar{flex-direction:column}.filter-route{margin-left:0}}@media(max-width:580px){.incident-toolbar{padding:18px}.incident-filters label{width:100%}.incident-filters select{width:100%}.incident-facts{grid-template-columns:1fr}.delivery-trace li{grid-template-columns:1fr}.detail-heading{flex-direction:column}}
</style>
