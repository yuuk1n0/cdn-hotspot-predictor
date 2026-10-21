async (page) => {
  const params={datasetId:'ds_a2740c2769b0e4e56d95c5a6f7022457391aa48479b1eed8c3b00e231c057c3a',appId:'10350',startDate:'2026-10-07',endDate:'2026-10-13',granularity:'WEEKLY',topK:50};
  const assert=(v,m)=>{if(!v)throw new Error(m);};
  const url='http://localhost:5173/api/history?'+new URLSearchParams(params);
  const probe=await page.request.get(url);
  const response=await probe.json();
  assert(probe.status()===503,'dependency unavailable did not return 503');
  assert(response.code==='PYTHON_UNAVAILABLE'||response.error?.code==='PYTHON_UNAVAILABLE','dependency error code lost');
  await page.getByRole('button',{name:'查询历史观测',exact:true}).click();
  const alert=page.getByRole('alert').filter({hasText:'历史查询失败'});
  await alert.waitFor();
  assert((await alert.innerText()).includes('PYTHON_UNAVAILABLE'),'real dependency error not shown');
  assert(await page.locator('.history-result-context').count()===0,'old result masquerades as current');
  assert(await page.locator('.overview-bottom').count()===0,'dependency failure replaced with static Mock');
  assert(await page.locator('#history-app').inputValue()==='10350','query context lost');
  assert(await page.locator('#history-start').inputValue()==='2026-10-07','date context lost');
  await page.screenshot({path:'/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_历史看板开发联调_v01/20261021_真实依赖错误_v01.png',fullPage:true});
  return {status:'PASSED',httpStatus:probe.status(),response,oldResultsRemoved:true,mockFallback:false,queryContextRetained:true,note:'已停止本轮Python实例；没有网络响应替身。'};
}
