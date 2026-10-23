<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import BaseDialog from '../components/BaseDialog.vue'
import { uiContextKey } from '../ui/context.js'
import { appOptions } from '../ui/mock-data.js'
import { agentApi, createAgentSession, shanghaiBusinessDate } from '../agent.js'

const { previewAppId: appId } = inject(uiContextKey, { previewAppId: ref('10350') })
const businessDate = ref(shanghaiBusinessDate()), horizonDays = ref(1), runId = ref(''), fileKey = ref(''), backtestId = ref('')
const draft = ref(''), composer = ref(null), messageList = ref(null)
const session = createAgentSession()
const status = ref(null), statusError = ref(''), config = ref(null), configError = ref(''), suggestion = ref(null), suggestionError = ref(''), switchOpen = ref(false), busy = reactive({ suggestion: false, switch: false })
const activeContext = computed(() => ({ appId: appId.value, businessDate: businessDate.value, horizonDays: Number(horizonDays.value), ...(runId.value.trim() ? { runId: runId.value.trim() } : {}), ...(fileKey.value.trim() ? { fileKey: fileKey.value.trim() } : {}), ...(backtestId.value.trim() ? { backtestId: backtestId.value.trim() } : {}) }))
const canSwitch = computed(() => Boolean(suggestion.value?.canSwitch && suggestion.value?.suggestionId && config.value && !busy.switch))
const availableModelLabels = computed(() => (config.value?.availableModels || []).map(model => typeof model === 'string' ? model : model?.modelVersion || model?.name || '').filter(Boolean))
let configGeneration = 0
watch(activeContext, value => { session.setContext(value); suggestion.value = null; suggestionError.value = ''; switchOpen.value = false; draft.value = '' }, { immediate: true })
watch(appId, () => { void loadConfig() })
function choose(text) { draft.value = text; nextTick(() => composer.value?.focus()) }
async function scrollToEnd() { await nextTick(); if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight }
async function send() { const message = draft.value.trim(); if (!message) return; draft.value = ''; await session.ask(message); await scrollToEnd() }
async function loadStatus() { try { status.value = await agentApi.status(); statusError.value = '' } catch (error) { statusError.value = error.message || 'Agent 状态查询失败。' } }
async function loadConfig() {
  const current = ++configGeneration
  configError.value = ''; config.value = null; suggestion.value = null
  try { const result = await agentApi.config(appId.value); if (current === configGeneration) config.value = result }
  catch (error) { if (current === configGeneration) configError.value = error.message || 'App 配置查询失败。' }
}
async function requestSuggestion() {
  if (!config.value) return
  busy.suggestion = true; suggestionError.value = ''; suggestion.value = null
  const current = configGeneration
  try {
    const result = await agentApi.suggestion(appId.value, { horizonDays: Number(horizonDays.value), ...(backtestId.value.trim() ? { backtestId: backtestId.value.trim() } : {}) })
    if (current === configGeneration) suggestion.value = result
  } catch (error) { if (current === configGeneration) suggestionError.value = error.message || '模型建议查询失败。' }
  finally { busy.suggestion = false }
}
async function switchConfirmed() {
  if (!canSwitch.value) return
  const current = configGeneration
  switchOpen.value = false; busy.switch = true; suggestionError.value = ''
  try {
    await agentApi.switchModel(appId.value, { expectedVersion: config.value.version, suggestionId: suggestion.value.suggestionId, confirmed: true })
    if (current === configGeneration) { suggestion.value = null; await loadConfig() }
  } catch (error) { if (current === configGeneration) suggestionError.value = error.message || '模型切换失败。' }
  finally { busy.switch = false }
}
onMounted(() => { void loadStatus(); void loadConfig() })
onBeforeUnmount(() => session.dispose())
</script>

