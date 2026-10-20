import test from 'node:test'
import assert from 'node:assert/strict'

import { buildTop50Query, fetchTop50, formatBytes, formatScore } from '../src/top50.js'

test('builds a backend query containing every active filter', () => {
  const query = buildTop50Query({
    startDate: '2026-10-18',
    endDate: '2026-10-20',
    appId: '10066',
    granularity: 'DAILY',
    modelVersion: 'last_day_v1',
    compressionStatus: 'PENDING'
  }, 2, 10)

  assert.equal(
    query,
    'startDate=2026-10-18&endDate=2026-10-20&appId=10066&granularity=DAILY&modelVersion=last_day_v1&compressionStatus=PENDING&page=2&pageSize=10'
  )
})

test('omits inactive filters instead of sending empty values', () => {
  const query = buildTop50Query({
    startDate: '2026-10-20',
    endDate: '2026-10-20',
    appId: '',
    granularity: '',
    modelVersion: '',
    compressionStatus: ''
  }, 1, 10)

  assert.equal(query, 'startDate=2026-10-20&endDate=2026-10-20&page=1&pageSize=10')
})

test('formats byte strings using business-friendly binary units', () => {
  assert.equal(formatBytes('0'), '0 B')
  assert.equal(formatBytes('1536'), '1.5 KB')
  assert.equal(formatBytes('5368709120'), '5 GB')
})

test('fractional decimal byte strings stay in the B display unit', () => {
  assert.equal(formatBytes('0.5'), '0.5 B')
  assert.equal(formatBytes('0.01'), '0.0 B')
})

test('unknown traffic and scores do not appear as real zero values', () => {
  for (const value of [null, undefined, '', ' ']) {
    assert.equal(formatBytes(value), '—')
    assert.equal(formatScore(value), '—')
  }
  assert.equal(formatBytes('0'), '0 B')
  assert.equal(formatScore(0), '0.000')
})

test('surfaces the backend error message when a query fails', async () => {
  const fetchService = async () => ({
    ok: false,
    status: 400,
    json: async () => ({ message: 'endDate 不能早于 startDate' })
  })

  await assert.rejects(
    fetchTop50({ startDate: '2026-10-20', endDate: '2026-10-18' }, 1, 10, fetchService),
    /endDate 不能早于 startDate/
  )
})
