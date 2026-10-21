import { reactive } from 'vue'
import { requestJson } from './http.js'

function requireValue(condition, message) {
  if (!condition) throw new Error(`历史查询契约：${message}`)
}

export function historyDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value ? date : null
}

export function historyWeekEnd(value) {
  const date = historyDate(value)
  if (!date) return ''
  date.setUTCDate(date.getUTCDate() + 6)
  return date.toISOString().slice(0, 10)
}

// Approximate display coordinates only; the response retains exact decimal strings.
export function historyChartData(days) {
  const bytes = days.map(day => day.observedTrafficBytes === null ? null : Number(day.observedTrafficBytes))
  const peak = Math.max(0, ...bytes.filter(Number.isFinite))
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB']
  const index = peak > 0 ? Math.min(units.length - 1, Math.floor(Math.log2(Math.max(1, peak)) / 10)) : 0
  const divisor = 1024 ** index
  return { unit: units[index], values: bytes.map(value => value === null ? null : value / divisor), maxY: peak > 0 ? peak / divisor * 1.1 : 1 }
}

function queryParams(value) {
  const p = { ...value, granularity: value?.granularity ?? 'DAILY', topK: value?.topK ?? 50 }
  for (const key of ['datasetId', 'appId']) {
    requireValue(typeof p[key] === 'string' && p[key].length > 0 && p[key].length <= 128 && p[key] === p[key].trim() && !/[\x00-\x1f\x7f]/.test(p[key]), `请提供有效的 ${key}。`)
  }
  const first = historyDate(p.startDate), last = historyDate(p.endDate)
  const count = first && last ? (last - first) / 86400000 + 1 : 0
  requireValue(count >= 1 && count <= 366, '日期无效，日查询范围须为1至366天。')
  requireValue(['DAILY', 'WEEKLY'].includes(p.granularity) && (p.granularity !== 'WEEKLY' || count === 7), '周窗口必须是连续七天。')
  requireValue(Number.isInteger(p.topK) && p.topK >= 1 && p.topK <= 50, 'topK 须为1至50。')
  requireValue(p.fileKey == null || typeof p.fileKey === 'string', 'fileKey 必须是原始文件标识。')
  return { datasetId: p.datasetId, appId: p.appId, startDate: p.startDate, endDate: p.endDate, granularity: p.granularity, topK: p.topK, ...(p.fileKey ? { fileKey: p.fileKey } : {}) }
}

const byteString = value => typeof value === 'string' && /^(0|[1-9]\d*)(\.\d+)?$/.test(value) && value.length <= 512

function validateResult(result, p) {
  for (const key of ['datasetId', 'appId', 'startDate', 'endDate', 'granularity']) requireValue(result?.[key] === p[key], `${key} 与本次请求不符。`)
  requireValue(result.scope === 'OBSERVED_FILES' && ['REAL', 'FAKE'].includes(result.datasetSource), '观测范围或数据来源无效。')
  requireValue(typeof result.completeness === 'string', '缺少完整性说明。')
  requireValue(result.observedTrafficBytes === null || byteString(result.observedTrafficBytes), '合计字节必须为精确字符串或 null。')
  requireValue(Array.isArray(result.days) && Array.isArray(result.items) && Array.isArray(result.missingDates), '缺少逐日观测或文件排行。')
  const count = (historyDate(p.endDate) - historyDate(p.startDate)) / 86400000 + 1
  requireValue(result.days.length === count, '逐日日期未完整覆盖查询窗口。')
  const first = historyDate(p.startDate), dates = new Set()
  result.days.forEach((day, index) => {
    const expected = new Date(first.getTime() + index * 86400000).toISOString().slice(0, 10)
    requireValue(day.date === expected && Number.isInteger(day.fileCount) && day.fileCount >= 0, '逐日日期或文件数无效。')
    requireValue(day.status === 'OBSERVED' ? byteString(day.observedTrafficBytes) && day.fileCount > 0 : day.status === 'NO_OBSERVATION' && day.observedTrafficBytes === null && day.fileCount === 0, '无观测必须保留 null，显式0B须标为已观测。')
    dates.add(day.date)
  })
  requireValue(new Set(result.missingDates).size === result.missingDates.length && result.missingDates.every(date => dates.has(date) && result.days.find(day => day.date === date).status === 'NO_OBSERVATION'), 'App 缺日列表无效。')
  requireValue(result.items.length <= p.topK, '返回排行超出 topK。')
  const files = new Set()
  result.items.forEach((item, index) => {
    requireValue(item.rank === index + 1 && typeof item.fileKey === 'string' && item.fileKey.length > 0 && !files.has(item.fileKey) && byteString(item.observedTrafficBytes) && Number.isInteger(item.observedDays) && item.observedDays > 0 && item.observedDays <= count, '文件排行字段无效。')
    requireValue(!p.fileKey || item.fileKey === p.fileKey, '返回文件不是请求的精确标识。')
    files.add(item.fileKey)
  })
  requireValue(result.hotspotTrafficRatio?.value === null, '未确认 App 全量分母，不应返回热点占比。')
  return result
}

export function createHistoryQuery({ fetchService = fetch } = {}) {
  const state = reactive({ busy: false, result: null, request: null, error: '' })
  let generation = 0, controller
  function clear() {
    generation++
    controller?.abort()
    state.busy = false
    state.result = state.request = null
    state.error = ''
  }
  async function load(params) {
    clear()
    const current = generation
    const abort = new AbortController()
    controller = abort
    state.busy = true
    try {
      const p = queryParams(params)
      state.request = p
      const payload = await requestJson(`/api/history?${new URLSearchParams(p)}`, fetchService, { signal: abort.signal })
      if (current !== generation || abort.signal.aborted) return
      state.result = validateResult(payload, p)
      return state.result
    } catch (error) {
      if (current === generation && !abort.signal.aborted) state.error = error.message || '历史查询失败，请重试。'
    } finally {
      if (current === generation) state.busy = false
    }
  }
  return { state, load, clear, dispose: clear }
}
