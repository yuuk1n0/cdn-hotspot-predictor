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
  assert.match(html, /不会提交新预测/)
  assert.doesNotMatch(html, /<tbody>/)
  assert.match(html, /type="submit" disabled/)
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
