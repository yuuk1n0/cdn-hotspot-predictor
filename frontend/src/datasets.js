import { dataApi } from './imports.js'

function validReadyDataset(value, appId) {
  return value?.status === 'READY' && value.datasetSource === 'REAL' &&
    value.datasetType === 'FILE_DAILY' && Array.isArray(value.appIds) && value.appIds.includes(appId) &&
    value.appDateRanges?.[appId]?.start && value.appDateRanges[appId].end
}

export async function loadLatestFileDatasets(appIds, fetchService = fetch) {
  const all = []
  for (let page = 1; page <= 20; page++) {
    const result = await dataApi.datasets(page, fetchService)
    if (!Array.isArray(result?.items)) throw new Error('数据集列表响应无效。')
    all.push(...result.items)
    if (all.length >= result.total || result.items.length === 0) break
  }
  const latest = {}
  for (const appId of appIds) {
    latest[appId] = all
      .filter(item => validReadyDataset(item, appId))
      .sort((left, right) => String(right.createdAt).localeCompare(String(left.createdAt)))[0] || null
  }
  return latest
}
