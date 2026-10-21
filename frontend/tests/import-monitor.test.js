import test from 'node:test'
import assert from 'node:assert/strict'
import { createImportMonitor } from '../src/imports.js'

const response = body => ({ ok: true, status: 200, json: async () => body })
const metadata = id => ({ datasetId: id, status: 'READY', datasetType: 'FILE_DAILY', datasetSource: 'REAL', sourceUnit: 'B', appIds: ['10350'], dateRange: { start: '2026-01-05', end: '2026-10-13' }, appDateRanges: { '10350': { start: '2026-01-05', end: '2026-10-13' } }, rowCount: 4478, completeness: 'UNKNOWN' })
const quality = { totalRows: 4478, validRows: 4478, encoding: 'gb18030', missingDateCount: 1, warnings: ['未确认完整性'] }

test('receipt is followed by GET polling, metadata and a separate quality request even when task quality is null', async () => {
  const paths = []
  const monitor = createImportMonitor({ pollIntervalMs: 1, fetchService: async (path, options) => {
    paths.push(path)
    assert.notEqual(options?.method, 'POST')
    if (path === '/data-api/imports/imp_one') return response({ importTaskId: 'imp_one', status: paths.length === 1 ? 'RUNNING' : 'SUCCEEDED', datasetId: paths.length === 1 ? null : 'ds_one', quality: null })
    if (path === '/data-api/datasets/ds_one') return response(metadata('ds_one'))
    if (path.endsWith('/quality')) return response(quality)
    return response({ page: 1, pageSize: 20, total: 1, items: [metadata('ds_one')] })
  } })
  await monitor.queryTask('imp_one')
  assert.equal(monitor.state.phase, 'SUCCEEDED')
  assert.equal(monitor.state.dataset.datasetId, 'ds_one')
  assert.equal(monitor.state.quality.encoding, 'gb18030')
  assert.ok(paths.includes('/data-api/datasets/ds_one/quality'))
  monitor.dispose()
})

test('failed import retains the real error and available report without publishing a dataset', async () => {
  const monitor = createImportMonitor({ fetchService: async () => response({ importTaskId: 'imp_bad', status: 'FAILED', datasetId: null, error: { code: 'INVALID_CSV', message: '坏行' }, quality: { invalidRows: 1, warnings: [] } }) })
  await monitor.queryTask('imp_bad')
  assert.equal(monitor.state.phase, 'FAILED')
  assert.equal(monitor.state.dataset, null)
  assert.equal(monitor.state.quality.invalidRows, 1)
  assert.match(monitor.state.error, /INVALID_CSV/)
  monitor.dispose()
})

test('dependency failure pauses the page, preserves task id and resumes with GET of the same task', async () => {
  let failing = true
  const paths = []
  const monitor = createImportMonitor({ fetchService: async path => {
    paths.push(path)
    if (failing) throw new TypeError('offline')
    return response({ importTaskId: 'imp_one', status: 'FAILED', datasetId: null, error: { code: 'INVALID_CSV', message: '坏行' }, quality: null })
  } })
  await monitor.queryTask('imp_one')
  assert.equal(monitor.state.phase, 'QUERY_ERROR')
  assert.equal(monitor.state.importTaskId, 'imp_one')
  failing = false
  await monitor.resume()
  assert.deepEqual(paths, ['/data-api/imports/imp_one', '/data-api/imports/imp_one'])
  assert.equal(monitor.state.phase, 'FAILED')
  monitor.dispose()
})

test('late task response cannot overwrite a newer query or make a malformed task successful', async () => {
  let resolveOld
  const monitor = createImportMonitor({ fetchService: async path => path.endsWith('imp_old')
    ? new Promise(resolve => { resolveOld = resolve })
    : response({ importTaskId: 'wrong', status: 'SUCCEEDED', datasetId: 'ds_fake' }) })
  const old = monitor.queryTask('imp_old')
  await monitor.queryTask('imp_new')
  resolveOld(response({ importTaskId: 'imp_old', status: 'RUNNING', datasetId: null }))
  await old
  assert.equal(monitor.state.importTaskId, 'imp_new')
  assert.equal(monitor.state.phase, 'QUERY_ERROR')
  assert.equal(monitor.state.dataset, null)
  monitor.dispose()
})

test('selecting an existing dataset reloads metadata and report; invalid READY metadata is rejected', async () => {
  const monitor = createImportMonitor({ fetchService: async path => response(path.endsWith('/quality') ? quality : path.endsWith('ds_bad') ? { ...metadata('ds_bad'), status: 'FAILED' } : metadata('ds_one')) })
  await monitor.selectDataset('ds_one')
  assert.equal(monitor.state.selectedDataset.datasetId, 'ds_one')
  assert.equal(monitor.state.selectedQuality.validRows, 4478)
  await monitor.selectDataset('ds_bad')
  assert.equal(monitor.state.selectedDataset, null)
  assert.match(monitor.state.selectionError, /READY/)
  monitor.dispose()
})

test('clearing the dataset selector also clears its metadata and report without querying an empty id', async () => {
  const paths = []
  const monitor = createImportMonitor({ fetchService: async path => {
    paths.push(path)
    return response(path.endsWith('/quality') ? quality : metadata('ds_one'))
  } })
  await monitor.selectDataset('ds_one')
  await monitor.selectDataset('')
  assert.equal(monitor.state.selectedDataset, null)
  assert.equal(monitor.state.selectedQuality, null)
  assert.equal(monitor.state.selectionBusy, false)
  assert.equal(paths.length, 2)
  monitor.dispose()
})

test('page waiting limit pauses polling without marking the backend task failed', async () => {
  const monitor = createImportMonitor({ maxWaitMs: 5, pollIntervalMs: 1, fetchService: async () => response({ importTaskId: 'imp_one', status: 'RUNNING', datasetId: null }) })
  await monitor.queryTask('imp_one')
  assert.equal(monitor.state.phase, 'PAUSED')
  assert.equal(monitor.state.task.status, 'RUNNING')
  monitor.dispose()
})

test('a slow dataset list cannot turn an already validated successful import back into paused', async () => {
  let releaseList
  const monitor = createImportMonitor({ maxWaitMs: 10, fetchService: async path => {
    if (path.includes('?page=')) return new Promise(resolve => { releaseList = resolve })
    if (path.includes('/imports/')) return response({ importTaskId: 'imp_one', status: 'SUCCEEDED', datasetId: 'ds_one', quality: null })
    return response(path.endsWith('/quality') ? quality : metadata('ds_one'))
  } })
  const query = monitor.queryTask('imp_one')
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.equal(monitor.state.phase, 'SUCCEEDED')
  assert.equal(monitor.state.busy, false)
  releaseList(response({ page: 1, pageSize: 20, total: 1, items: [metadata('ds_one')] }))
  await query
  monitor.dispose()
})
