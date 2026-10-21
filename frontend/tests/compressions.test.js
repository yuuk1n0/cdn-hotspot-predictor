import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createCompression, listCompressions, updateCompression } from '../src/compressions.js'

const run = { runId: 'run_real', appId: '10350', datasetSource: 'REAL', predictionSource: 'MODEL' }

test('registration uses original run and file identity, idempotency header, and explicit evidence source', async () => {
  const calls = []
  const request = async (url, options) => {
    calls.push([url, options])
    return { ok: true, status: 201, json: async () => ({ id: 'cmp_one', runId: 'run_real', appId: '10350', fileKey: 'a.mp4', evidenceSource: 'TEST', status: 'PROCESSING', version: 1 }) }
  }
  const result = await createCompression(run, 'a.mp4', { status: 'PROCESSING', evidenceSource: 'TEST', handler: 'operator', remark: '' }, 'request-one', request)
  assert.equal(result.id, 'cmp_one')
  assert.equal(calls[0][0], '/api/compressions')
  assert.equal(calls[0][1].headers['Idempotency-Key'], 'request-one')
  assert.deepEqual(JSON.parse(calls[0][1].body), { runId: 'run_real', appId: '10350', fileKey: 'a.mp4', status: 'PROCESSING', evidenceSource: 'TEST', handler: 'operator', remark: '' })
})

test('list and update validate response identity; failed calls do not invent records', async () => {
  let count = 0
  const request = async (url, options) => {
    count++
    if (count === 1) {
      assert.match(url, /^\/api\/compressions\?appId=10350&runId=run_real&fileKey=a.mp4/)
      return { ok: true, status: 200, json: async () => ({ total: 1, items: [{ id: 'cmp_one', runId: 'run_real', appId: 'other', fileKey: 'a.mp4', evidenceSource: 'TEST' }] }) }
    }
    assert.equal(url, '/api/compressions/cmp_one')
    assert.equal(options.method, 'PUT')
    return { ok: false, status: 503, json: async () => ({ error: { code: 'COMPRESSION_STORAGE_UNAVAILABLE', message: '存储不可用' } }) }
  }
  await assert.rejects(() => listCompressions(run, 'a.mp4', request), /不匹配/)
  await assert.rejects(() => updateCompression(run, 'a.mp4', { id: 'cmp_one', version: 1, runId: 'run_real', appId: '10350', fileKey: 'a.mp4', evidenceSource: 'TEST' }, { remark: '更新' }, request), /存储不可用/)
})
