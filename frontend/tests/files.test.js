import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createFileAccess, fetchFileMetadata } from '../src/files.js'

const run = { runId: 'run_one', appId: '10066', datasetSource: 'REAL' }
const response = url => ({ runId: 'run_one', appId: '10066', fileKey: 'file', purpose: 'PREVIEW', url, dataSource: 'CSTORE' })

test('each operation requests a fresh URL without browser caching, closing clears it', async () => {
  const calls = []
  const session = createFileAccess({ fetchService: async (path, options) => {
    calls.push({ path, options }); return { ok: true, json: async () => response(`https://media.test/${calls.length}`) }
  } })
  session.select(run, 'file')
  await session.request('PREVIEW')
  assert.equal(session.state.url, 'https://media.test/1')
  await session.request('PREVIEW')
  assert.equal(session.state.url, 'https://media.test/2')
  assert.equal(calls.length, 2)
  assert.equal(calls[0].options.cache, 'no-store')
  assert.deepEqual(JSON.parse(calls[0].options.body), { runId: 'run_one', fileKey: 'file', purpose: 'PREVIEW' })
  session.clear()
  assert.equal(session.state.url, '')
})

test('late responses cannot revive a closed file or overwrite the next file', async () => {
  let finish
  const session = createFileAccess({ fetchService: () => new Promise(resolve => { finish = resolve }) })
  session.select(run, 'file')
  const pending = session.request('PREVIEW')
  session.select(run, 'other')
  finish({ ok: true, json: async () => response('https://media.test/old') })
  await pending
  assert.equal(session.state.url, '')
  assert.equal(session.state.busy, false)
})

test('fake contexts, mismatched responses, unsafe URLs and upstream failures cannot expose links', async () => {
  let calls = 0
  let payload = response('javascript:alert(1)')
  const session = createFileAccess({ fetchService: async () => { calls++; return { ok: true, json: async () => payload } } })
  session.select({ ...run, datasetSource: 'FAKE' }, 'file')
  await session.request('PREVIEW')
  assert.equal(calls, 0)
  session.select(run, 'file')
  await session.request('PREVIEW')
  assert.equal(session.state.url, '')
  assert.ok(session.state.error)
  payload = { ...response('https://media.test/file'), appId: 'wrong' }
  await session.request('PREVIEW')
  assert.equal(session.state.url, '')
})

test('metadata retains precise byte strings and per-file failures, rejects numeric precision loss', async () => {
  let item = { fileKey: 'file', runId: run.runId, appId: run.appId, status: 'READY', fileSizeBytes: '9007199254740993', dataSource: 'CSTORE', contentType: 'image/png' }
  const fetchService = async (_path, options) => {
    assert.equal(options.cache, 'no-store')
    return { ok: true, json: async () => ({ runId: run.runId, appId: run.appId, items: [item] }) }
  }
  assert.equal((await fetchFileMetadata(run, ['file'], fetchService))[0].fileSizeBytes, '9007199254740993')
  item = { ...item, status: 'UNAVAILABLE', fileSizeBytes: null, errorCode: 'FILE_METADATA_UNAVAILABLE' }
  assert.equal((await fetchFileMetadata(run, ['file'], fetchService))[0].fileSizeBytes, null)
  item = { ...item, status: 'READY', fileSizeBytes: 9007199254740993 }
  await assert.rejects(fetchFileMetadata(run, ['file'], fetchService))
})
