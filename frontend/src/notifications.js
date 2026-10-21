import { reactive } from 'vue'
import { requestJson } from './http.js'

function query(params) {
  const value = new URLSearchParams()
  for (const [key, item] of Object.entries(params)) if (item !== '' && item != null) value.set(key, String(item))
  return value.toString()
}

function jsonOptions(method, body, signal, headers = {}) {
  return { method, headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body), signal }
}

export const notificationApi = {
  destinations: (fetchService = fetch, signal) => requestJson('/api/notifications/destinations', fetchService, { signal }),
  config: (appId, fetchService = fetch, signal) => requestJson(`/api/notifications/config?${query({ appId })}`, fetchService, { signal }),
  updateConfig: (appId, body, fetchService = fetch, signal) => requestJson(`/api/notifications/config?${query({ appId })}`, fetchService, jsonOptions('PUT', body, signal)),
  runs: (appId, horizonDays, businessDate, fetchService = fetch, signal) => requestJson(`/api/operations/runs?${query({ appId, horizonDays, businessDate })}`, fetchService, { signal }),
  preview: (params, fetchService = fetch, signal) => requestJson(`/api/notifications/preview?${query(params)}`, fetchService, signal ? { signal } : undefined),
  list: (params, fetchService = fetch, signal) => requestJson(`/api/notifications?${query({ ...params, page: params.page ?? 1, pageSize: params.pageSize ?? 20 })}`, fetchService, { signal }),
  overdue: (params, fetchService = fetch, signal) => requestJson(`/api/overdue?${query(params)}`, fetchService, { signal }),
  send: (body, idempotencyKey, fetchService = fetch, signal) => requestJson('/api/notifications/send', async (url, options) => {
    const response = await fetchService(url, options)
    if (response.ok && response.status !== 202) throw new Error('发送响应不符合契约：应返回 HTTP 202。')
    return response
  }, jsonOptions('POST', body, signal, { 'Idempotency-Key': idempotencyKey }))
}

const previewFields = ['appId', 'runId', 'type', 'purpose', 'evidenceSource']
function matchesPreview(preview, input) {
  return previewFields.every(key => preview?.[key] === input?.[key])
}

export function createNotificationSession({ fetchService = fetch } = {}) {
  const state = reactive({ preview: null, input: null, destinationId: '', sent: null, busy: false, error: '' })
  let generation = 0
  let controller
  let operation
  function invalidate() {
    generation++
    controller?.abort()
    state.preview = state.input = state.sent = null
    state.busy = false
    state.error = ''
    operation = null
  }
  function setDestination(destinationId) {
    state.destinationId = destinationId
    state.sent = null
    operation = null
  }
  async function preview(input) {
    invalidate()
    const current = generation
    const abort = new AbortController()
    controller = abort
    state.busy = true
    try {
      const result = await notificationApi.preview(input, fetchService, abort.signal)
      if (current !== generation || abort.signal.aborted) return
      if (!matchesPreview(result, input) || typeof result.text !== 'string' || typeof result.canSend !== 'boolean') throw new Error('预览响应缺少匹配的运行上下文或正文。')
      state.input = { ...input }
      state.preview = result
      return result
    } catch (error) {
      if (current === generation && !abort.signal.aborted) state.error = error.message || '通知预览失败。'
    } finally { if (current === generation) state.busy = false }
  }
  async function send(destinationId) {
    if (!state.preview || !state.input) throw new Error('请先预览通知内容。')
    if (!state.preview.canSend) throw new Error('当前预览不允许发送。')
    if (!destinationId || destinationId !== state.destinationId) throw new Error('请确认当前发送目标。')
    if (state.busy) return
    const current = generation
    const key = JSON.stringify({ input: state.input, destinationId, text: state.preview.text })
    if (operation?.signature !== key) operation = { signature: key, idempotencyKey: crypto.randomUUID() }
    state.busy = true
    state.error = ''
    try {
      const result = await notificationApi.send({ ...state.input, destinationId }, operation.idempotencyKey, fetchService)
      if (current !== generation) return
      if (!result?.notificationId || !['PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN', 'SKIPPED'].includes(result.status)) throw new Error('发送响应缺少有效的投递记录。')
      state.sent = result
      return result
    } catch (error) {
      if (current === generation) state.error = error.message || '发送请求失败，请查询发送记录。'
      throw error
    } finally { if (current === generation) state.busy = false }
  }
  return { state, invalidate, setDestination, preview, send }
}
