import { test } from 'node:test'
import assert from 'node:assert/strict'
import { metricPercent, metricsMatchRun, ratioReason, ratioText } from '../src/operations.js'

const run = {
  runId: 'run_real', appId: '10350',
  predictionStartDate: '2026-10-13', predictionEndDate: '2026-10-13'
}
const matchingMetrics = {
  runId: 'run_real', appId: '10350', windowStart: '2026-10-13', windowEnd: '2026-10-13'
}

test('actual top50 percent is recalculated from real bytes instead of trusting a display value', () => {
  const metric = {
    value: 73.6,
    numeratorBytes: '169715876173',
    denominatorBytes: '7888992685759.44',
    numeratorSource: 'MYSQL_FILE_DAILY_OBSERVATION',
    denominatorSource: 'MYSQL_APP_DAILY_TOTAL'
  }
  assert.equal(metricPercent(metric), 2.1513)
  assert.equal(ratioText(metric), '2.15%')
  assert.equal(ratioReason(metric), 'MYSQL_FILE_DAILY_OBSERVATION / MYSQL_APP_DAILY_TOTAL')
})

test('a value without byte evidence is not rendered as an actual percentage', () => {
  const metric = { value: 80.1, numeratorSource: 'unknown', denominatorSource: 'unknown' }
  assert.equal(metricPercent(metric), null)
  assert.equal(ratioText(metric), '不可计算')
  assert.equal(ratioReason(metric), 'METRIC_BYTES_MISSING')
})

test('metrics must belong to the same run, app and prediction window', () => {
  assert.equal(metricsMatchRun(matchingMetrics, run), true)
  for (const mismatch of [
    { ...matchingMetrics, runId: 'run_other' },
    { ...matchingMetrics, appId: '10066' },
    { ...matchingMetrics, windowEnd: '2026-10-14' }
  ]) assert.equal(metricsMatchRun(mismatch, run), false)
})