<template>
  <div data-page="agent"><header class="page-heading"><div><h1>CDN 智能运营助手</h1><p>使用服务端模型和业务工具查询事实；回答附带服务端证据与不确定项。</p></div><span class="source-badge">实时接口 · 按上下文隔离</span></header>
    <div v-if="statusError || configError" class="notice notice--amber" role="alert">{{ statusError || configError }}</div>
    <div class="agent-layout"><aside class="card agent-side"><h2>查询上下文</h2><label>App<select v-model="appId"><option v-for="app in appOptions" :key="app.id" :value="app.id">{{ app.label }}</option></select></label><label>业务日（上海）<input v-model="businessDate" type="date"></label><label>跨度<select v-model.number="horizonDays"><option :value="1">1 天</option><option :value="7">7 天</option></select></label><label>历史运行 runId（可选）<input v-model.trim="runId" placeholder="留空按业务日查询"></label><label>文件标识（可选）<input v-model.trim="fileKey" placeholder="精确 fileKey"></label><label>回测编号（可选）<input v-model.trim="backtestId" placeholder="backtestId"></label><p class="side-note">未指定运行时只查询所选业务日与跨度；历史运行按其原日期显示。切换上下文保留本页原对话，迟到回答不会插入新对话。</p><h3>示例问题</h3><button class="question" type="button" @click="choose('当前 AppID 最新成功预测的 Top5 待压缩文件有哪些？')">当前 AppID 最新成功预测的 Top5 待压缩文件有哪些？</button><button class="question" type="button" @click="choose('所选业务日的 Top10 热点是什么？')">所选业务日的 Top10 热点是什么？</button><button class="question" type="button" @click="choose('有哪些文件逾期未处理？')">有哪些文件逾期未处理？</button><button class="question" type="button" @click="choose('当前模型有哪些可验证的改进建议？')">当前模型有哪些可验证的改进建议？</button></aside>
      <section class="card chat-panel" aria-label="Agent 对话"><header class="chat-heading"><span class="agent-avatar"><AppIcon name="agent" :size="23"/></span><div><h2>运营问答</h2><p>App {{ appId }} · {{ businessDate }} · {{ horizonDays }} 天<span v-if="runId"> · 运行 {{ runId }}</span></p></div><span class="connection-status">{{ status ? (status.configured ? `模型 ${status.model || '已配置'}` : '模型未配置') : '状态待查询' }}</span></header>
        <div ref="messageList" class="chat-messages" role="log" aria-live="polite"><p v-if="!session.state.messages.length" class="empty-chat">选择查询上下文后输入问题。无模型配置或无业务证据时，服务端会返回明确原因。</p><article v-for="(message,index) in session.state.messages" :key="index" class="chat-message" :class="`chat-message--${message.role}`"><div class="message-author">{{ message.role === 'user' ? '你' : '运营助手' }}</div><p class="message-bubble">{{ message.text }}</p><template v-if="message.role === 'assistant'"><div class="answer-context">{{ message.context?.businessDate || businessDate }} · {{ message.context?.horizonDays || horizonDays }} 天 · {{ message.context?.modelVersion || '模型版本未提供' }}</div><details v-for="(item,evidenceIndex) in message.evidence" :key="evidenceIndex" class="evidence"><summary>证据 {{ evidenceIndex + 1 }} · {{ item.tool || '业务查询' }} · {{ item.queriedAt || '查询时间未提供' }}</summary><div v-if="item.context" class="evidence-meta">{{ item.context.businessDate || item.context.predictionStartDate || '日期未提供' }} · {{ item.context.horizonDays || '跨度未提供' }} 天 · {{ item.context.runId || '运行未提供' }}</div><pre>{{ JSON.stringify(item.data ?? item, null, 2) }}</pre></details><p v-for="(uncertainty,i) in message.uncertainties" :key="i" class="uncertainty">待确认：{{ uncertainty }}</p></template></article><div v-if="session.state.busy" role="status" class="busy-chat">正在查询模型和业务工具…</div><div v-if="session.state.error" role="alert" class="notice notice--amber">{{ session.state.error }}</div></div>
        <form class="chat-compose" @submit.prevent="send"><label class="sr-only" for="agent-message">输入运营问题</label><textarea id="agent-message" ref="composer" v-model="draft" rows="2" maxlength="2000" placeholder="输入问题，Enter 发送，Shift + Enter 换行" :disabled="session.state.busy" @keydown.enter.exact.prevent="send"/><div class="compose-row"><span>问题与回答仅保留在本页内存</span><button class="btn btn-primary" type="submit" :disabled="session.state.busy || !draft.trim()"><AppIcon name="send" :size="15"/>发送</button></div></form></section></div>
    <section class="card model-panel" aria-labelledby="model-title"><header><div><h2 id="model-title">模型配置与建议</h2><p>建议来自服务端证据。切换模型需要独立确认与配置版本校验。</p></div><button class="btn" type="button" :disabled="busy.suggestion || !config" @click="requestSuggestion">生成当前跨度建议</button></header><div v-if="config" class="model-facts"><span>日模型：{{ config.dailyModelVersion || '未配置' }}</span><span>周模型：{{ config.weeklyModelVersion || '未配置' }}</span><span>可用模型：{{ availableModelLabels.join('、') || '未提供' }}</span><span>配置版本：{{ config.version }}</span></div><div v-if="suggestionError" class="notice notice--amber" role="alert">{{ suggestionError }}</div><div v-if="suggestion" class="suggestion"><p>建议编号：{{ suggestion.suggestionId || '未生成' }} · 目标模型：{{ suggestion.targetModelVersion || '无可切换模型' }}</p><p v-if="suggestion.reasonCodes?.length">限制原因：{{ suggestion.reasonCodes.join('、') }}</p><details v-if="suggestion.evidence"><summary>查看建议证据</summary><pre>{{ JSON.stringify(suggestion.evidence, null, 2) }}</pre></details><button class="btn btn-primary" type="button" :disabled="!canSwitch" @click="switchOpen=true">确认模型切换</button></div></section>
    <BaseDialog :open="switchOpen" title="确认切换模型" width="520px" @close="switchOpen=false"><div class="dialog-body"><p>App {{ appId }} · {{ horizonDays }} 天 · 当前配置版本 {{ config?.version }}</p><p>目标模型：<strong>{{ suggestion?.targetModelVersion }}</strong></p><p>建议编号：{{ suggestion?.suggestionId }}</p><div class="detail-button-row"><button class="btn" type="button" @click="switchOpen=false">取消</button><button class="btn btn-primary" type="button" :disabled="!canSwitch" @click="switchConfirmed">确认切换</button></div></div></BaseDialog>
  </div>
