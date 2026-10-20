import { reactive } from 'vue'

import { requestJson } from './http.js'

const storageKey = 'cdn.csv-import.task'
export const maxFileBytes = 5 * 1024 * 1024

// All data-service requests use the existing Python proxy, never the Java /api proxy.
export const dataApi = {
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

export function createCsvImport({ fetchService = fetch, storage = null } = {}) {
  const state = reactive({
    file: null,
    options: {
      datasetSource: 'REAL', sourceUnit: '', appId: '',
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
    if (state.file.size > maxFileBytes) throw new Error('当前仅支持不超过 5 MiB 的 CSV；不会自动截取大文件。')
    if (state.options.sourceUnit !== 'B') throw new Error('请先确认流量单位为 B；单位待确认的数据不能提交。')
    const mapping = state.options.mapping
    if (![mapping.fileKey, mapping.date, mapping.trafficBytes].every(value => value.trim())) {
      throw new Error('请填写文件标识、日期和流量对应的 CSV 列名。')
    }
    if (!mapping.appId.trim() && !state.options.appId.trim()) throw new Error('没有 App 列时，必须填写默认 App ID。')
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
        body.set('datasetType', 'FILE_DAILY')
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

  return { state, selectFile, submit, restore, reset, dispose: () => controller?.abort() }
}
