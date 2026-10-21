import test from 'node:test'
import assert from 'node:assert/strict'
import { createPredictionSubmit } from '../src/predictions.js'

const body = { datasetId: 'ds_one', appId: '10350', cutoffDate: '2026-10-06', horizonDays: 1, modelVersion: 'contract-demo-v1' }
const dataset = { datasetId: 'ds_one', appId: '10350', datasetSource: 'REAL', appDateRanges: { '10350': { start: '2026-01-05', end: '2026-10-13' } } }
const response = (value, status = 202) => ({ status, ok: status < 300, json: async () => value })

test('prediction submission posts exactly five fields to Java and retains the accepted task for polling', async () => {
  let received
  const submit = createPredictionSubmit({ fetchService: async (path, options) => {
    received = { path, options }
    return response({ taskId: 'task_one', status: 'PENDING' })
  } })
  const id = await submit.submit({ ...body, ignored: 'must not reach API' }, dataset)
  assert.equal(id, 'task_one')
  assert.equal(received.path, '/api/predictions')
  assert.deepEqual(JSON.parse(received.options.body), body)
  assert.ok(received.options.headers['Idempotency-Key'])
  assert.equal(submit.state.taskId, 'task_one')
})

test('double clicks and a lost response retain the original frozen request and key on explicit retry', async () => {
  let release
  const calls = []
  const submit = createPredictionSubmit({ fetchService: async (_, options) => {
    calls.push(options)
    if (calls.length === 1) { await new Promise(resolve => { release = resolve }); throw new TypeError('lost response') }
    return response({ taskId: 'task_one', status: 'RUNNING' })
  } })
  const first = submit.submit(body, dataset)
  await submit.submit(body, dataset)
  assert.equal(calls.length, 1)
  release()
  await first
  assert.match(submit.state.error, /重试/)
  await submit.retry()
  assert.equal(calls[0].body, calls[1].body)
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key'])
  await submit.submit(body, dataset)
  assert.equal(calls.length, 2)
  await submit.submit({ ...body, horizonDays: 7 }, dataset)
  assert.notEqual(calls[1].headers['Idempotency-Key'], calls[2].headers['Idempotency-Key'])
})

test('invalid dataset, App, date or span is blocked before POST', async () => {
  const submit = createPredictionSubmit({ fetchService: async () => assert.fail('invalid submit must not POST') })
  for (const [params, context] of [
    [body, null], [{ ...body, appId: 'other' }, dataset], [{ ...body, cutoffDate: '2026-02-30' }, dataset],
    [{ ...body, cutoffDate: '2026-10-14' }, dataset], [{ ...body, horizonDays: 2 }, dataset], [{ ...body, modelVersion: '' }, dataset]
  ]) {
    await submit.submit(params, context)
    assert.ok(submit.state.error)
    assert.equal(submit.state.taskId, '')
  }
})

test('restore never POSTs automatically; explicit retry uses the saved original request', async () => {
  const stored = new Map()
  const storage = { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) }
  let originalKey
  const first = createPredictionSubmit({ storage, fetchService: async (_, options) => { originalKey = options.headers['Idempotency-Key']; throw new TypeError('offline') } })
  await first.submit(body, dataset)
  let calls = 0
  const second = createPredictionSubmit({ storage, fetchService: async (_, options) => { calls++; assert.equal(options.headers['Idempotency-Key'], originalKey); assert.deepEqual(JSON.parse(options.body), body); return response({ taskId: 'task_one', status: 'PENDING' }) } })
  second.restore()
  assert.equal(calls, 0)
  assert.deepEqual(JSON.parse(JSON.stringify(second.state.request)), body)
  await second.retry()
  assert.equal(calls, 1)
})

test('malformed success, 409 and non-202 success never fabricate a task id', async () => {
  for (const [value, status] of [[{}, 202], [{ taskId: 12, status: 'PENDING' }, 202], [{ taskId: 'task_one', status: 'PENDING' }, 200], [{ error: { code: 'IDEMPOTENCY_CONFLICT', message: 'same key changed body' } }, 409]]) {
    const submit = createPredictionSubmit({ fetchService: async () => response(value, status) })
    await submit.submit(body, dataset)
    assert.equal(submit.state.taskId, '')
    assert.ok(submit.state.error)
  }
})
