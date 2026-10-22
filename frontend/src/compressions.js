import { requestJson } from './http.js'

function valid(run, fileKey) {
  if (!run?.runId || !run?.appId || !['REAL', 'FAKE'].includes(run?.datasetSource) || !fileKey) throw new Error('须选择已有预测运行及原始文件标识。')
}

function matching(record, run, fileKey, source) {
  if (!record || record.runId !== run.runId || record.appId !== run.appId || record.fileKey !== fileKey ||
      (source && record.evidenceSource !== source)) throw new Error('压缩登记响应与当前运行或文件不匹配。')
  return record
}

export async function listCompressions(run, fileKey, fetchService = fetch) {
  valid(run, fileKey)
  const params = new URLSearchParams({ appId: run.appId, runId: run.runId, fileKey, page: '1', pageSize: '20' })
  const result = await requestJson(`/api/compressions?${params}`, fetchService)
  if (!Array.isArray(result?.items)) throw new Error('压缩登记列表响应无效。')
  return result.items.map(row => matching(row, run, fileKey))
}

export async function listRunCompressions(run, fetchService = fetch) {
  if (!run?.runId || !run?.appId) throw new Error('须选择已有预测运行。')
  const params = new URLSearchParams({ appId: run.appId, runId: run.runId, page: '1', pageSize: '100' })
  const result = await requestJson(`/api/compressions?${params}`, fetchService)
  if (!Array.isArray(result?.items)) throw new Error('压缩登记列表响应无效。')
  return result.items.filter(row => row.runId === run.runId && row.appId === run.appId)
}

export async function createCompression(run, fileKey, fields, idempotencyKey, fetchService = fetch) {
  valid(run, fileKey)
  if (fields.evidenceSource === 'REAL' && run.datasetSource !== 'REAL') throw new Error('FAKE 运行不能使用 REAL 处理证据。')
  const response = await fetchService('/api/compressions', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ runId: run.runId, appId: run.appId, fileKey, ...fields })
  })
  if (response.ok && ![200, 201].includes(response.status)) throw new Error('压缩登记响应状态无效。')
  const record = await requestJson('/api/compressions', async () => response)
  return matching(record, run, fileKey, fields.evidenceSource)
}

export async function updateCompression(run, fileKey, existing, fields, fetchService = fetch) {
  valid(run, fileKey)
  matching(existing, run, fileKey)
  const id = existing.id
  if (!id || !Number.isInteger(existing.version)) throw new Error('压缩登记版本无效，请刷新。')
  const record = await requestJson(`/api/compressions/${encodeURIComponent(id)}`, fetchService, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ appId: run.appId, version: existing.version, ...fields })
  })
  return matching(record, run, fileKey, existing.evidenceSource)
}
