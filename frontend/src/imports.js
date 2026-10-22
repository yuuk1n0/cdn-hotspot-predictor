import { reactive } from 'vue'

import { requestJson } from './http.js'

const storageKey = 'cdn.csv-import.task'
export const maxFileBytes = 200 * 1024 * 1024

// All data-service requests use the existing Python proxy, never the Java /api proxy.
export const dataApi = {
  importTask: (id, fetchService = fetch, signal) => requestJson(`/data-api/imports/${encodeURIComponent(id)}`, fetchService, { signal }),
  datasets: (page = 1, fetchService = fetch, signal) => requestJson(`/data-api/datasets?page=${page}&pageSize=20`, fetchService, { signal }),
  dataset: (id, fetchService = fetch, signal) => requestJson(`/data-api/datasets/${encodeURIComponent(id)}`, fetchService, { signal }),
  quality: (id, fetchService = fetch, signal) => requestJson(`/data-api/datasets/${encodeURIComponent(id)}/quality`, fetchService, { signal }),
  async uploadImport(body, idempotencyKey, fetchService = fetch, signal) {
    return requestJson('/data-api/imports', async (path, options) => {
      const response = await fetchService(path, options)
      if (response.ok && response.status !== 202) {
        throw new Error('上传响应不符合契约：应返回 HTTP 202。')
      }
      return response
    }, {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': idempotencyKey },
      signal
    })
  }
}

function requireContract(condition, message) {
  if (!condition) throw new Error(`导入响应不符合契约：${message}`)
}

function readyDataset(value, id) {
  requireContract(value?.datasetId === id && value.status === 'READY', '数据集必须为对应编号的 READY 快照。')
  const units = { FILE_DAILY: 'B', APP_DAILY_TOTAL: 'GB_DECIMAL' }
  requireContract(Object.hasOwn(units, value.datasetType) && value.sourceUnit === units[value.datasetType] && ['REAL', 'FAKE'].includes(value.datasetSource), '数据集类型、单位或来源无效。')
  requireContract(Array.isArray(value.appIds) && value.appIds.length > 0 && value.appIds.every(app => typeof app === 'string' && app.length), '缺少有效 App 列表。')
  requireContract(value.appIds.every(app => value.appDateRanges?.[app]?.start && value.appDateRanges[app].end), '缺少 App 日期范围。')
  return value
}

