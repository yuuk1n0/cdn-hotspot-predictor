import { test } from 'node:test'
import assert from 'node:assert/strict'
import { alertApi } from '../src/alerts.js'

function json(body, status = 200) {
  return { ok: status < 400, status, json: async () => body }
}

test('alert route update carries optimistic version', async () => {
  const calls = []
  const fetchService = async (url, options) => {
    calls.push({ url, options })
    return json({ appId: '10350', version: 1 })
  }

  const result = await alertApi.updateRoute('10350', {
    expectedVersion: 0,
    alertsEnabled: true
  }, fetchService)

  assert.equal(result.version, 1)
  assert.match(calls[0].url, /\/api\/v1\/alert-routes\/10350$/)
  assert.equal(calls[0].options.method, 'PUT')
  assert.equal(JSON.parse(calls[0].options.body).expectedVersion, 0)
  assert.equal(calls[0].options.headers['Content-Type'], 'application/json')
})

test('incident list encodes app filters and never sends an alert ingest token', async () => {
  const calls = []
  const fetchService = async (url, options) => {
    calls.push({ url, options })
    return json({ page: 2, pageSize: 20, total: 0, items: [] })
  }

  await alertApi.listIncidents({
    appId: '10/350',
    status: 'OPEN',
    severity: 'P1',
    page: 2,
    pageSize: 20
  }, fetchService)

  assert.match(calls[0].url, /appId=10%2F350/)
  assert.match(calls[0].url, /status=OPEN/)
  assert.match(calls[0].url, /severity=P1/)
  assert.match(calls[0].url, /page=2/)
  assert.equal(calls[0].options.body, undefined)
  assert.equal(calls[0].options.headers, undefined)
})

test('route validate and DEMO test send use POST endpoints without client identity', async () => {
  const calls = []
  const fetchService = async (url, options) => {
    calls.push({ url, options })
    return json({ appId: '10066', valid: true, sent: true })
  }

  await alertApi.validateRoute('10066', fetchService)
  await alertApi.testRoute('10066', fetchService)

  assert.match(calls[0].url, /\/alert-routes\/10066\/validate$/)
  assert.match(calls[1].url, /\/alert-routes\/10066\/test$/)
  assert.equal(calls[0].options.method, 'POST')
  assert.equal(calls[1].options.method, 'POST')
  assert.equal(calls[0].options.body, undefined)
  assert.equal(calls[1].options.body, undefined)
})
