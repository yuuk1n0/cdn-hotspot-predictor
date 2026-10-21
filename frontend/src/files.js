import { reactive } from 'vue'
import { requestJson } from './http.js'

const validContext = run => run?.datasetSource === 'REAL' && typeof run.runId === 'string' && typeof run.appId === 'string'
const requireContract = condition => { if (!condition) throw new Error('文件服务响应与当前文件不匹配。') }
const validSource = source => ['CSTORE', 'CSTORE_TEST'].includes(source)
const options = body => ({ method: 'POST', cache: 'no-store', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })

export async function fetchFileMetadata(run, keys, fetchService = fetch) {
  if (!validContext(run)) throw new Error('仅真实数据运行可查询 CStore 文件信息。')
  const data = await requestJson('/api/files/metadata', fetchService, options({ runId: run.runId, fileKeys: keys }))
  requireContract(data.runId === run.runId && data.appId === run.appId && Array.isArray(data.items) && data.items.length === keys.length)
  const found = new Set()
  for (const item of data.items) {
    requireContract(item.runId === run.runId && item.appId === run.appId && keys.includes(item.fileKey) && !found.has(item.fileKey) && validSource(item.dataSource))
    found.add(item.fileKey)
    requireContract(item.status === 'READY' ? typeof item.fileSizeBytes === 'string' && /^(0|[1-9]\d*)$/.test(item.fileSizeBytes) : item.status === 'UNAVAILABLE' && item.fileSizeBytes == null)
  }
  return data.items
}

/** A disposable detail session. Signed URLs never join prediction results or browser storage. */
export function createFileAccess({ fetchService = fetch } = {}) {
  const state = reactive({ url: '', busy: false, error: '', purpose: '', dataSource: '' })
  let current = null
  let generation = 0
  function clear() {
    generation++
    current = null
    Object.assign(state, { url: '', busy: false, error: '', purpose: '', dataSource: '' })
  }
  function select(run, key) { clear(); current = { run, key } }
  async function request(purpose) {
    if (state.busy) return null
    const requestId = ++generation
    const context = current
    Object.assign(state, { url: '', error: '', purpose, dataSource: '' })
    if (!validContext(context?.run) || !context?.key || !['PREVIEW', 'DOWNLOAD'].includes(purpose)) {
      state.error = '当前文件没有可用于 CStore 查询的真实运行。'
      return null
    }
    state.busy = true
    try {
      const data = await requestJson('/api/files/access-url', fetchService, options({ runId: context.run.runId, fileKey: context.key, purpose }))
      if (requestId !== generation) return null
      requireContract(data.runId === context.run.runId && data.appId === context.run.appId && data.fileKey === context.key && data.purpose === purpose && validSource(data.dataSource))
      const url = new URL(data.url)
      requireContract(['http:', 'https:'].includes(url.protocol) && !url.username && !url.password)
      state.url = url.href
      state.dataSource = data.dataSource
      return state.url
    } catch (error) {
      if (requestId === generation) { state.url = ''; state.error = error.message || '文件访问地址查询失败。' }
      return null
    } finally { if (requestId === generation) state.busy = false }
  }
  return { state, select, request, clear }
}
