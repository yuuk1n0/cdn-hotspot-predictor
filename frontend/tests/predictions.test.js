import test from 'node:test'
import assert from 'node:assert/strict'

import { createPredictionLookup, resultRows, validatePredictionResult } from '../src/predictions.js'

const reply = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const metadata = { datasetId: 'ds_one', appId: '10350', cutoffDate: '2026-10-06', horizonDays: 7, modelVersion: 'contract-demo-v1' }
const task = (status, extra = {}) => ({
  ...metadata, taskId: 'task_one', status, stage: 'COMPUTING', datasetSource: 'REAL', predictionSource: null,
  runId: status === 'SUCCEEDED' ? 'run_one' : null, error: null, lastDependencyError: null, ...extra
})
const run = (extra = {}) => ({
  ...metadata, runId: 'run_one', predictionStartDate: '2026-10-07', predictionEndDate: '2026-10-13',
  datasetSource: 'REAL', predictionSource: 'MOCK', candidateCount: 2, top50Count: 2, top50PredictedBytes: '10000000000000002',
  items: [{ rank: 1, fileKey: 'https://host/a', predictedBytes: '10000000000000001', score: 0.9, reason: '模拟结果，仅验证链路' },
    { rank: 2, fileKey: 'host/a', predictedBytes: '1', score: null, reason: null }],
  hotspotTrafficRatio: { value: null, denominatorBytes: null, reasonCode: 'MOCK_RESULT_AND_NO_MATCHING_TOTAL' }, ...extra
})

test('polls only Java GET routes and fetches a run only after business SUCCEEDED', async () => {
  const calls = []
  const states = [task('PENDING'), task('RUNNING'), task('SUCCEEDED')]
  const flow = createPredictionLookup({ pollIntervalMs: 0, fetchService: async (path, options) => {
    calls.push({ path, options })
    if (path === '/api/tasks/task_one') return reply(states.shift())
    if (path === '/api/predictions/run_one') return reply(run())
    assert.fail(`unexpected route: ${path}`)
  } })
  await flow.queryTask('task_one')
  assert.equal(flow.state.phase, 'READY')
  assert.equal(flow.state.result.runId, 'run_one')
  assert.deepEqual(calls.map(c => c.path), ['/api/tasks/task_one', '/api/tasks/task_one', '/api/tasks/task_one', '/api/predictions/run_one'])
  assert.ok(calls.every(c => !c.options?.method || c.options.method === 'GET'))
  assert.equal(flow.state.result.items[0].predictedBytes, '10000000000000001')
  assert.equal(flow.state.result.top50PredictedBytes, '10000000000000002')
})

test('FAILED shows the business reason and never asks for a result or prototype fallback', async () => {
  const calls = []
  const flow = createPredictionLookup({ fetchService: async path => {
    calls.push(path)
    return reply(task('FAILED', { error: { code: 'TASK_TIMEOUT', message: '超过任务总时限' } }))
  } })
  await flow.queryTask('task_one')
  assert.equal(flow.state.phase, 'TASK_FAILED')
  assert.match(flow.state.error, /TASK_TIMEOUT.*超过任务总时限/)
  assert.equal(flow.state.result, null)
  assert.deepEqual(calls, ['/api/tasks/task_one'])
})

test('a 404 run endpoint is unavailable, not a successful empty or Fake ranking', async () => {
  const flow = createPredictionLookup({ fetchService: async path => {
    assert.equal(path, '/api/predictions/run_one')
    return reply({}, 404)
  } })
  await flow.queryRun('run_one')
  assert.equal(flow.state.phase, 'QUERY_ERROR')
  assert.equal(flow.state.result, null)
  assert.match(flow.state.error, /404/)
})

test('an unavailable Java connection surfaces a query error without fabricating task failure', async () => {
  const flow = createPredictionLookup({ fetchService: async () => { throw new TypeError('Failed to fetch') } })
  await flow.queryTask('task_one')
  assert.equal(flow.state.phase, 'QUERY_ERROR')
  assert.equal(flow.state.task, null)
  assert.equal(flow.state.result, null)
  assert.match(flow.state.error, /无法连接 Java/)
})