</template>

<style scoped>
.agent-layout{display:grid;grid-template-columns:270px minmax(0,1fr);gap:18px;margin-bottom:18px}.agent-side{padding:20px;display:flex;flex-direction:column;gap:13px}.agent-side h2,.agent-side h3{font-size:13px;color:#385679;margin:0}.agent-side h3{margin-top:12px}.agent-side label{display:grid;gap:6px;color:#6c819d;font-size:11px}.agent-side input,.agent-side select{width:100%;min-height:34px;font-size:12px}.side-note{font-size:10px;color:#8799af;line-height:1.7;margin:0}.question{background:#f7faff;border:1px solid #e3eefb;border-radius:5px;padding:10px;text-align:left;color:#597da8;font-size:11px}.question:hover{border-color:#9bc7f6}.chat-panel{display:flex;flex-direction:column;min-height:650px;height:calc(100vh - 205px);max-height:850px;overflow:hidden}.chat-heading{display:flex;align-items:center;gap:12px;padding:17px 20px;border-bottom:1px solid #e8eef6}.agent-avatar{width:42px;height:42px;border-radius:10px;display:grid;place-items:center;background:#eaf3ff;color:#2079df}.chat-heading h2{font-size:14px;color:#385477;margin:0 0 5px}.chat-heading p,.connection-status{font-size:10px;color:#8497b0;margin:0}.connection-status{margin-left:auto}.chat-messages{flex:1;min-height:0;overflow:auto;background:#fbfdff;padding:20px}.empty-chat{color:#879ab2;font-size:12px;line-height:1.8}.chat-message{margin:0 0 20px;max-width:94%}.chat-message--user{margin-left:auto;text-align:right}.message-author{font-size:10px;color:#8a9cb1;margin-bottom:7px}.message-bubble{display:inline-block;max-width:100%;text-align:left;margin:0;background:#fff;border:1px solid #e6edf6;border-radius:8px;padding:12px 15px;color:#48617f;font-size:12px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.chat-message--user .message-bubble{background:#eaf4ff}.answer-context{color:#8b9eb3;font-size:10px;margin:8px 0}.evidence{border:1px solid #e5ecf6;border-radius:5px;background:white;margin:7px 0;padding:9px 11px;text-align:left;font-size:10px;color:#5e7a9c}.evidence summary,.suggestion summary{cursor:pointer}.evidence-meta{padding:9px 0;color:#7d90a8}.evidence pre,.suggestion pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:270px;overflow:auto;font-size:10px;line-height:1.5}.uncertainty{font-size:10px;color:#9a7441;margin:7px 0}.busy-chat{font-size:11px;color:#6f92bd}.chat-compose{padding:15px 20px;border-top:1px solid #e6edf6}.chat-compose textarea{width:100%;font-size:12px;resize:vertical}.compose-row{display:flex;justify-content:space-between;align-items:center;margin-top:8px;color:#93a3b6;font-size:10px}.model-panel{padding:19px 21px;margin-bottom:20px}.model-panel header{display:flex;justify-content:space-between;align-items:center;gap:10px}.model-panel h2{font-size:14px;color:#385477;margin:0}.model-panel p{font-size:11px;color:#798da7;line-height:1.7}.model-facts{display:flex;gap:12px;flex-wrap:wrap;margin-top:14px;font-size:11px;color:#5c7594}.model-facts span{background:#f4f8fd;border-radius:4px;padding:8px}.suggestion{border-top:1px solid #e8eef6;margin-top:16px;padding-top:9px}.suggestion details{font-size:11px;color:#547396;margin-bottom:12px}@media(max-width:900px){.agent-layout{grid-template-columns:1fr}.agent-side{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.agent-side h2,.agent-side h3,.side-note{grid-column:1/-1}.chat-panel{height:650px}}@media(max-width:600px){.agent-side{grid-template-columns:1fr}.model-panel header{align-items:start;flex-direction:column}.chat-panel{height:600px}}
</style>
