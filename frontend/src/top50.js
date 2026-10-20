import { requestJson } from './http.js'

const filterKeys = [
  'startDate',
  'endDate',
  'appId',
  'granularity',
  'modelVersion',
  'compressionStatus'
]

export function buildTop50Query(filters, page, pageSize) {
  const parameters = new URLSearchParams()
  for (const key of filterKeys) {
    if (filters[key]) {
      parameters.set(key, filters[key])
    }
  }
  parameters.set('page', String(page))
  parameters.set('pageSize', String(pageSize))
  return parameters.toString()
}

export async function fetchTop50(filters, page, pageSize, fetchService = fetch) {
  return requestJson(`/api/top50?${buildTop50Query(filters, page, pageSize)}`, fetchService)
}

export async function fetchTop50Options(fetchService = fetch) {
  return requestJson('/api/top50/options', fetchService)
}

export function formatBytes(value) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '—'
  }
  if (bytes === 0) {
    return '0 B'
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  const unitIndex = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const formatted = bytes / (1024 ** unitIndex)
  const digits = formatted >= 100 || Number.isInteger(formatted) ? 0 : 1
  return `${formatted.toFixed(digits)} ${units[unitIndex]}`
}

export function formatScore(value) {
  const score = Number(value)
  return Number.isFinite(score) ? score.toFixed(3) : '—'
}

export const fileTypeLabels = {
  VIDEO: '视频',
  IMAGE: '图片',
  AUDIO: '音频',
  OTHER: '其他'
}

export const granularityLabels = {
  DAILY: '日',
  WEEKLY: '周'
}

export const compressionStatusLabels = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  COMPRESSED: '已压缩'
}
