import test from 'node:test'
import assert from 'node:assert/strict'

import { checkService } from '../src/health.js'

test('reports an available service using its returned identity', async () => {
  const fetchService = async () => ({
    ok: true,
    json: async () => ({ service: 'backend', status: 'UP', database: 'UP' })
  })

  const result = await checkService('/api/health', fetchService)

  assert.deepEqual(result, {
    state: 'up',
    detail: 'backend · UP',
    payload: { service: 'backend', status: 'UP', database: 'UP' }
  })
})

test('reports the HTTP status when a service is unavailable', async () => {
  const fetchService = async () => ({ ok: false, status: 503 })

  const result = await checkService('/api/health', fetchService)

  assert.deepEqual(result, {
    state: 'down',
    detail: 'HTTP 503',
    payload: null
  })
})

test('reports a connection error without inventing a service response', async () => {
  const fetchService = async () => {
    throw new Error('connection refused')
  }

  const result = await checkService('/data-api/health', fetchService)

  assert.deepEqual(result, {
    state: 'down',
    detail: 'connection refused',
    payload: null
  })
})
