import { reactive } from 'vue'

import { requestJson } from './http.js'
import { contract, isId, validatePredictionTask, validatePredictionResult } from './prediction-results.js'

export { resultRows, validatePredictionResult } from './prediction-results.js'

export const predictionApi = {
  submit: (body, key, fetchService = fetch, signal) => requestJson('/api/predictions', async (path, options) => {
    const response = await fetchService(path, options)
    contract(!response.ok || response.status === 202, '预测接收应返回 HTTP 202。')
    return response
  }, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(body), signal }),
  task: (id, fetchService = fetch, signal) => requestJson(`/api/tasks/${encodeURIComponent(id)}`, fetchService, { signal }),
  run: (id, fetchService = fetch, signal) => requestJson(`/api/predictions/${encodeURIComponent(id)}`, fetchService, { signal })
}

const storageKey = 'cdn.prediction.lookup'
const submissionKey = 'cdn.prediction.submission'

function requestBody(params) {
  const body = Object.fromEntries(['datasetId', 'appId', 'cutoffDate', 'horizonDays', 'modelVersion'].map(key => [key, params?.[key]]))
  contract(isId(body.datasetId) && isId(body.appId) && isId(body.modelVersion), '请选择有效的数据集、App与已部署模型版本。')
  contract(typeof body.cutoffDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(body.cutoffDate), '截止日格式必须为YYYY-MM-DD。')
  const day = new Date(`${body.cutoffDate}T00:00:00Z`)
  contract(Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === body.cutoffDate, '截止日无效。')
  contract([1, 7].includes(body.horizonDays), '预测跨度只能为1或7天。')
  return body
}

export function createPredictionSubmit({ fetchService = fetch, storage = null } = {}) {
  const state = reactive({ busy: false, error: '', taskId: '', request: null, hasRetry: false, restored: false })
  let operation = null
  let controller = null

  function save() {
    try { storage?.setItem(submissionKey, JSON.stringify(operation)) } catch { /* Optional browser storage. */ }
  }

  async function send() {
    if (state.busy || !operation) return
    if (operation.taskId) return operation.taskId
    state.busy = true
    state.error = ''
    state.taskId = ''
    const abort = new AbortController()
    controller = abort
    try {
      const receipt = await predictionApi.submit(operation.body, operation.key, fetchService, abort.signal)
      if (abort.signal.aborted) return
      contract(isId(receipt?.taskId) && ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(receipt.status), '缺少合法的Java任务接收编号或状态。')
      operation.taskId = receipt.taskId
      state.taskId = receipt.taskId
      state.hasRetry = false
      save()
      return receipt.taskId
    } catch (error) {
      if (!abort.signal.aborted) {
        state.hasRetry = true
        state.error = error instanceof TypeError ? '无法确认Java是否接收；请重试本次提交，保留原五字段及幂等键。' : error.message || '提交失败，请重试本次提交。'
      }
    } finally { state.busy = false }
  }

  async function submit(params, dataset) {
    if (state.busy) return
    state.error = ''
    try {
      const body = requestBody(params)
      contract(dataset?.datasetId === body.datasetId && dataset.appId === body.appId, '请先选择对应READY数据集与App。')
      const range = dataset.appDateRanges?.[body.appId]
      contract(range?.start && range?.end && body.cutoffDate >= range.start && body.cutoffDate <= range.end, '截止日必须在所选App的观测日期范围内。')
      if (!operation || JSON.stringify(operation.body) !== JSON.stringify(body)) {
        operation = { key: crypto.randomUUID(), body: Object.freeze(body), taskId: null }
        state.taskId = ''
        state.request = body
        state.restored = false
        state.hasRetry = false
        save()
      }
      return await send()
    } catch (error) { state.error = error.message || '预测参数无效。' }
  }

  function restore() {
    try {
      const saved = JSON.parse(storage?.getItem(submissionKey) || 'null')
      if (!saved || typeof saved.key !== 'string' || !saved.key.trim() || saved.key.length > 200) return
      const body = requestBody(saved.body)
      if (saved.taskId != null && !isId(saved.taskId)) return
      operation = { key: saved.key, body: Object.freeze(body), taskId: saved.taskId || null }
      state.request = body
      state.taskId = operation.taskId || ''
      state.hasRetry = !operation.taskId
      state.restored = true
    } catch { /* Corrupt/disabled storage never triggers a POST. */ }
  }

  return { state, submit, retry: send, restore, dispose: () => controller?.abort() }
}

