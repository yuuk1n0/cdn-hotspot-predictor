import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createMemoryHistory } from 'vue-router'

let server, createRouter, App
before(async()=>{
  server=await createServer({server:{middlewareMode:true,ws:false,watch:null},appType:'custom'})
  createRouter=(await server.ssrLoadModule('/src/router/index.js')).createAppRouter
  App=(await server.ssrLoadModule('/src/App.vue')).default
})
after(async()=>{await server?.close()})

test('root defaults to overview, with notification center and one current navigation link',async()=>{
  for(const path of ['/','/prediction','/backtest','/compression','/notifications','/agent']){
    const router=createRouter(createMemoryHistory())
    await router.push(path)
    const expected=path==='/'?'overview':path.slice(1)
    assert.equal(router.currentRoute.value.name,expected)
    const html=await renderToString(createSSRApp(App).use(router))
    assert.ok(html.includes(`data-page="${expected}"`))
    const nav=html.match(/<nav aria-label="主导航"[^>]*>([\s\S]*?)<\/nav>/)?.[1]
    assert.ok(nav)
    assert.equal((nav.match(/href=/g)||[]).length,6)
    assert.equal((nav.match(/aria-current="page"/g)||[]).length,1)
    assert.ok(nav.includes('通知中心'))
  }
})

test('legacy task and run links keep query identifiers and enter the existing API view',async()=>{
  for(const path of ['/?taskId=task_saved','/?runId=run_saved#prediction','/#top50']){
    const router=createRouter(createMemoryHistory())
    await router.push(path)
    assert.equal(router.currentRoute.value.path,'/prediction')
    const html=await renderToString(createSSRApp(App).use(router))
    assert.ok(html.includes('导入数据与发起新的真实预测任务'))
    assert.ok(html.includes('CSV 上传'))
    assert.equal(router.currentRoute.value.fullPath,path.replace(/^\//,'/prediction'))
  }
})

test('notification and agent routes render live workflow controls',async()=>{
  for (const [path, labels] of [
    ['/notifications', ['通知预览', '确认目标与内容后发送', '发送记录', 'App 通知配置']],
    ['/agent', ['查询上下文', '运营问答', '模型配置与建议', '生成当前跨度建议']]
  ]) {
    const router=createRouter(createMemoryHistory())
    await router.push(path)
    const html=await renderToString(createSSRApp(App).use(router))
    for (const label of labels) assert.ok(html.includes(label), `${path} should render ${label}`)
  }
})

test('an unknown page returns to overview rather than leaving an empty content panel',async()=>{
  const router=createRouter(createMemoryHistory())
  await router.push('/not-a-page')
  assert.equal(router.currentRoute.value.name,'overview')
})
