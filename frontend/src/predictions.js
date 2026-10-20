import { reactive } from 'vue'

import { requestJson } from './http.js'
import { contract, isId, validatePredictionTask, validatePredictionResult } from './prediction-results.js'

export { resultRows, validatePredictionResult } from './prediction-results.js'

export const predictionApi = {
  task: (id, fetchService = fetch, signal) => requestJson(`/api/tasks/${encodeURIComponent(id)}`, fetchService, { signal }),
  run: (id, fetchService = fetch, signal) => requestJson(`/api/predictions/${encodeURIComponent(id)}`, fetchService, { signal })
}

const storageKey = 'cdn.prediction.lookup'

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