test('query failure resumes the same task without inventing a FAILED state or creating a task', async () => {
  let calls = 0
  const flow = createPredictionLookup({ pollIntervalMs: 0, fetchService: async (path, options) => {
    assert.equal(path, '/api/tasks/task_one')
    assert.notEqual(options?.method, 'POST')
    calls++
    if (calls === 1) return reply(task('RUNNING', { lastDependencyError: { code: 'PYTHON_UNAVAILABLE', message: '暂时断连' } }))
    if (calls === 2) return reply({ error: { code: 'TASK_STORAGE_UNAVAILABLE', message: '存储暂不可用' } }, 503)
    return reply(task('FAILED', { error: { code: 'APP_MISMATCH', message: 'App 不匹配' } }))
  } })
  await flow.queryTask('task_one')
  assert.equal(flow.state.task.status, 'RUNNING')
  assert.equal(flow.state.phase, 'QUERY_ERROR')
  assert.match(flow.state.error, /存储暂不可用/)
  await flow.resume()
  assert.equal(flow.state.task.status, 'FAILED')
  assert.equal(calls, 3)
})

test('client waiting limit pauses queries without declaring the Java task failed', async () => {
  let now = 0
  const flow = createPredictionLookup({ pollIntervalMs: 0, maxWaitMs: 1000, now: () => now, fetchService: async () => {
    now += 1000
    return reply(task('RUNNING'))
  } })
  await flow.queryTask('task_one')
  assert.equal(flow.state.phase, 'PAUSED')
  assert.equal(flow.state.task.status, 'RUNNING')
  assert.equal(flow.state.error, '')
  assert.match(flow.state.notice, /暂停/)
})

test('the overall waiting limit also releases an unresponsive HTTP request', async () => {
  const flow = createPredictionLookup({ maxWaitMs: 10, fetchService: async (path, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  }) })
  const querying = flow.queryTask('task_one')
  await new Promise(resolve => setTimeout(resolve, 30))
  const phase = flow.state.phase
  const busy = flow.state.busy
  // Always release the pending test request, including when the regression is present.
  flow.pause()
  await querying
  assert.equal(phase, 'PAUSED')
  assert.equal(busy, false)
  assert.equal(flow.state.task, null)
  assert.equal(flow.state.error, '')
})

test('a 100-second backend wait retains the same polling contract', async () => {
  let now = 0
  let count = 0
  const flow = createPredictionLookup({ pollIntervalMs: 0, now: () => now, fetchService: async path => {
    if (path.endsWith('/run_one')) return reply(run())
    count++
    if (count === 1) { now = 100000; return reply(task('RUNNING')) }
    return reply(task('SUCCEEDED'))
  } })
  await flow.queryTask('task_one')
  assert.equal(count, 2)
  assert.equal(flow.state.phase, 'READY')
})

test('manual pause aborts polling without cancelling or mutating the business task', async () => {
  let calls = 0
  const flow = createPredictionLookup({ pollIntervalMs: 60000, fetchService: async () => { calls++; return reply(task('RUNNING')) } })
  const querying = flow.queryTask('task_one')
  await new Promise(resolve => setImmediate(resolve))
  flow.pause()
  await querying
  assert.equal(flow.state.phase, 'PAUSED')
  assert.equal(flow.state.task.status, 'RUNNING')
  assert.equal(flow.state.busy, false)
  assert.equal(calls, 1)
})

test('stale replies from a replaced query cannot replace the current result', async () => {
  let release
  const flow = createPredictionLookup({ fetchService: async path => {
    if (path === '/api/tasks/task_old') return new Promise(resolve => { release = resolve })
    return reply(run())
  } })
  const old = flow.queryTask('task_old')
  await flow.queryRun('run_one')
  release(reply(task('RUNNING', { taskId: 'task_old' })))
  await old
  assert.equal(flow.state.kind, 'run')
  assert.equal(flow.state.phase, 'READY')
  assert.equal(flow.state.result.runId, 'run_one')
})

test('a later failed lookup preserves but clearly separates the last successful result', async () => {
  const flow = createPredictionLookup({ fetchService: async path => path.endsWith('/run_one') ? reply(run()) : reply({}, 404) })
  await flow.queryRun('run_one')
  await flow.queryRun('run_missing')
  assert.equal(flow.state.result.runId, 'run_one')
  assert.equal(flow.state.resultIsCurrent, false)
  assert.equal(flow.state.phase, 'QUERY_ERROR')
})

