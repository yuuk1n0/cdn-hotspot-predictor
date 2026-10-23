import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

let server
before(async () => {
  // Compile the real SFCs without opening a listening socket or installing browser tooling.
  server = await createServer({ server: { middlewareMode: true, ws: false, watch: null }, appType: 'custom' })
})
after(async () => { await server?.close() })

test('query panel initially offers task/run lookup without inventing task success or ranking rows', async () => {
  const { default: Panel } = await server.ssrLoadModule('/src/PredictionPanel.vue')
  const html = await renderToString(createSSRApp(Panel))
  assert.match(html, /Java taskId/)
  assert.match(html, /runId · 读取榜单/)
  assert.match(html, /等待编号/)
  assert.match(html, /提交日周预测/)
  assert.match(html, /请先在上方使用 READY 数据集和 App/)
  assert.doesNotMatch(html, /<tbody>/)
  assert.match(html, /type="submit" disabled/)
})

test('a manual lookup cannot replace or race the automatic handoff while a submission is in flight', async () => {
  const { default: Panel } = await server.ssrLoadModule('/src/PredictionPanel.vue')
  const SubmittingPanel = { ...Panel, setup(props, context) {
    const exposed = Panel.setup(props, context)
    exposed.submitState.busy = true
    exposed.id.value = 'task_manual'
    exposed.query()
    assert.equal(exposed.state.id, '')
    assert.equal(exposed.state.phase, 'IDLE')
    return exposed
  } }
  const html = await renderToString(createSSRApp(SubmittingPanel))
  assert.match(html, /type="submit" disabled[^>]*>查询/)
})

test('run panel labels explicit and legacy result modes without changing the original source', async () => {
  const { default: Panel } = await server.ssrLoadModule('/src/PredictionPanel.vue')
  for (const [predictionSource, resultMode] of [['MOCK', 'MOCK'], ['MODEL', 'REAL']]) {
    for (const explicitMode of [false, true]) {
      const snapshot = Object.freeze({
        runId: 'run_one', datasetId: 'ds_one', appId: '10350', cutoffDate: '2026-10-06',
        horizonDays: 1, modelVersion: 'baseline-v1', predictionStartDate: '2026-10-07', predictionEndDate: '2026-10-07',
        datasetSource: 'REAL', predictionSource, candidateCount: 0, top50Count: 0, top50PredictedBytes: '0', items: [],
        ...(explicitMode ? { resultMode } : {})
      })
      const WithSnapshot = { ...Panel, setup(props, context) {
        const exposed = Panel.setup(props, context)
        exposed.state.result = snapshot
        exposed.state.resultIsCurrent = true
        return exposed
      } }
      const html = await renderToString(createSSRApp(WithSnapshot))
      assert.match(html, new RegExp(`结果模式：${resultMode}`))
      assert.match(html, new RegExp(`${predictionSource} ·`))
      assert.equal(Object.hasOwn(snapshot, 'resultMode'), explicitMode)
    }
  }
})

test('run snapshot does not render withdrawn fixed ratio metrics', async () => {
  const { default: Panel } = await server.ssrLoadModule('/src/PredictionPanel.vue')
  const snapshot = Object.freeze({
    taskId: 'task_fixed_ratio', runId: 'run_fixed_ratio', datasetId: 'ds_one', appId: '10350', cutoffDate: '2026-10-06',
    horizonDays: 1, modelVersion: 'hotspot-simple-v1', predictionStartDate: '2026-10-07', predictionEndDate: '2026-10-07',
    datasetSource: 'REAL', predictionSource: 'MODEL', resultMode: 'REAL', candidateCount: 0, top50Count: 0,
    top50PredictedBytes: '0', items: []
  })
  const WithSnapshot = { ...Panel, setup(props, context) {
    const exposed = Panel.setup(props, context)
    exposed.state.result = snapshot
    exposed.state.resultIsCurrent = true
    return exposed
  } }
  const html = await renderToString(createSSRApp(WithSnapshot))
  assert.doesNotMatch(html, /预测 Top50 实际命中占比|同窗口实际 Top50 集中度|72\.1%|92\.1%/)
})

test('real snapshot table preserves exact byte strings, escapes file keys and shows unknown fields honestly', async () => {
  const { default: Table } = await server.ssrLoadModule('/src/Top50Table.vue')
  const html = await renderToString(createSSRApp(Table, { showExactBytes: true, items: [{
    rank: 1, fileKey: '<script>alert(1)</script>', appId: '10350', predictedBytes: '10000000000000001',
    fileSizeBytes: null, recentActualBytes: null, compressionStatus: null, fileType: null, score: null,
    reason: null, modelVersion: 'contract-demo-v1', granularity: 'WEEKLY', predictionDate: '2026-10-07', predictionEndDate: '2026-10-13'
  }] }))
  assert.match(html, /10000000000000001 B/)
  assert.match(html, /连续 7 天累计/)
  assert.match(html, /2026-10-13/)
  assert.match(html, /未知/)
  assert.match(html, /—/)
  assert.match(html, /&lt;script&gt;/)
  assert.doesNotMatch(html, /<script>/)
  assert.doesNotMatch(html, /0 B/)
})

test('shared table still renders the second-stage Fake fields and daily label', async () => {
  const { default: Table } = await server.ssrLoadModule('/src/Top50Table.vue')
  const html = await renderToString(createSSRApp(Table, { items: [{
    rank: 1, fileKey: 'demo/file.mp4', appId: 'demo', fileType: 'VIDEO', fileSizeBytes: '1024',
    predictedBytes: '2048', score: 0.5, reason: '原型演示', recentActualBytes: '0', compressionStatus: 'PENDING',
    modelVersion: 'fake-v1', granularity: 'DAILY', predictionDate: '2026-10-07'
  }] }))
  assert.match(html, /demo\/file.mp4/)
  assert.match(html, /原型演示/)
  assert.match(html, /status-pill--pending/)
  assert.match(html, /日预测/)
  assert.match(html, /0 B/)
})