export function createImportMonitor({ fetchService = fetch, pollIntervalMs = 2000, maxWaitMs = 300000 } = {}) {
  const state = reactive({
    importTaskId: '', phase: 'IDLE', busy: false, task: null, dataset: null, quality: null, error: '', notice: '',
    datasets: [], page: 1, total: 0, listBusy: false, listError: '',
    selectedDataset: null, selectedQuality: null, selectionBusy: false, selectionError: ''
  })
  let taskController, listController, selectionController, waitTimer
  let generation = 0, listGeneration = 0, selectionGeneration = 0

  async function loadDatasets(page = 1) {
    listController?.abort()
    const current = ++listGeneration
    const abort = new AbortController()
    listController = abort
    state.listBusy = true
    state.listError = ''
    try {
      const result = await dataApi.datasets(page, fetchService, abort.signal)
      if (current !== listGeneration || abort.signal.aborted) return
      requireContract(result?.page === page && Number.isInteger(result.total) && result.total >= 0 && Array.isArray(result.items), '数据集分页无效。')
      for (const item of result.items) readyDataset(item, item.datasetId)
      state.datasets = result.items
      state.page = page
      state.total = result.total
    } catch (error) {
      if (current === listGeneration && !abort.signal.aborted) { state.datasets = []; state.total = 0; state.listError = error.message || '数据集列表查询失败。' }
    } finally { if (current === listGeneration) state.listBusy = false }
  }

  async function selectDataset(id) {
    selectionController?.abort()
    const current = ++selectionGeneration
    const abort = new AbortController()
    selectionController = abort
    state.selectedDataset = state.selectedQuality = null
    state.selectionError = ''
    if (!id) { state.selectionBusy = false; return }
    state.selectionBusy = true
    try {
      const [metadata, quality] = await Promise.all([dataApi.dataset(id, fetchService, abort.signal), dataApi.quality(id, fetchService, abort.signal)])
      if (current !== selectionGeneration || abort.signal.aborted) return
      readyDataset(metadata, id)
      requireContract(quality && typeof quality === 'object' && !Array.isArray(quality) && Array.isArray(quality.warnings), '缺少质量报告。')
      state.selectedDataset = metadata
      state.selectedQuality = quality
      return metadata
    } catch (error) {
      if (current === selectionGeneration && !abort.signal.aborted) state.selectionError = error.message || '数据集查询失败。'
    } finally { if (current === selectionGeneration) state.selectionBusy = false }
  }

  function pause(notice = '已暂停页面查询；后台导入未被取消，可重查原编号。') {
    clearTimeout(waitTimer)
    generation++
    taskController?.abort()
    state.busy = false
    state.phase = 'PAUSED'
    state.notice = notice
  }

  async function queryTask(id) {
    clearTimeout(waitTimer)
    taskController?.abort()
    const current = ++generation
    const abort = new AbortController()
    taskController = abort
    const active = () => current === generation && !abort.signal.aborted
    state.importTaskId = id
    state.task = state.dataset = state.quality = null
    state.phase = 'QUERYING'
    state.busy = true
    state.error = state.notice = ''
    waitTimer = setTimeout(() => { if (active()) pause('已达到5分钟页面等待上限；后台未取消，可重查原编号。') }, maxWaitMs)
    try {
      requireContract(typeof id === 'string' && id.trim(), '缺少导入任务编号。')
      while (active()) {
        const task = await dataApi.importTask(id, fetchService, abort.signal)
        if (!active()) return
        requireContract(task?.importTaskId === id && ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(task.status), '任务编号或状态无效。')
        requireContract(task.status === 'SUCCEEDED' || task.datasetId == null, '非成功任务不能发布数据集。')
        state.task = task
        if (task.status === 'FAILED') {
          requireContract(task.error?.code && task.error?.message, '失败任务缺少原因。')
          state.phase = 'FAILED'
          state.quality = task.quality
          state.error = `${task.error.code}：${task.error.message}`
          return
        }
        if (task.status === 'SUCCEEDED') {
          requireContract(typeof task.datasetId === 'string' && task.datasetId.length, '成功任务缺少 datasetId。')
          const [metadata, quality] = await Promise.all([dataApi.dataset(task.datasetId, fetchService, abort.signal), dataApi.quality(task.datasetId, fetchService, abort.signal)])
          if (!active()) return
          readyDataset(metadata, task.datasetId)
          requireContract(quality && typeof quality === 'object' && !Array.isArray(quality) && Array.isArray(quality.warnings), '缺少质量报告。')
          state.dataset = metadata
          state.quality = quality
          clearTimeout(waitTimer)
          state.phase = 'SUCCEEDED'
          void loadDatasets()
          return
        }
        state.phase = 'POLLING'
        await new Promise((resolve, reject) => {
          const onAbort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
          const timer = setTimeout(() => { abort.signal.removeEventListener('abort', onAbort); resolve() }, pollIntervalMs)
          abort.signal.addEventListener('abort', onAbort, { once: true })
          if (abort.signal.aborted) onAbort()
        })
      }
    } catch (error) {
      if (active()) { state.phase = 'QUERY_ERROR'; state.error = error instanceof TypeError ? '无法连接 Python；查询已暂停，可重查原导入编号。' : error.message || '导入状态查询失败。' }
    } finally { if (current === generation) { clearTimeout(waitTimer); state.busy = false } }
  }

  function dispose() {
    clearTimeout(waitTimer)
    generation++; listGeneration++; selectionGeneration++
    taskController?.abort(); listController?.abort(); selectionController?.abort()
    state.busy = state.listBusy = state.selectionBusy = false
  }

  return { state, queryTask, resume: () => queryTask(state.importTaskId), pause, loadDatasets, selectDataset, dispose }
}

