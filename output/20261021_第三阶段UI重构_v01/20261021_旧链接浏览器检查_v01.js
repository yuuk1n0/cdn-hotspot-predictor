async (page) => {
  const probe=await page.context().newPage();
  const checks=[],requests=[],errors=[];
  const assert=(value,message)=>{if(!value)throw new Error(message);};
  probe.on('pageerror',error=>errors.push(error.message));
  try {
    await probe.route(/\/(api|data-api)\//,async route=>{
      requests.push({method:route.request().method(),path:new URL(route.request().url()).pathname});
      await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'UI_TEST_DEPENDENCY_OFFLINE',message:'仅用于前端错误展示验证的离线响应；未调用后端'})});
    });
    await probe.setViewportSize({width:1366,height:900});
    for(const suffix of ['?runId=ui-link-check#prediction','#top50']) {
      await probe.goto('http://localhost:5175/'+suffix);
      const hash=new URL(probe.url()).hash;
      await probe.locator(hash).waitFor();
      await probe.waitForFunction(()=>scrollY>0);
      assert(new URL(probe.url()).pathname==='/prediction','legacy link route lost');
      assert(await probe.getByRole('region',{name:'已有接口功能'}).isVisible(),'API mode missing');
      if(hash==='#prediction') {
        await probe.getByRole('alert').filter({hasText:'查询未完成'}).waitFor();
        assert(new URL(probe.url()).searchParams.get('runId')==='ui-link-check','run identifier lost');
      }
      assert(await probe.locator('.mock-prediction-table').count()===0,'API failure fell back to mock');
      const size=await probe.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,y:scrollY}));
      assert(size.scroll<=size.width,'legacy view overflows');
      checks.push({link:suffix,hash,...size,status:'PASSED'});
    }
    await probe.screenshot({path:'/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_第三阶段UI重构_v01/20261021_已有功能离线UI_v01.png',fullPage:true});
    assert(requests.every(item=>item.method==='GET'),'unexpected business write');
    assert(errors.length===0,'runtime error '+errors.join(','));
    await page.goto('http://localhost:5175/compression');
    await page.getByRole('button',{name:'查看详情',exact:true}).first().click();
    const dialog=page.getByRole('dialog');
    await dialog.waitFor();
    const icon=await dialog.locator('.comparison-art>span>svg').first().boundingBox();
    assert(icon.width===22&&icon.height===22,'comparison play icon stretched');
    await page.screenshot({path:'/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_第三阶段UI重构_v01/20261021_压缩效果详情_v02.png'});
    await page.keyboard.press('Escape');
    await page.goto('http://localhost:5175/overview');
    return {status:'PASSED',checks,interceptedRequests:requests,runtimeErrors:errors,note:'所有业务请求由前端浏览器夹具拦截；未连接后端。'};
  } finally { await probe.close(); }
}