test('task and result identity, window and source mismatches never publish a result', async () => {
  for (const changed of [
    { runId: 'other' }, { datasetId: 'other' }, { appId: '10066' }, { horizonDays: 1 },
    { modelVersion: 'other' }, { cutoffDate: '2026-10-05' }, { datasetSource: 'FAKE' },
    { predictionStartDate: '2026-10-06' }, { predictionEndDate: '2026-10-14' }
  ]) {
    const flow = createPredictionLookup({ fetchService: async path => reply(path.includes('/tasks/') ? task('SUCCEEDED') : run(changed)) })
    await flow.queryTask('task_one')
    assert.equal(flow.state.phase, 'QUERY_ERROR', JSON.stringify(changed))
    assert.equal(flow.state.result, null)
  }
})

test('invalid states or SUCCEEDED without runId never fetch a ranking', async () => {
  for (const bad of [task('SUCCEEDED', { runId: null }), task('OTHER'), task('RUNNING', { runId: 'too-early' }), task('PENDING', { taskId: 123 })]) {
    let calls = 0
    const flow = createPredictionLookup({ fetchService: async () => { calls++; return reply(bad) } })
    await flow.queryTask('task_one')
    assert.equal(flow.state.phase, 'QUERY_ERROR')
    assert.equal(calls, 1)
  }
})

test('malformed result numbers, counts, ranks and sources are not repaired into valid results', () => {
  for (const bad of [
    run({ datasetId: 123 }), run({ datasetSource: null }), run({ predictionSource: 'unknown' }),
    run({ top50PredictedBytes: 123 }), run({ top50Count: 50 }), run({ candidateCount: 1 }),
    run({ items: [{ rank: 1, fileKey: 'a', predictedBytes: -1 }] }),
    run({ top50Count: 1, items: [{ rank: 2, fileKey: 'a', predictedBytes: '1' }] }),
    run({ top50Count: 1, items: [{ rank: 1, fileKey: 'a', predictedBytes: '1e3' }] }),
    run({ items: [{ rank: 1, fileKey: 'a', predictedBytes: '2' }, { rank: 2, fileKey: 'a', predictedBytes: '1' }] })
  ]) assert.throws(() => validatePredictionResult(bad, 'run_one'), /契约/)
})

test('empty results stay empty and weekly bytes are never multiplied or fabricated', () => {
  const empty = run({ candidateCount: 0, top50Count: 0, top50PredictedBytes: '0', items: [] })
  assert.equal(validatePredictionResult(empty, 'run_one'), empty)
  assert.deepEqual(resultRows(empty), [])
  const rows = resultRows(run())
  assert.equal(rows.length, 2)
  assert.equal(rows[0].predictedBytes, '10000000000000001')
  assert.equal(rows[0].fileSizeBytes, null)
  assert.equal(rows[0].recentActualBytes, null)
  assert.equal(rows[0].compressionStatus, null)
  assert.equal(rows[1].fileKey, 'host/a')
  assert.equal(rows[0].predictionEndDate, '2026-10-13')
})

test('refresh restores a lookup id and re-queries Java instead of storing fabricated results', async () => {
  const stored = new Map()
  const storage = { getItem: k => stored.get(k), setItem: (k, v) => stored.set(k, v) }
  const first = createPredictionLookup({ storage, fetchService: async () => reply(run()) })
  await first.queryRun('run_one')
  assert.deepEqual(JSON.parse(stored.get('cdn.prediction.lookup')), { kind: 'run', id: 'run_one' })
  const restored = createPredictionLookup({ storage, fetchService: async path => {
    assert.equal(path, '/api/predictions/run_one')
    return reply(run())
  } })
  await restored.restore()
  assert.equal(restored.state.phase, 'READY')
})

test('invalid input makes no request, while identifiers are encoded as path segments', async () => {
  const calls = []
  const flow = createPredictionLookup({ fetchService: async path => { calls.push(path); return reply({}, 404) } })
  await flow.queryTask('')
  assert.equal(calls.length, 0)
  await flow.queryRun('run/a?b')
  assert.equal(calls[0], '/api/predictions/run%2Fa%3Fb')
})