export function createCsvImport({ fetchService = fetch, storage = null } = {}) {
  const state = reactive({
    file: null,
    options: {
      datasetType: 'FILE_DAILY', datasetSource: 'REAL', sourceUnit: '', appId: '',
      mapping: { fileKey: '文件标识', date: '日期', trafficBytes: '当日流量（B）', appId: 'AppID' }
    },
    busy: false, phase: 'IDLE', error: '', importTaskId: ''
  })
  let operation = null
  let controller = null

  function selectFile(file) {
    if (state.busy || state.phase === 'ACCEPTED') return
    state.file = file || null
    operation = null
    state.error = ''
    state.phase = 'IDLE'
  }

  function validate() {
    if (!state.file || !/\.csv$/i.test(state.file.name)) throw new Error('请选择 CSV 文件。')
    if (!state.file.size) throw new Error('CSV 文件为空，请重新选择。')
    if (state.file.size > maxFileBytes) throw new Error('当前仅支持不超过 200 MiB 的 CSV；不会自动截取大文件。')
    const mapping = state.options.mapping
    if (state.options.datasetType === 'FILE_DAILY') {
      if (state.options.sourceUnit !== 'B') throw new Error('请先确认文件流量单位为 B；单位待确认的数据不能提交。')
      if (![mapping.fileKey, mapping.date, mapping.trafficBytes].every(value => value.trim())) {
        throw new Error('请填写文件标识、日期和流量对应的 CSV 列名。')
      }
    } else if (state.options.datasetType === 'APP_DAILY_TOTAL') {
      if (state.options.sourceUnit !== 'GB_DECIMAL') throw new Error('请确认总流量列为十进制 GB（1 GB = 10^9 B）；不会推断单位。')
      if (![mapping.date, mapping.totalTraffic].every(value => value.trim())) {
        throw new Error('请填写日期和 App 总流量对应的 CSV 列名。')
      }
    } else {
      throw new Error('未知的数据集类型。')
    }
    if (!mapping.appId.trim() && !state.options.appId.trim()) throw new Error('没有 App 列时，必须填写默认 App ID。')
  }

  function setDatasetType(type) {
    if (state.busy || state.phase === 'ACCEPTED') return
    if (type === 'APP_DAILY_TOTAL') {
      state.options.datasetType = type
      state.options.sourceUnit = ''
      state.options.mapping = { date: '日期', totalTraffic: 'flux(GB)', appId: 'AppId' }
    } else {
      state.options.datasetType = 'FILE_DAILY'
      state.options.sourceUnit = ''
      state.options.mapping = { fileKey: '文件标识', date: '日期', trafficBytes: '当日流量（B）', appId: 'AppID' }
    }
    operation = null
    state.error = ''
  }

  async function submit() {
    if (state.busy || state.phase === 'ACCEPTED') return
    state.busy = true
    state.error = ''
    controller = new AbortController()
    try {
      validate()
      const signature = JSON.stringify(state.options)
      if (!operation || operation.signature !== signature) {
        const body = new FormData()
        body.set('file', state.file)
        body.set('mapping', JSON.stringify(state.options.mapping))
        body.set('datasetType', state.options.datasetType)
        body.set('datasetSource', state.options.datasetSource)
        body.set('sourceUnit', state.options.sourceUnit)
        if (state.options.appId.trim()) body.set('appId', state.options.appId.trim())
        operation = { signature, body, key: crypto.randomUUID() }
      }
      state.phase = 'UPLOADING'
      // Retrying a lost response reuses the exact multipart body and idempotency key.
      const task = await dataApi.uploadImport(operation.body, operation.key, fetchService, controller.signal)
      if (!task || typeof task.importTaskId !== 'string' || !task.importTaskId.trim()) {
        throw new Error('上传响应不符合契约：缺少 importTaskId。')
      }
      if (controller.signal.aborted) return
      state.importTaskId = task.importTaskId
      state.phase = 'ACCEPTED'
      try { storage?.setItem(storageKey, task.importTaskId) } catch { /* Storage is optional. */ }
    } catch (error) {
      if (!controller.signal.aborted) {
        state.phase = 'FAILED'
        state.error = error instanceof TypeError
          ? '无法连接上传服务。请检查 Python 服务后重试；重试将沿用本次幂等键。'
          : error.message || '上传失败，请重试。'
      }
    } finally {
      state.busy = false
    }
  }

  function restore() {
    try {
      const taskId = storage?.getItem(storageKey)
      if (taskId && !state.busy && !state.importTaskId) {
        state.importTaskId = taskId
        state.phase = 'ACCEPTED'
      }
    } catch { /* Disabled browser storage must not prevent uploading. */ }
  }

  function reset() {
    if (state.busy) return
    state.file = null
    state.importTaskId = ''
    state.phase = 'IDLE'
    state.error = ''
    operation = null
    try { storage?.removeItem(storageKey) } catch {}
  }

  return { state, selectFile, setDatasetType, submit, restore, reset, dispose: () => controller?.abort() }
}