export function createPredictionLookup({ fetchService = fetch, storage = null, pollIntervalMs = 2000, maxWaitMs = 300000, now = Date.now } = {}) {
  const state = reactive({
    kind: 'task', id: '', busy: false, phase: 'IDLE', task: null, result: null,
    resultIsCurrent: false, error: '', notice: ''
  })
  let controller = null
  let generation = 0
  let waitTimer = null

  async function query(kind, input) {
    clearTimeout(waitTimer)
    controller?.abort()
    const current = ++generation
    const abort = new AbortController()
    controller = abort
    const active = () => current === generation && !abort.signal.aborted
    state.busy = true
    state.phase = 'QUERYING'
    state.error = state.notice = ''
    state.resultIsCurrent = false
    state.task = null
    try {
      const id = typeof input === 'string' ? input.trim() : input
      contract(['task', 'run'].includes(kind) && isId(id), '请输入有效的字符串 taskId 或 runId。')
      state.kind = kind
      state.id = id
      try { storage?.setItem(storageKey, JSON.stringify({ kind, id })) } catch {}
      waitTimer = setTimeout(() => {
        if (active()) stopWaiting('已达到浏览器等待上限，暂停查询；后台任务未被取消，可继续查询原编号。')
      }, maxWaitMs)
      const started = now()
      let runId = id
      let task = null
      if (kind === 'task') {
        while (active()) {
          const response = await predictionApi.task(id, fetchService, abort.signal)
          if (!active()) return
          task = validatePredictionTask(response, id)
          state.task = task
          if (task.status === 'FAILED') {
            state.phase = 'TASK_FAILED'
            state.error = `${task.error.code}：${task.error.message}`
            return
          }
          if (task.status === 'SUCCEEDED') { runId = task.runId; break }
          state.phase = 'POLLING'
          if (now() - started >= maxWaitMs) {
            state.phase = 'PAUSED'
            state.notice = '已暂停自动查询；Java 任务未被取消，可继续查询原任务。'
            return
          }
          await pause(pollIntervalMs, abort.signal)
        }
      }
      if (!active()) return
      state.phase = 'LOADING_RESULT'
      const result = await predictionApi.run(runId, fetchService, abort.signal)
      if (!active()) return
      validatePredictionResult(result, runId, task)
      state.result = result
      state.resultIsCurrent = true
      state.phase = 'READY'
    } catch (error) {
      if (!active()) return
      const loadingResult = state.phase === 'LOADING_RESULT'
      state.phase = 'QUERY_ERROR'
      if (error.status === 404) {
        state.error = loadingResult || kind === 'run' || state.task?.status === 'SUCCEEDED'
          ? '榜单接口尚不可用或 runId 不存在（HTTP 404）；未加载榜单。'
          : 'Java 任务不存在（HTTP 404），请核对 taskId。'
      } else state.error = error instanceof TypeError ? '无法连接 Java 服务，请检查服务后继续查询原编号。' : error.message || '查询失败，请重试原编号。'
    } finally {
      if (current === generation) { clearTimeout(waitTimer); state.busy = false }
    }
  }

  function stopWaiting(notice) {
    if (!state.busy) return
    clearTimeout(waitTimer)
    generation++
    controller?.abort()
    state.busy = false
    state.phase = 'PAUSED'
    state.notice = notice
  }

  async function restore(preferred = {}) {
    if (preferred.taskId) return query('task', preferred.taskId)
    if (preferred.runId) return query('run', preferred.runId)
    let saved
    try { saved = JSON.parse(storage?.getItem(storageKey) || 'null') } catch { return }
    if (saved && ['task', 'run'].includes(saved.kind) && isId(saved.id)) return query(saved.kind, saved.id)
  }

  return {
    state, queryTask: id => query('task', id), queryRun: id => query('run', id),
    resume: () => query(state.kind, state.id), pause: () => stopWaiting('已暂停自动查询；不会取消 Java 任务。'), restore,
    dispose: () => { clearTimeout(waitTimer); generation++; controller?.abort(); state.busy = false }
  }
}

function pause(ms, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, ms)
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
  })
}
