import { requestJson } from './http.js'
import { validatePredictionResult } from './prediction-results.js'

function query(params) {
  const value = new URLSearchParams()
  for (const [key, item] of Object.entries(params)) if (item !== '' && item != null) value.set(key, String(item))
  return value.toString()
}

export const operationsApi = {
  runs: (appId, horizonDays, businessDate, fetchService = fetch, signal) =>
    requestJson(`/api/operations/runs?${query({ appId, horizonDays, businessDate })}`, fetchService, { signal }),
  latest: async (appId, horizonDays, fetchService = fetch, signal) => {
    const result = await requestJson(`/api/operations/latest?${query({ appId, horizonDays })}`, fetchService, { signal })
    return validatePredictionResult(result, result?.runId)
  },
  result: async (runId, fetchService = fetch, signal) => {
    const result = await requestJson(`/api/predictions/${encodeURIComponent(runId)}`, fetchService, { signal })
    return validatePredictionResult(result, runId)
  },
  metrics: (runId, fetchService = fetch, signal) =>
    requestJson(`/api/predictions/${encodeURIComponent(runId)}/metrics`, fetchService, { signal }),
  modelMetrics: (appId, horizonDays, fetchService = fetch, signal) =>
    requestJson(`/api/operations/metrics?${query({ appId, horizonDays })}`, fetchService, { signal }),
  pending: (appId, runId, fetchService = fetch, signal) =>
    requestJson(`/api/operations/pending?${query({ appId, runId, evidenceSource: 'REAL' })}`, fetchService, { signal }),
  effects: (appId, fetchService = fetch, signal) =>
    requestJson(`/api/operations/effects?${query({ appId, evidenceSource: 'REAL' })}`, fetchService, { signal }),
  history: (params, fetchService = fetch, signal) =>
    requestJson(`/api/history?${query(params)}`, fetchService, { signal })
}

function decimalBytes(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  const text = String(value).trim()
  const match = /^(\d+)(?:\.(\d+))?$/.exec(text)
  if (!match) return null
  try {
    return { units: BigInt(`${match[1]}${match[2] || ''}`), scale: (match[2] || '').length }
  } catch { return null }
}

export function metricPercent(metric) {
  if (!metric) return null
  const numeratorParts = decimalBytes(metric.numeratorBytes)
  const denominatorParts = decimalBytes(metric.denominatorBytes)
  if (numeratorParts != null || denominatorParts != null) {
    if (numeratorParts == null || denominatorParts == null) return null
    const scale = Math.max(numeratorParts.scale, denominatorParts.scale)
    const numerator = numeratorParts.units * 10n ** BigInt(scale - numeratorParts.scale)
    const denominator = denominatorParts.units * 10n ** BigInt(scale - denominatorParts.scale)
    if (denominator <= 0n) return null
    const scaled = (numerator * 1_000_000n + denominator / 2n) / denominator
    return Number(scaled) / 10_000
  }
  return null
}

export function metricsMatchRun(metrics, run) {
  if (!metrics || !run?.runId) return false
  return metrics.runId === run.runId
    && metrics.appId === run.appId
    && metrics.windowStart === run.predictionStartDate
    && metrics.windowEnd === run.predictionEndDate
}

export function ratioText(metric) {
  const value = metricPercent(metric)
  return value == null ? '不可计算' : `${value.toFixed(2)}%`
}

export function ratioReason(metric) {
  if (!metric) return 'NO_METRIC'
  if (metricPercent(metric) != null) return `${metric.numeratorSource} / ${metric.denominatorSource}`
  if (metric.value != null) return 'METRIC_BYTES_MISSING'
  return metric.reasonCode || 'UNKNOWN'
}
