async (page) => {
  const datasetId = 'ds_a2740c2769b0e4e56d95c5a6f7022457391aa48479b1eed8c3b00e231c057c3a';
  await page.goto('http://localhost:5173');
  await page.locator('.dataset-selection select').first().selectOption(datasetId);
  await page.locator('.dataset-selection select').nth(1).selectOption('10350');
  await page.getByRole('button',{name:'使用所选数据集和 App',exact:true}).click();
  const form = page.getByRole('form',{name:'提交日周预测'});
  await form.locator('input[type=date]').fill('2026-10-06');
  await form.locator('input[maxlength="128"]').fill('hotspot-simple-v1');
  const results = [];
  for (const days of [1,7]) {
    await form.locator('select').selectOption(String(days));
    const existing = await page.evaluate(() => JSON.parse(localStorage.getItem('cdn.prediction.submission') || 'null'));
    const reuse = existing?.taskId && existing.body?.datasetId === datasetId && existing.body?.appId === '10350' && existing.body?.cutoffDate === '2026-10-06' && existing.body?.horizonDays === days && existing.body?.modelVersion === 'hotspot-simple-v1';
    const requestPromise = reuse ? Promise.resolve(null) : page.waitForRequest(req => req.method()==='POST' && req.url().endsWith('/api/predictions'));
    await page.getByRole('button',{name:'提交预测任务',exact:true}).click();
    const request = await requestPromise;
    const requestBody = request ? request.postDataJSON() : existing.body;
    await page.locator('.run-snapshot').filter({hasText: days===1 ? '下一自然日' : '连续 7 天累计'}).waitFor({timeout:45000});
    await page.getByRole('heading',{name:'本次查询榜单',exact:true}).waitFor({timeout:45000});
    const summary = await page.locator('.run-snapshot .identity-grid').innerText();
    const taskId = (await page.locator('.task-summary .identity-grid dd').first().innerText()).trim();
    const runId = (await page.locator('.run-snapshot .identity-grid dd').first().innerText()).trim();
    const api = await page.request.get(new URL('/api/predictions/'+encodeURIComponent(runId),page.url()).href);
    const snapshot = await api.json();
    if (!api.ok() || snapshot.taskId!==taskId || snapshot.horizonDays!==days || snapshot.datasetId!==datasetId || snapshot.appId!=='10350') throw new Error('snapshot identity mismatch');
    if (!summary.includes(snapshot.top50PredictedBytes+' B')) throw new Error('bytes not displayed exactly');
    if (snapshot.predictionSource!=='MODEL' || snapshot.resultMode!=='REAL') throw new Error('existing deployed mode misrepresented');
    results.push({request:requestBody,reusedAcceptedTask:Boolean(reuse),idempotencyKey:request ? request.headers()['idempotency-key'] : existing.key,taskId,runId,source:snapshot.predictionSource,resultMode:snapshot.resultMode,predictionStartDate:snapshot.predictionStartDate,predictionEndDate:snapshot.predictionEndDate,candidateCount:snapshot.candidateCount,top50Count:snapshot.top50Count,top50PredictedBytes:snapshot.top50PredictedBytes});
    await page.screenshot({path:'/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_四小时开发执行_v01/20261021_'+(days===1?'日预测':'周预测')+'_v01.png',fullPage:true});
  }
  await page.reload();
  await page.getByRole('heading',{name:'本次查询榜单',exact:true}).waitFor({timeout:20000});
  if (!(await page.locator('.run-snapshot').innerText()).includes(results[1].runId)) throw new Error('refresh did not restore original run');
  return {results,refreshRunId:results[1].runId,fakeRows:await page.locator('.result-panel tbody tr').count()};
}
