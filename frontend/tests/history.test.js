import test from 'node:test'
import assert from 'node:assert/strict'
import { createHistoryQuery } from '../src/history.js'

const params = { datasetId:'ds_one',appId:'A',startDate:'2026-01-01',endDate:'2026-01-07',granularity:'WEEKLY',fileKey:'a&b?#c',topK:50 }
const body = p => ({...p,scope:'OBSERVED_FILES',datasetSource:'FAKE',completeness:'UNKNOWN',observedTrafficBytes:'10000000000000001',days:Array.from({length:7},(_,i)=>({date:`2026-01-0${i+1}`,observedTrafficBytes:i===0?null:i===1?'10000000000000001':'0',fileCount:i===0?0:1,status:i===0?'NO_OBSERVATION':'OBSERVED'})),items:[],missingDates:[p.startDate],hotspotTrafficRatio:{value:null,reasonCode:'NO_CONFIRMED_APP_TOTAL',basis:'HISTORICAL_ACTUAL',unit:'PERCENT'}})
const response = value => ({ok:true,status:200,json:async()=>value})

test('history filters reach Java with exact file identity and retain nulls and large byte strings',async()=>{
  let url
  const query = createHistoryQuery({fetchService:async path=>{url=new URL(path,'http://local');return response(body(params))}})
  await query.load(params)
  assert.equal(url.pathname,'/api/history')
  assert.equal(url.searchParams.get('fileKey'),'a&b?#c')
  assert.equal(url.searchParams.get('appId'),'A')
  assert.equal(query.state.result.observedTrafficBytes,'10000000000000001')
  assert.equal(query.state.result.days[0].observedTrafficBytes,null)
})

test('changing dataset invalidates in-flight responses and clears the old result',async()=>{
  let release
  const query=createHistoryQuery({fetchService:async()=>new Promise(resolve=>{release=resolve})})
  const waiting=query.load(params)
  query.clear()
  release(response(body(params)))
  await waiting
  assert.equal(query.state.result,null)
  assert.equal(query.state.busy,false)
})

test('dependency errors and cross-App responses never publish an empty successful result',async()=>{
  for(const fetchService of [async()=>{throw new TypeError('offline')},async()=>response(body({...params,appId:'B'}))]){
    const query=createHistoryQuery({fetchService})
    await query.load(params)
    assert.equal(query.state.result,null)
    assert.ok(query.state.error)
  }
})

test('all history filters reach the backend and a successful explicit zero stays observed',async()=>{
  let url
  const query=createHistoryQuery({fetchService:async path=>{url=new URL(path,'http://local');return response(body(params))}})
  await query.load(params)
  for(const [key,value] of Object.entries(params)) assert.equal(url.searchParams.get(key),String(value))
  assert.equal(query.state.result.days[2].observedTrafficBytes,'0')
  assert.equal(query.state.result.days[2].status,'OBSERVED')
  assert.equal(query.state.request.fileKey,params.fileKey)
})

test('bad dates, week length, topK and required identity fail before any HTTP request',async()=>{
  let calls=0
  const query=createHistoryQuery({fetchService:async()=>{calls++;return response(body(params))}})
  for(const patch of [{appId:''},{datasetId:' bad '},{startDate:'2026-02-30'},{endDate:'2026-01-06'},{topK:51},{topK:0},{granularity:'MONTHLY'},{granularity:'DAILY',endDate:'2027-01-02'}]){
    await query.load({...params,...patch})
    assert.ok(query.state.error,JSON.stringify(patch))
    assert.equal(query.state.result,null)
    assert.equal(query.state.busy,false)
  }
  assert.equal(calls,0)
})

test('newer queries win even if an aborted older fetch completes late',async()=>{
  const pending=[]
  const query=createHistoryQuery({fetchService:(path,options)=>new Promise(resolve=>pending.push({resolve,signal:options.signal}))})
  const older=query.load(params)
  const newer=query.load({...params,appId:'B'})
  assert.equal(pending[0].signal.aborted,true)
  pending[1].resolve(response(body({...params,appId:'B'})))
  await newer
  pending[0].resolve(response(body(params)))
  await older
  assert.equal(query.state.result.appId,'B')
  assert.equal(query.state.busy,false)
})

test('HTTP dependency failure removes a prior result without publishing a fake empty success',async()=>{
  let success=true
  const query=createHistoryQuery({fetchService:async()=>success?response(body(params)):{ok:false,status:503,json:async()=>({code:'PYTHON_UNAVAILABLE',message:'观测服务离线'})}})
  await query.load(params)
  assert.ok(query.state.result)
  success=false
  await query.load(params)
  assert.equal(query.state.result,null)
  assert.match(query.state.error,/PYTHON_UNAVAILABLE/)
})

test('mismatched windows, numeric bytes, missing days and invented ratio are rejected',async()=>{
  for(const patch of [
    {endDate:'2026-01-06'}, {datasetSource:'UI_MOCK'}, {observedTrafficBytes:100},
    {days:[]}, {days:body(params).days.map((d,i)=>i===0?{...d,observedTrafficBytes:'0'}:d)},
    {hotspotTrafficRatio:{value:0.5}}, {items:[{rank:1,fileKey:'f',observedTrafficBytes:99,observedDays:1}]}
  ]){
    const query=createHistoryQuery({fetchService:async()=>response({...body(params),...patch})})
    await query.load(params)
    assert.equal(query.state.result,null,JSON.stringify(patch))
    assert.match(query.state.error,/契约/)
  }
})

test('history chart scales small observations visibly while preserving missing points and exact source bytes',async()=>{
  const { historyChartData }=await import('../src/history.js')
  const days=[{observedTrafficBytes:'1048576'},{observedTrafficBytes:'0'},{observedTrafficBytes:null}]
  const chart=historyChartData(days)
  assert.equal(chart.unit,'MiB')
  assert.deepEqual(chart.values,[1,0,null])
  assert.ok(chart.maxY>=1&&chart.maxY<=2)
  assert.equal(days[0].observedTrafficBytes,'1048576')
  const small=historyChartData([{observedTrafficBytes:'40'}])
  assert.equal(small.unit,'B')
  assert.ok(small.maxY>=40&&small.maxY<60)
  assert.equal(historyChartData([{observedTrafficBytes:null}]).maxY,1)
})
