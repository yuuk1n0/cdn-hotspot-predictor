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

export function ratioText(metric) {
  return metric?.value == null ? '不可计算' : `${Number(metric.value).toFixed(2)}%`
}

export function ratioReason(metric) {
  if (!metric) return 'NO_METRIC'
  if (metric.value != null) return `${metric.numeratorSource} / ${metric.denominatorSource}`
  return metric.reasonCode || 'UNKNOWN'
}
