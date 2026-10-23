import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createRouter, createMemoryHistory } from 'vue-router'

let server, HistoryPanel, LineChart
before(async()=>{
  server=await createServer({server:{middlewareMode:true,ws:false,watch:null},appType:'custom'})
  HistoryPanel=(await server.ssrLoadModule('/src/HistoryPanel.vue')).default
  LineChart=(await server.ssrLoadModule('/src/components/LineChart.vue')).default
})
after(async()=>{await server?.close()})
async function render(props) {
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{render:()=>null}},{path:'/prediction',component:{render:()=>null}}]})
  await router.push('/')
  return renderToString(createSSRApp({render:()=>h(HistoryPanel,props)}).use(router))
}

test('history requires a confirmed dataset and links to the existing READY selector',async()=>{
  const html=await render({dataset:null})
  assert.match(html,/先选择 READY 数据集/)
  assert.match(html,/href="\/prediction#prediction"/)
  assert.doesNotMatch(html,/UI MOCK/)
})

test('history scopes App options and dates to the selected dataset without promising App total traffic',async()=>{
  const html=await render({dataset:{datasetId:'ds_fixture',appId:'A',datasetSource:'FAKE',appDateRanges:{A:{start:'2026-01-01',end:'2026-01-07'},B:{start:'2026-02-01',end:'2026-02-03'}}}})
  assert.match(html,/ds_fixture/)
  assert.match(html,/value="A"/)
  assert.match(html,/value="B"/)
  assert.match(html,/value="2026-01-01"/)
  assert.match(html,/value="2026-01-07"/)
  assert.match(html,/仅已观测文件/)
  assert.match(html,/未确认 App 全量分母/)
  assert.match(html,/尚未查询/)
})

test('a history chart tooltip uses its actual source label rather than hardcoded UI Mock',async()=>{
  const html=await renderToString(createSSRApp({render:()=>h(LineChart,{labels:['2026-01-01'],series:[{name:'观测',values:[0],color:'#1677ff'}],unit:'GiB',sourceLabel:'接口观测',ariaLabel:'历史观测趋势'})}))
  assert.match(html,/历史观测趋势/)
  assert.doesNotMatch(html,/UI Mock/)
})
