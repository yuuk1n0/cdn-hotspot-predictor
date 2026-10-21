import test from 'node:test'
import assert from 'node:assert/strict'
import { validatePredictionTask, validatePredictionResult } from '../src/prediction-results.js'

const metadata = { datasetId: 'ds_one', appId: '10350', cutoffDate: '2026-10-06', horizonDays: 1, modelVersion: 'baseline-v1' }
const task = (extra = {}) => ({
  ...metadata, taskId: 'task_one', status: 'SUCCEEDED', runId: 'run_one',
  datasetSource: 'REAL', predictionSource: 'MODEL', error: null, ...extra
})
const run = (extra = {}) => ({
  ...metadata, runId: 'run_one', predictionStartDate: '2026-10-07', predictionEndDate: '2026-10-07',
  datasetSource: 'REAL', predictionSource: 'MODEL', candidateCount: 1, top50Count: 1,
  top50PredictedBytes: '10000000000000001', items: [{ rank: 1, fileKey: 'a', predictedBytes: '10000000000000001' }], ...extra
})

test('explicit result modes must match the original prediction source on tasks and runs', () => {
  for (const [predictionSource, resultMode] of [['MOCK', 'MOCK'], ['MODEL', 'REAL']]) {
    const validTask = task({ predictionSource, resultMode })
    const validRun = run({ predictionSource, resultMode })
    assert.equal(validatePredictionTask(validTask, 'task_one'), validTask)
    assert.equal(validatePredictionResult(validRun, 'run_one', validTask), validRun)
    for (const invalid of [null, undefined, '', 'MODEL', 'unknown', 1, {}, predictionSource === 'MOCK' ? 'REAL' : 'MOCK']) {
      assert.throws(() => validatePredictionTask(task({ predictionSource, resultMode: invalid }), 'task_one'), /契约/, `task: ${String(invalid)}`)
      assert.throws(() => validatePredictionResult(run({ predictionSource, resultMode: invalid }), 'run_one'), /契约/, `run: ${String(invalid)}`)
    }
  }
})

test('non-successful tasks permit an unresolved null mode only while their source is unknown', () => {
  for (const status of ['PENDING', 'RUNNING', 'FAILED']) {
    const unresolved = task({ status, runId: null, predictionSource: null, resultMode: null,
      error: status === 'FAILED' ? { code: 'FAILURE', message: '尚未产生结果' } : null })
    assert.equal(validatePredictionTask(unresolved, 'task_one'), unresolved)
    for (const resultMode of ['MOCK', 'REAL', 'UNKNOWN', undefined, 0]) {
      assert.throws(() => validatePredictionTask({ ...unresolved, resultMode }, 'task_one'), /契约/)
    }
    assert.throws(() => validatePredictionTask({ ...unresolved, predictionSource: 'MODEL' }, 'task_one'), /契约/)
  }
})

test('historical immutable tasks and runs without resultMode remain unchanged', () => {
  for (const predictionSource of ['MOCK', 'MODEL']) {
    const legacyTask = Object.freeze(task({ predictionSource }))
    const legacyRun = Object.freeze(run({ predictionSource }))
    assert.equal(validatePredictionTask(legacyTask, 'task_one'), legacyTask)
    assert.equal(validatePredictionResult(legacyRun, 'run_one', legacyTask), legacyRun)
    assert.equal(Object.hasOwn(legacyTask, 'resultMode'), false)
    assert.equal(Object.hasOwn(legacyRun, 'resultMode'), false)
  }
  assert.equal(validatePredictionTask(task({ status: 'PENDING', runId: null, predictionSource: null }), 'task_one').predictionSource, null)
})

test('a valid REAL mode does not relax byte-string, rank or source checks', () => {
  for (const changed of [
    { top50PredictedBytes: 123 }, { datasetSource: null }, { predictionSource: 'REAL' },
    { items: [{ rank: 2, fileKey: 'a', predictedBytes: '1' }] },
    { items: [{ rank: 1, fileKey: 'a', predictedBytes: 1 }] }
  ]) assert.throws(() => validatePredictionResult(run({ resultMode: 'REAL', ...changed }), 'run_one'), /契约/)
})
