import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createMemoryHistory } from 'vue-router'
import {
  HISTORICAL_HOTSPOT_RATIOS,
  PREDICTED_TOP50_ACTUAL_RATIOS,
  createOverviewRatios
} from '../src/overview-ratios.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value))
  }
}

let server
let OverviewView
let createAppRouter

before(async () => {
  server = await createServer({ server: { middlewareMode: true, ws: false, watch: null }, appType: 'custom' })
  OverviewView = (await server.ssrLoadModule('/src/views/OverviewView.vue')).default
  createAppRouter = (await server.ssrLoadModule('/src/router/index.js')).createAppRouter
})

after(async () => {
  await server?.close()
})

test('uses the exact approved ratio pools and selects only from them', () => {
  assert.deepEqual(HISTORICAL_HOTSPOT_RATIOS, [92.1, 93.8, 94.3, 92.5, 88.1, 89.9])
  assert.deepEqual(PREDICTED_TOP50_ACTUAL_RATIOS, [72.1, 75.4, 76.9, 73.9, 80.4, 81.2])

  const ratios = createOverviewRatios({ random: () => 0, storage: null }).forTask('task_one')

  assert.deepEqual(ratios, { historicalHotspot: 92.1, predictedTop50Actual: 72.1 })
})

test('keeps one task stable across calls and recreated instances', () => {
  const storage = memoryStorage()
  let calls = 0
  const first = createOverviewRatios({
    storage,
    random: () => {
      calls++
      return 0
    }
  })
  const original = first.forTask('task_one')

  assert.deepEqual(first.forTask('task_one'), original)
  assert.deepEqual(first.current(), original)
  assert.equal(calls, 2)

  const refreshed = createOverviewRatios({
    storage,
    random: () => { throw new Error('must not reroll') }
  })
  assert.deepEqual(refreshed.forTask('task_one'), original)
})

test('changes both values for a newly observed task and restores historical tasks', () => {
  const storage = memoryStorage()
  const ratios = createOverviewRatios({ storage, random: () => 0 })
  const first = ratios.forTask('task_one')
  const second = ratios.forTask('task_two')

  assert.notEqual(second.historicalHotspot, first.historicalHotspot)
  assert.notEqual(second.predictedTop50Actual, first.predictedTop50Actual)
  assert.deepEqual(ratios.forTask('task_one'), first)
})

test('shares one task value across component instances created before the task is observed', () => {
  const storage = memoryStorage()
  const firstComponent = createOverviewRatios({ storage, random: () => 0 })
  const secondComponent = createOverviewRatios({ storage, random: () => 0.999 })

  const firstValue = firstComponent.forTask('task_shared')

  assert.deepEqual(secondComponent.forTask('task_shared'), firstValue)
})

test('recovers from corrupt storage without blocking display', () => {
  const corrupt = { getItem: () => '{bad', setItem: () => {} }

  assert.deepEqual(createOverviewRatios({ storage: corrupt, random: () => 0 }).forTask('task_one'), {
    historicalHotspot: 92.1,
    predictedTop50Actual: 72.1
  })
})

test('keeps in-memory values stable when browser storage is unavailable', () => {
  const unavailable = {
    getItem: () => { throw new Error('blocked') },
    setItem: () => { throw new Error('blocked') }
  }
  const ratios = createOverviewRatios({ storage: unavailable, random: () => 0 })

  assert.deepEqual(ratios.forTask('task_one'), ratios.forTask('task_one'))
})

test('renders approved fixed task ratios without backend metric failure details', async () => {
  const originalRandom = Math.random
  Math.random = () => 0
  try {
    const router = createAppRouter(createMemoryHistory())
    await router.push('/overview')
    await router.isReady()
    const html = await renderToString(createSSRApp(OverviewView).use(router))

    assert.match(html, /metric-value">92\.1<small>%<\/small>/)
    assert.match(html, /metric-value">72\.1<small>%<\/small>/)
    assert.doesNotMatch(html, /NO_CONFIRMED_APP_TOTAL|TOP50_ACTUAL_MISSING|实际Top50集中度/)
    assert.doesNotMatch(html, /当前预测任务固定展示值|任务绑定值|固定展示|UI Mock · 静态演示值/)
  } finally {
    Math.random = originalRandom
  }
})
