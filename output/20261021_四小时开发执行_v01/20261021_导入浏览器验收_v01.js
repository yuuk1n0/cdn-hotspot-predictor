async (page) => {
  const datasetId = 'ds_a2740c2769b0e4e56d95c5a6f7022457391aa48479b1eed8c3b00e231c057c3a';
  const before = await page.locator('.upload-receipt').innerText();
  await page.reload();
  await page.locator('.quality-report').waitFor({timeout:15000});
  const restored = await page.locator('.upload-receipt').innerText();
  if (!restored.includes('SUCCEEDED') || !restored.includes(datasetId)) throw new Error('refresh did not GET original import');
  await page.getByRole('button', {name:'使用所选数据集和 App',exact:true}).click();
  const selected = await page.locator('.workspace > p[role=status]').innerText();
  await page.screenshot({path:'/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_四小时开发执行_v01/20261021_导入成功_v01.png',fullPage:true});
  await page.getByRole('button', {name:'选择另一份文件',exact:true}).click();
  await page.locator('#csv-file').setInputFiles({name:'invalid-test.csv',mimeType:'text/csv',buffer:Buffer.from('文件标识,日期,当日流量（B）,AppID\na,not-a-date,10,10350\n')});
  await page.locator('.upload-panel fieldset select').nth(0).selectOption('FAKE');
  await page.locator('.upload-panel fieldset select').nth(1).selectOption('B');
  await page.getByRole('button', {name:'上传 CSV',exact:true}).click();
  await page.locator('.upload-receipt').filter({hasText:'FAILED'}).waitFor({timeout:15000});
  const failed = await page.locator('.upload-receipt').innerText();
  const failedReport = await page.locator('.quality-report').innerText();
  if (failed.includes('本次数据集：')) throw new Error('failed task published dataset');
  if (!failedReport.includes('本次导入质量报告')) throw new Error('failed report masked by old selection');
  const oldLabel = await page.locator('.dataset-selection').innerText();
  if (!oldLabel.includes('此前选择')) throw new Error('old selection not labelled');
  await page.screenshot({path:'/home/yukino/projects/work/cvte/cdn-hotspot-predictor/output/20261021_四小时开发执行_v01/20261021_导入失败_v01.png',fullPage:true});
  return {before,restored,selected,failed,failedReport,oldSelectionLabel:oldLabel.slice(0,350)};
}
