import test from 'node:test'
import assert from 'node:assert/strict'

import { createCsvImport, maxFileBytes } from '../src/imports.js'

const file = () => new File(['文件标识,日期,当日流量（B）,AppID\na,2026/1/5,1E3,10350'], '10350.csv', { type: 'text/csv' })
const response = (body, status = 202) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const accepted = { importTaskId: 'imp_1', status: 'PENDING' }

function sizedCsv(size) {
  const selected = new File(['x'], 'sized.csv', { type: 'text/csv' })
  Object.defineProperty(selected, 'size', { value: size })
  return selected
}

function prepared(fetchService, extra = {}) {
  const flow = createCsvImport({ fetchService, ...extra })
  flow.selectFile(file())
  flow.state.options.sourceUnit = 'B'
  return flow
}

test('uploads multipart only to Python and treats 202 as accepted, without status or dataset queries', async () => {
  const calls = []
  const flow = prepared(async (path, options) => {
    calls.push({ path, options })
    return response(accepted)
  })
  await flow.submit()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].path, '/data-api/imports')
  assert.equal(flow.state.phase, 'ACCEPTED')
  assert.equal(flow.state.importTaskId, 'imp_1')
  assert.equal('datasetId' in flow.state, false)
  assert.equal(flow.state.busy, false)
  const { options } = calls[0]
  assert.equal(options.method, 'POST')
  assert.ok(options.headers['Idempotency-Key'])
  assert.equal(options.headers['Content-Type'], undefined)
  assert.equal(options.body.get('file').name, '10350.csv')
  assert.match(await options.body.get('file').text(), /1E3/)
  assert.equal(options.body.get('datasetType'), 'FILE_DAILY')
  assert.equal(options.body.get('datasetSource'), 'REAL')
  assert.equal(options.body.get('sourceUnit'), 'B')
  assert.equal(options.body.has('appId'), false)
  assert.deepEqual(JSON.parse(options.body.get('mapping')), {
    fileKey: '文件标识', date: '日期', trafficBytes: '当日流量（B）', appId: 'AppID'
  })
})

test('blocks missing, empty, non-CSV and oversized files before sending', async () => {
  let calls = 0
  const flow = prepared(async () => { calls++; assert.fail('must not send') })
  for (const [selected, message] of [
    [null, /CSV/],
    [new File([], 'empty.csv'), /为空/],
    [new File(['bad'], 'bad.txt'), /CSV/],
    [sizedCsv(200 * 1024 * 1024 + 1), /200 MiB/]
  ]) {
    flow.selectFile(selected)
    await flow.submit()
    assert.equal(flow.state.phase, 'FAILED')
    assert.match(flow.state.error, message)
  }
  assert.equal(calls, 0)
})

test('accepts a CSV at exactly 200 MiB and sends one request', async () => {
  let calls = 0
  const flow = prepared(async () => { calls++; return response(accepted) })
  flow.selectFile(sizedCsv(200 * 1024 * 1024))
  await flow.submit()
  assert.equal(maxFileBytes, 200 * 1024 * 1024)
  assert.equal(calls, 1)
  assert.equal(flow.state.phase, 'ACCEPTED')
})

test('requires a confirmed B unit and sends custom mapping, source and optional App ID unchanged', async () => {
  let calls = 0
  const flow = createCsvImport({ fetchService: async (_, options) => {
    calls++
    assert.equal(options.body.get('datasetSource'), 'FAKE')
    assert.equal(options.body.get('appId'), 'sample-app')
    assert.deepEqual(JSON.parse(options.body.get('mapping')), {
      fileKey: 'url', date: 'day', trafficBytes: 'bytes', appId: ''
    })
    return response(accepted)
  } })
  flow.selectFile(file())
  await flow.submit()
  assert.match(flow.state.error, /单位/)
  assert.equal(calls, 0)
  flow.state.options.sourceUnit = 'B'
  flow.state.options.datasetSource = 'FAKE'
  flow.state.options.mapping = { fileKey: 'url', date: 'day', trafficBytes: 'bytes', appId: '' }
  await flow.submit()
  assert.match(flow.state.error, /App ID/)
  flow.state.options.appId = 'sample-app'
  await flow.submit()
  assert.equal(calls, 1)
  assert.equal(flow.state.phase, 'ACCEPTED')
})

