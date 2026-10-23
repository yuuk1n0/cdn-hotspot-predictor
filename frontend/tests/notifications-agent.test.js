import { test } from 'node:test'
import assert from 'node:assert/strict'
import { notificationApi, createNotificationSession } from '../src/notifications.js'
import { agentApi, createAgentSession, shanghaiBusinessDate } from '../src/agent.js'

function json(body, status = 200) {
  return { ok: status < 400, status, json: async () => body }
}

test('notification preview is read only and send requires a matching reviewed preview', async () => {
  const calls = []
  const fetchService = async (url, options) => {
    calls.push({ url, options })
    return json(url.includes('/send') ? { notificationId: 'n-1', status: 'PENDING' } : {
      appId: '10350', runId: 'run-1', type: 'DAILY', purpose: 'DEMO', evidenceSource: 'TEST',
      businessDate: '2026-10-20', predictionStartDate: '2026-10-20', predictionEndDate: '2026-10-20',
      horizonDays: 1, modelVersion: 'm1', datasetSource: 'REAL', predictionSource: 'MOCK',
      itemCount: 1, items: [{ fileKey: 'a' }], text: '真实预览正文', canSend: true, reasonCodes: []
    }, url.includes('/send') ? 202 : 200)
  }
  const session = createNotificationSession({ fetchService })
  const input = { appId: '10350', runId: 'run-1', type: 'DAILY', purpose: 'DEMO', evidenceSource: 'TEST' }
  await assert.rejects(session.send('test'), /先预览/)
  await session.preview(input)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].options.method, undefined)
  assert.match(calls[0].url, /purpose=DEMO/)
  assert.equal(session.state.preview.text, '真实预览正文')
  assert.equal(session.state.preview.businessDate, '2026-10-20')
  session.setDestination('test')
  await assert.rejects(session.send('other'), /目标/)
  await session.send('test')
  assert.equal(calls.length, 2)
  assert.equal(calls[1].options.method, 'POST')
  assert.equal(JSON.parse(calls[1].options.body).destinationId, 'test')
  assert.ok(calls[1].options.headers['Idempotency-Key'])
  assert.equal(session.state.sent.status, 'PENDING')
})

test('changing notification context invalidates preview and drops a late response', async () => {
  let release
  const fetchService = () => new Promise(resolve => { release = resolve })
  const session = createNotificationSession({ fetchService })
  const pending = session.preview({ appId: '10350', runId: 'old', type: 'DAILY', purpose: 'DEMO', evidenceSource: 'TEST' })
  session.invalidate()
  release(json({ appId: '10350', runId: 'old', text: 'stale', canSend: true }))
  await pending
  assert.equal(session.state.preview, null)
})

test('an idempotent send may return an existing SENT record', async () => {
  const fetchService = async (url) => json(url.includes('/send')
    ? { notificationId: 'n-existing', status: 'SENT' }
    : { appId: '10350', runId: 'run-1', type: 'DAILY', purpose: 'DEMO', evidenceSource: 'TEST', text: '原正文', canSend: true }, url.includes('/send') ? 202 : 200)
  const session = createNotificationSession({ fetchService })
  await session.preview({ appId: '10350', runId: 'run-1', type: 'DAILY', purpose: 'DEMO', evidenceSource: 'TEST' })
  session.setDestination('test')
  const result = await session.send('test')
  assert.equal(result.status, 'SENT')
})

test('notification endpoints encode filters and config updates carry expected version', async () => {
  const calls = []
  const fetchService = async (url, options) => { calls.push({ url, options }); return json({ items: [] }) }
  await notificationApi.list({ appId: '10/350', runId: 'a b', type: 'OVERDUE', page: 2 }, fetchService)
  await notificationApi.updateConfig('10350', { expectedVersion: 3, handlingHours: 24 }, fetchService)
  assert.match(calls[0].url, /appId=10%2F350/)
  assert.match(calls[0].url, /runId=a\+b/)
  assert.match(calls[0].url, /page=2&pageSize=20/)
  assert.equal(calls[1].options.method, 'PUT')
  assert.equal(JSON.parse(calls[1].options.body).expectedVersion, 3)
})

test('agent session isolates app and date contexts and ignores late answers', async () => {
  const pending = []
  const fetchService = (url, options) => new Promise(resolve => pending.push({ url, options, resolve }))
  const session = createAgentSession({ fetchService })
  session.setContext({ appId: '10350', businessDate: '2026-10-21', horizonDays: 1, runId: '' })
  const first = session.ask('今天 Top10？')
  session.setContext({ appId: '10066', businessDate: '2026-10-21', horizonDays: 1, runId: '' })
  pending[0].resolve(json({ answer: '旧回答', context: { appId: '10350' }, evidence: [], uncertainties: [], suggestionId: null }))
  await first
  assert.equal(session.state.messages.length, 0)
  session.setContext({ appId: '10350', businessDate: '2026-10-21', horizonDays: 1, runId: '' })
  assert.equal(session.state.messages.length, 1)
  assert.equal(session.state.messages[0].text, '今天 Top10？')
})

test('agent errors are visible and evidence stays server supplied', async () => {
  const calls = []
  const fetchService = async (url, options) => {
    calls.push({ url, options })
    return calls.length === 1
      ? json({ error: { code: 'AGENT_UNCONFIGURED', message: '未配置模型' } }, 503)
      : json({ answer: '有两项', context: { appId: '10350', businessDate: '2026-10-21' }, evidence: [{ tool: 'getTopHotspots', queriedAt: '2026-10-21T01:00:00Z', data: { itemCount: 2 } }], uncertainties: ['MOCK'], suggestionId: null })
  }
  const session = createAgentSession({ fetchService })
  session.setContext({ appId: '10350', businessDate: '2026-10-21', horizonDays: 1 })
  await session.ask('热点？')
  assert.match(session.state.error, /AGENT_UNCONFIGURED/)
  await session.ask('再次查询')
  assert.equal(session.state.messages.at(-1).evidence[0].data.itemCount, 2)
  assert.deepEqual(session.state.messages.at(-1).uncertainties, ['MOCK'])
})

test('Shanghai business date follows local midnight independently of UTC date', () => {
  assert.equal(shanghaiBusinessDate(new Date('2026-10-20T16:30:00Z')), '2026-10-21')
  assert.equal(shanghaiBusinessDate(new Date('2026-10-20T15:30:00Z')), '2026-10-20')
})
