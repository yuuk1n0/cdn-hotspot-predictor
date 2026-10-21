import { reactive } from 'vue'
import { requestJson } from './http.js'

function jsonOptions(body, signal) {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal }
}

export const agentApi = {
  status: (fetchService = fetch, signal) => requestJson('/api/agent/status', fetchService, { signal }),
  chat: (body, fetchService = fetch, signal) => requestJson('/api/agent/chat', fetchService, jsonOptions(body, signal)),
  config: (appId, fetchService = fetch, signal) => requestJson(`/api/app-configs/${encodeURIComponent(appId)}`, fetchService, { signal }),
  updateConfig: (appId, body, fetchService = fetch, signal) => requestJson(`/api/app-configs/${encodeURIComponent(appId)}`, fetchService, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal }),
  suggestion: (appId, body, fetchService = fetch, signal) => requestJson(`/api/app-configs/${encodeURIComponent(appId)}/model-suggestions`, fetchService, jsonOptions(body, signal)),
  switchModel: (appId, body, fetchService = fetch, signal) => requestJson(`/api/app-configs/${encodeURIComponent(appId)}/model-switches`, fetchService, jsonOptions(body, signal))
}

export function shanghaiBusinessDate(date = new Date()) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}

export function createAgentSession({ fetchService = fetch } = {}) {
  const state = reactive({ context: null, messages: [], busy: false, error: '' })
  const conversations = new Map()
  let generation = 0
  let controller
  let key = ''
  function contextKey(context) {
    return JSON.stringify([context.appId, context.businessDate, context.horizonDays, context.runId || '', context.fileKey || '', context.backtestId || ''])
  }
  function setContext(context) {
    const next = contextKey(context)
    if (next === key) return
    generation++
    controller?.abort()
    key = next
    state.context = { ...context }
    if (!conversations.has(key)) conversations.set(key, [])
    state.messages = conversations.get(key)
    state.error = ''
    state.busy = false
  }
  async function ask(message) {
    const text = message?.trim()
    if (!text || !state.context || state.busy) return
    const current = generation
    const activeKey = key
    const abort = new AbortController()
    controller = abort
    const history = conversations.get(activeKey)
    history.push({ role: 'user', text })
    state.busy = true
    state.error = ''
    try {
      const result = await agentApi.chat({ ...state.context, message: text }, fetchService, abort.signal)
      if (current !== generation || abort.signal.aborted) return
      if (typeof result?.answer !== 'string' || !Array.isArray(result.evidence) || !Array.isArray(result.uncertainties) || result.context?.appId !== state.context.appId) throw new Error('Agent 响应缺少匹配的业务上下文或证据。')
      history.push({ role: 'assistant', text: result.answer, context: result.context, evidence: result.evidence, uncertainties: result.uncertainties, groundingStatus: result.groundingStatus, suggestionId: result.suggestionId })
      return result
    } catch (error) {
      if (current === generation && !abort.signal.aborted) state.error = error.message || 'Agent 请求失败。'
    } finally { if (current === generation) state.busy = false }
  }
  function dispose() { generation++; controller?.abort() }
  return { state, setContext, ask, dispose }
}