test('double clicks send once and a lost upload response retries the same body and key', async () => {
  const uploads = []
  let release
  const flow = prepared(async (_, options) => {
    uploads.push(options)
    if (uploads.length === 1) {
      await new Promise(resolve => { release = resolve })
      throw new TypeError('network failed')
    }
    return response(accepted)
  })
  const pending = flow.submit()
  assert.equal(flow.state.phase, 'UPLOADING')
  assert.equal(flow.state.busy, true)
  await flow.submit()
  assert.equal(uploads.length, 1)
  release()
  await pending
  assert.equal(flow.state.phase, 'FAILED')
  assert.match(flow.state.error, /Python.*重试/)
  await flow.submit()
  await flow.submit()
  assert.equal(uploads.length, 2)
  assert.equal(uploads[0].headers['Idempotency-Key'], uploads[1].headers['Idempotency-Key'])
  assert.equal(uploads[0].body, uploads[1].body)
  assert.equal(flow.state.phase, 'ACCEPTED')
})

test('a changed request after failure receives a new idempotency key', async () => {
  const keys = []
  const flow = prepared(async (_, options) => {
    keys.push(options.headers['Idempotency-Key'])
    return response({ error: { code: 'UNAVAILABLE', message: '服务暂不可用' } }, 503)
  })
  await flow.submit()
  flow.state.options.datasetSource = 'FAKE'
  await flow.submit()
  assert.notEqual(keys[0], keys[1])
})

test('HTTP errors surface the server code and reason without inventing an importTaskId', async () => {
  for (const [status, body, message] of [
    [409, { error: { code: 'IDEMPOTENCY_CONFLICT', message: '相同幂等键对应不同内容' } }, /IDEMPOTENCY_CONFLICT.*不同内容/],
    [413, { error: { code: 'FILE_TOO_LARGE', message: '文件超过限制' } }, /文件超过限制/],
    [404, {}, /HTTP 404/],
    [502, null, /HTTP 502/]
  ]) {
    const flow = prepared(async () => response(body, status))
    await flow.submit()
    assert.equal(flow.state.phase, 'FAILED')
    assert.equal(flow.state.importTaskId, '')
    assert.match(flow.state.error, message)
  }
})

test('200 responses and malformed 202 responses cannot become accepted', async () => {
  for (const [body, status] of [[accepted, 200], [{}, 202], [{ importTaskId: 123 }, 202], [{ importTaskId: ' ' }, 202]]) {
    const flow = prepared(async () => response(body, status))
    await flow.submit()
    assert.equal(flow.state.phase, 'FAILED')
    assert.equal(flow.state.importTaskId, '')
    assert.match(flow.state.error, /契约/)
  }
})

test('accepted taskId survives reload locally without making requests', async () => {
  const stored = new Map()
  const storage = {
    getItem: key => stored.get(key),
    setItem: (key, value) => stored.set(key, value),
    removeItem: key => stored.delete(key)
  }
  const flow = prepared(async () => response(accepted), { storage })
  await flow.submit()
  const restored = createCsvImport({ storage, fetchService: async () => assert.fail('no status queries in step 1') })
  restored.restore()
  assert.equal(restored.state.importTaskId, 'imp_1')
  assert.equal(restored.state.phase, 'ACCEPTED')
  restored.reset()
  assert.equal(restored.state.importTaskId, '')
  assert.equal(stored.size, 0)
})

test('disabled browser storage does not turn an accepted upload into failure', async () => {
  const flow = prepared(async () => response(accepted), { storage: { setItem: () => { throw new Error('disabled') } } })
  await flow.submit()
  assert.equal(flow.state.phase, 'ACCEPTED')
  assert.equal(flow.state.importTaskId, 'imp_1')
})
