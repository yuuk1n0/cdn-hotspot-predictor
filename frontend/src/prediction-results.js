const sources = new Set(['REAL', 'FAKE'])
const predictionSources = new Set(['MOCK', 'MODEL'])

export function contract(condition, message) {
  if (!condition) throw new Error(`响应契约错误：${message}`)
}

export function isId(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 128 && value === value.trim() && !/[\u0000-\u001f\u007f]/.test(value)
}

function isDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00.000Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function addDays(value, days) {
  const date = new Date(`${value}T00:00:00.000Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function metadata(value) {
  contract(value && isId(value.datasetId) && isId(value.appId) && isId(value.modelVersion), '缺少字符串数据集、App 或模型标识。')
  contract(isDate(value.cutoffDate) && [1, 7].includes(value.horizonDays), '截止日或预测跨度无效。')
}

function isBytes(value) {
  return typeof value === 'string' && value.length <= 256 && /^(0|[1-9]\d*)(\.\d+)?$/.test(value)
}

export function validatePredictionTask(value, expectedId) {
  contract(value && value.taskId === expectedId && isId(value.taskId), 'Java 任务编号不匹配。')
  contract(['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(value.status), '未知任务状态。')
  metadata(value)
  if (value.datasetSource != null) contract(sources.has(value.datasetSource), '任务数据来源无效。')
  if (value.predictionSource != null) contract(predictionSources.has(value.predictionSource), '任务预测来源无效。')
  if (value.status === 'SUCCEEDED') contract(isId(value.runId), '成功任务缺少有效 runId。')
  else contract(value.runId == null, '非成功任务不得暴露业务 runId。')
  if (value.status === 'FAILED') contract(typeof value.error?.code === 'string' && typeof value.error?.message === 'string', '失败任务缺少错误原因。')
  else contract(value.error == null, '非失败任务包含终态错误。')
  return value
}

export function validatePredictionResult(value, expectedId, task = null) {
  contract(value && isId(value.runId) && value.runId === expectedId, '榜单 runId 不匹配。')
  metadata(value)
  contract(sources.has(value.datasetSource) && predictionSources.has(value.predictionSource), '榜单缺少明确的数据或预测来源。')
  contract(isDate(value.predictionStartDate) && isDate(value.predictionEndDate)
    && value.predictionStartDate === addDays(value.cutoffDate, 1)
    && value.predictionEndDate === addDays(value.cutoffDate, value.horizonDays), '榜单预测窗口与截止日及跨度不符。')
  if (task) {
    for (const key of ['datasetId', 'appId', 'cutoffDate', 'horizonDays', 'modelVersion', 'runId']) {
      contract(value[key] === task[key], `榜单 ${key} 与 Java 业务任务不符。`)
    }
    for (const key of ['datasetSource', 'predictionSource']) {
      if (task[key] != null) contract(value[key] === task[key], `榜单 ${key} 与业务任务来源不符。`)
    }
  }
  contract(Array.isArray(value.items) && value.items.length <= 50, '榜单必须是最多 50 条的数组。')
  contract(Number.isSafeInteger(value.candidateCount) && value.candidateCount >= 0
    && Number.isSafeInteger(value.top50Count) && value.top50Count === value.items.length
    && value.top50Count <= value.candidateCount, '候选数或榜单条数不一致。')
  contract(isBytes(value.top50PredictedBytes), 'Top50 累计流量必须为非负十进制字符串 B。')
  const identities = new Set()
  for (const [index, item] of value.items.entries()) {
    contract(item && typeof item.fileKey === 'string' && item.fileKey.trim() && !identities.has(item.fileKey), '文件标识缺失或重复。')
    identities.add(item.fileKey)
    contract(item.rank === index + 1 && isBytes(item.predictedBytes), '名次或窗口累计流量无效。')
    for (const key of ['fileSizeBytes', 'recentActualBytes']) {
      if (item[key] != null) contract(isBytes(item[key]), `${key} 必须为十进制字符串或 null。`)
    }
    if (item.score != null) contract(typeof item.score === 'number' && Number.isFinite(item.score), '排序分数不是有限数值。')
    for (const key of ['fileType', 'compressionStatus', 'reason']) {
      if (item[key] != null) contract(typeof item[key] === 'string', `${key} 类型无效。`)
    }
  }
  // Preserve the server snapshot, byte strings and order; never compute predictions or fill 50 rows.
  return value
}

export function resultRows(result) {
  return result.items.map(item => ({
    ...item, appId: result.appId, modelVersion: result.modelVersion,
    predictionDate: result.predictionStartDate, predictionEndDate: result.predictionEndDate,
    granularity: result.horizonDays === 7 ? 'WEEKLY' : 'DAILY',
    fileType: item.fileType ?? null, fileSizeBytes: item.fileSizeBytes ?? null,
    recentActualBytes: item.recentActualBytes ?? null, compressionStatus: item.compressionStatus ?? null,
    score: item.score ?? null, reason: item.reason ?? null
  }))
}
