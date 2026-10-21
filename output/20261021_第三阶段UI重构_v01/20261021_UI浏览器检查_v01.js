async (page) => {
  const directory='/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_第三阶段UI重构_v01';
  const runtimeErrors=[],businessRequests=[],checks=[];
  page.on('pageerror',error=>runtimeErrors.push(error.message));
  page.on('request',request=>{const path=new URL(request.url()).pathname;if(path.startsWith('/api/')||path.startsWith('/data-api/'))businessRequests.push({method:request.method(),path});});
  const assert=(condition,message)=>{if(!condition)throw new Error(message);};
  const pages=[['overview','业务总览'],['prediction','热点预测'],['backtest','模型回测'],['compression','压缩降本'],['agent','智能 Agent']];
  await page.goto('http://localhost:5175');
  await page.evaluate(()=>document.fonts.ready);
  assert(new URL(page.url()).pathname==='/overview','root did not redirect to overview');
  assert(await page.locator('.sidebar nav a').count()===5,'navigation must have exactly five entries');
  for(const [route,title] of pages){
    await page.locator('.sidebar nav').getByRole('link',{name:title,exact:true}).click();
    await page.locator(`[data-page="${route}"]`).waitFor();
    assert(new URL(page.url()).pathname===`/${route}`,'route mismatch '+route);
    assert(await page.locator('.sidebar nav .router-link-active').count()===1,'active links not unique');
    assert((await page.locator('.sidebar nav .router-link-active').innerText()).includes(title),'highlight mismatch');
    const size=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight}));
    assert(size.scroll<=size.width,'1920 page horizontally overflows '+route);
    await page.screenshot({path:`${directory}/20261021_${title.replaceAll(' ','')}_1920_v01.png`,fullPage:true});
    checks.push({route,viewport:1920,...size,status:'PASSED'});
  }
  await page.locator('.sidebar nav').getByRole('link',{name:'业务总览',exact:true}).click();
  const before=await page.locator('.metric-value').first().innerText();
  await page.locator('#overview-app').selectOption('10066');
  const after=await page.locator('.metric-value').first().innerText();
  assert(before!==after,'App switch did not change independent UI fixture');
  await page.locator('#overview-app').selectOption('10350');
  await page.getByRole('button',{name:'近7天',exact:true}).click();
  assert(await page.getByLabel('历史开始日期',{exact:true}).inputValue()==='2026-10-07','quick range incorrect');
  await page.getByLabel('历史开始日期',{exact:true}).fill('2026-10-01');
  await page.getByRole('button',{name:'周榜',exact:true}).click();
  assert((await page.getByRole('heading',{name:'Top50 热点预测摘要 周榜'}).innerText()).includes('周榜'),'weekly summary missing');
  checks.push({check:'overview_app_date_granularity',before,after,status:'PASSED'});

  await page.locator('.sidebar nav').getByRole('link',{name:'热点预测',exact:true}).click();
  await page.getByRole('button',{name:'周榜预测',exact:true}).click();
  assert((await page.locator('.task-metric--window').innerText()).includes('2026-10-20'),'weekly target range incorrect');
  await page.getByRole('button',{name:'开始预测',exact:true}).click();
  assert((await page.getByRole('status').innerText()).includes('未创建预测任务'),'mock submit pretends success');
  const previewButton=page.locator('.mock-prediction-table').getByRole('button',{name:'预览',exact:true}).first();
  await previewButton.click();
  let dialog=page.getByRole('dialog');
  await dialog.waitFor();
  assert((await dialog.innerText()).includes('未加载实际媒体内容'),'preview disguises actual media');
  await dialog.getByRole('button',{name:'预测信息',exact:true}).click();
  assert((await dialog.innerText()).includes('窗口累计流量'),'forecast tab missing');
  await dialog.getByRole('button',{name:'历史趋势',exact:true}).click();
  assert((await dialog.innerText()).includes('与当前文件真实访问无关'),'history fixture not labelled');
  await page.screenshot({path:`${directory}/20261021_文件详情_v01.png`});
  await dialog.getByRole('button',{name:'关闭弹窗',exact:true}).focus();
  await page.keyboard.press('Shift+Tab');
  assert(await page.evaluate(()=>Boolean(document.activeElement?.closest('dialog'))),'modal focus trap failed');
  await page.keyboard.press('Escape');
  await dialog.waitFor({state:'hidden'});
  await page.locator('.mock-prediction-table').getByRole('button',{name:'下载',exact:true}).first().click();
  dialog=page.getByRole('dialog');
  assert((await dialog.innerText()).includes('未执行该业务操作'),'download pretends business execution');
  await page.keyboard.press('Escape');
  checks.push({check:'prediction_window_dialog_tabs_focus_unconnected_actions',status:'PASSED'});

  await page.locator('.sidebar nav').getByRole('link',{name:'模型回测',exact:true}).click();
  await page.getByRole('button',{name:'开始回测',exact:true}).click();
  assert((await page.getByRole('status').innerText()).includes('未提交回测任务'),'backtest pretends execution');
  const bestA=await page.locator('.best-row .model-name').innerText();
  await page.locator('#backtest-app').selectOption('10066');
  const bestB=await page.locator('.best-row .model-name').innerText();
  assert(bestA!==bestB,'per-App comparison fixtures did not change');
  await page.locator('#backtest-app').selectOption('10350');
  checks.push({check:'backtest_static_boundary_per_app',bestA,bestB,status:'PASSED'});

  await page.locator('.sidebar nav').getByRole('link',{name:'压缩降本',exact:true}).click();
  await page.locator('#compression-status').selectOption('COMPRESSED');
  assert(await page.locator('.table-card tbody tr').count()===2,'UI status filter did not affect sample rows');
  await page.locator('#compression-status').selectOption('ALL');
  await page.getByRole('button',{name:'查看详情',exact:true}).first().click();
  dialog=page.getByRole('dialog');
  await dialog.waitFor();
  assert((await dialog.innerText()).includes('非真实压缩记录'),'compression sample not labelled');
  for(const tab of ['访问趋势','收益分析','处理记录','效果对比']){
    await dialog.getByRole('button',{name:tab,exact:true}).click();
    assert(await dialog.isVisible(),'compression dialog tab failed '+tab);
  }
  await page.screenshot({path:`${directory}/20261021_压缩效果详情_v01.png`});
  await page.keyboard.press('Escape');
  await page.getByRole('button',{name:/标记压缩/}).first().click();
  assert((await page.getByRole('status').innerText()).includes('未修改任何文件状态'),'compression action pretends a write');
  checks.push({check:'compression_filters_tabs_estimate_actual_boundary',status:'PASSED'});

  await page.locator('.sidebar nav').getByRole('link',{name:'智能 Agent',exact:true}).click();
  await page.getByRole('button',{name:'今天有哪些值得压缩的文件？',exact:true}).click();
  assert(await page.locator('#agent-message').inputValue()==='今天有哪些值得压缩的文件？','quick question did not fill composer');
  await page.getByRole('button',{name:'发送消息',exact:true}).click();
  await page.getByText('当前为 UI 预览，尚未连接 LLM 或实时业务工具，暂不能回答该业务问题。没有发起模型请求、执行查询或生成报告。',{exact:true}).waitFor();
  await page.locator('#agent-app').selectOption('10066');
  assert(await page.locator('.chat-message--user').count()===0,'messages crossed App contexts');
  await page.locator('#agent-app').selectOption('10350');
  assert(await page.locator('.chat-message--user').count()===1,'original App messages lost');
  await page.screenshot({path:`${directory}/20261021_Agent交互_v01.png`,fullPage:true});
  checks.push({check:'agent_composer_message_states_no_llm_per_app',status:'PASSED'});
  for(const viewport of [{width:1366,height:900},{width:1024,height:768}]){
    await page.setViewportSize(viewport);
    for(const [route,title] of pages){
      await page.goto(`http://localhost:5175/${route}`);
      await page.locator(`[data-page="${route}"]`).waitFor();
      const size=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
      assert(size.scroll<=size.width,'small desktop horizontal overflow '+route+' '+viewport.width);
      if(viewport.width===1366) await page.screenshot({path:`${directory}/20261021_${title.replaceAll(' ','')}_1366_v01.png`,fullPage:true});
      checks.push({route,viewport:viewport.width,...size,status:'PASSED'});
    }
  }
  assert(businessRequests.length===0,'UI preview unexpectedly called business API: '+JSON.stringify(businessRequests));
  assert(runtimeErrors.length===0,'browser runtime errors: '+JSON.stringify(runtimeErrors));
  await page.setViewportSize({width:1920,height:1080});
  await page.goto('http://localhost:5175/overview');
  return {checks,businessRequests,runtimeErrors,status:'PASSED',scope:'FRONTEND_UI_ONLY_NO_BACKEND_INTEGRATION'};
}
