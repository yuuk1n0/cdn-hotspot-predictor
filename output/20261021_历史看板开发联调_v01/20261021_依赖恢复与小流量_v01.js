async (page) => {
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  const assert=(v,m)=>{if(!v)throw new Error(m);};
  await page.getByRole('button',{name:'查询历史观测',exact:true}).click();
  await page.locator('.history-result-context').waitFor();
  assert(await page.getByRole('alert').count()===0,'restored service still shows error');
  assert((await page.locator('.history-result-context').innerText()).includes('10350'),'restore lost selected App');
  await page.locator('.sidebar nav').getByRole('link',{name:'热点预测',exact:true}).click();
  await page.getByRole('button',{name:'接口数据 · 已有功能',exact:true}).click();
  await page.locator('.dataset-selection select').first().selectOption('ds_3c527ca3b91b481adf2b64f1090650b97b493a70504a8168706abebe097388cf');
  await page.getByRole('combobox',{name:'业务 App',exact:true}).selectOption('A');
  await page.getByRole('button',{name:'使用所选数据集和 App',exact:true}).click();
  await page.locator('.sidebar nav').getByRole('link',{name:'业务总览',exact:true}).click();
  await page.locator('#history-file').fill('b.mp4');
  await page.getByRole('button',{name:'查询历史观测',exact:true}).click();
  await page.locator('.history-result-context').waitFor();
  assert((await page.locator('.chart-unit').innerText()).includes('/ B'),'small traffic lost byte unit');
  const coordinates=await page.locator('.history-trend circle').evaluateAll(points=>points.map(p=>Number(p.getAttribute('cy'))));
  assert(coordinates.length===2&&Math.max(...coordinates)-Math.min(...coordinates)>40,'small trend collapsed into zero axis');
  await page.screenshot({path:'/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_历史看板开发联调_v01/20261021_小流量缩放_v01.png',fullPage:true});
  assert(errors.length===0,'runtime errors after recovery');
  return {status:'PASSED',recoveredRealQuery:true,smallTrafficUnit:'B',pointYCoordinates:coordinates,runtimeErrors:errors};
}
