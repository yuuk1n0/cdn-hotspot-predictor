import { requestJson } from './http.js'

function query(params) {
  const value = new URLSearchParams()
  for (const [key, item] of Object.entries(params || {})) {
    if (item !== '' && item != null) value.set(key, String(item))
  }
  const text = value.toString()
  return text ? `?${text}` : ''
}

function appPath(appId) {
  return `/api/v1/alert-routes/${encodeURIComponent(appId)}`
}

function jsonOptions(method, body, signal) {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal
  }
}

export const alertApi = {
  listIncidents: (params, fetchService = fetch, signal) =>
    requestJson(`/api/v1/alerts${query({
      appId: params.appId,
      status: params.status,
      severity: params.severity,
      page: params.page ?? 1,
      pageSize: params.pageSize ?? 20
    })}`, fetchService, { signal }),

  getIncident: (incidentId, fetchService = fetch, signal) =>
    requestJson(`/api/v1/alerts/${encodeURIComponent(incidentId)}`, fetchService, { signal }),

  listRoutes: (fetchService = fetch, signal) =>
    requestJson('/api/v1/alert-routes', fetchService, { signal }),

  getRoute: (appId, fetchService = fetch, signal) =>
    requestJson(appPath(appId), fetchService, { signal }),

  updateRoute: (appId, body, fetchService = fetch, signal) =>
    requestJson(appPath(appId), fetchService, jsonOptions('PUT', body, signal)),

  validateRoute: (appId, fetchService = fetch, signal) =>
    requestJson(`${appPath(appId)}/validate`, fetchService, { method: 'POST', signal }),

  testRoute: (appId, fetchService = fetch, signal) =>
    requestJson(`${appPath(appId)}/test`, fetchService, { method: 'POST', signal })
}
