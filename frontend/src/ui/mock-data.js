// UI-only fixtures. Never used as an API fallback or submitted as business evidence.
export const uiMockSource = 'UI_MOCK'
export const mockDateRange = { start: '2026-10-01', end: '2026-10-13' }
export const appOptions = [{ id: '10350', label: '10350 · 内容分发' }, { id: '10066', label: '10066 · 应用资源' }]
export const mockApps = {
  '10350': { totalTraffic: '12.34', totalUnit: 'TB', hotspotRatio: '68.2', pending: '32', forecastSaving: '25,320', completed: '12', savedTraffic: '1.82', period: '2026-10-01 — 2026-10-13',
    totalSeries: [520, 610, 565, 640, 698, 664, 735, 702, 758, 825, 792, 880, 836],
    hotspotSeries: [310, 370, 345, 405, 442, 420, 478, 455, 495, 545, 522, 598, 565],
    predictedSeries: [335, 362, 370, 394, 460, 448, 466, 480, 512, 528, 550, 584, 596],
    distribution: [{ name: '视频文件', value: 46.2, color: '#1677ff' }, { name: '图片文件', value: 24.8, color: '#36c6a3' }, { name: '脚本 / 样式', value: 13.6, color: '#8f7eda' }, { name: '文档文件', value: 8.9, color: '#f4b953' }, { name: '其他', value: 6.5, color: '#a2b2c9' }] },
  '10066': { totalTraffic: '8.76', totalUnit: 'TB', hotspotRatio: '52.4', pending: '21', forecastSaving: '15,860', completed: '8', savedTraffic: '1.24', period: '2026-10-01 — 2026-10-13',
    totalSeries: [355, 402, 385, 430, 478, 462, 510, 485, 540, 582, 560, 625, 600],
    hotspotSeries: [165, 192, 180, 221, 235, 227, 255, 240, 278, 310, 295, 332, 320],
    predictedSeries: [175, 188, 200, 215, 248, 239, 260, 265, 270, 298, 310, 325, 340],
    distribution: [{ name: '应用安装包', value: 38.5, color: '#1677ff' }, { name: '图片文件', value: 28.3, color: '#36c6a3' }, { name: '视频文件', value: 19.2, color: '#8f7eda' }, { name: '脚本 / 样式', value: 8.6, color: '#f4b953' }, { name: '其他', value: 5.4, color: '#a2b2c9' }] }
}
export const chartDates = Array.from({ length: 13 }, (_, i) => `10-${String(i + 1).padStart(2, '0')}`)
const paths = ['/video/movie_20260928.mp4', '/images/banner_01.jpg', '/js/main.a7f3c2.js', '/images/product_123.png', '/video/intro.mp4', '/css/style.202609.css', '/docs/manual.pdf', '/images/icon.png']
const types = ['VIDEO', 'IMAGE', 'OTHER', 'IMAGE', 'VIDEO', 'OTHER', 'OTHER', 'IMAGE']
const sizeBytes = ['128200000', '4800000', '1200000', '6300000', '52100000', '328000', '2100000', '1800000']
const daily = ['860200000000', '210300000000', '160600000000', '98100000000', '76400000000', '64300000000', '52700000000', '48200000000']
const weekly = ['5260200000000', '1380300000000', '970600000000', '648100000000', '510400000000', '442300000000', '365700000000', '312200000000']
const secondDaily = ['542300000000', '178600000000', '124800000000', '87500000000', '61900000000', '43200000000', '35100000000', '28400000000']
const secondWeekly = ['3580200000000', '1080300000000', '760600000000', '538100000000', '410400000000', '342300000000', '265700000000', '212200000000']
const reasons = ['历史流量集中，样例呈持续访问趋势', '活动资源近期访问增加', '核心静态资源，重复请求较多', '产品图片具备体积优化空间', '长期访问的视频内容', '稳定命中的样式资源', '重复访问的说明文档', '高频使用的小型图片']
const statuses = ['PENDING', 'COMPRESSED', 'PENDING', 'PENDING', 'PROCESSING', 'PENDING', 'COMPRESSED', 'PENDING']
export function getMockFiles(appId = '10350', granularity = 'DAILY') {
  const forecast = appId === '10066' ? granularity === 'WEEKLY' ? secondWeekly : secondDaily : granularity === 'WEEKLY' ? weekly : daily
  return paths.map((path, index) => ({
    dataSource: uiMockSource, rank: index + 1, fileKey: appId === '10066' ? `/app-resources${path}` : path,
    appId, fileType: types[index], fileSizeBytes: sizeBytes[index], predictedBytes: forecast[index],
    score: [0.982, 0.961, 0.947, 0.928, 0.902, 0.875, 0.856, 0.831][index],
    recentActualBytes: ['820100000000', '190400000000', '132500000000', '76500000000', '60200000000', '52800000000', '38600000000', '35100000000'][index],
    reason: reasons[index], compressionStatus: statuses[index], modelVersion: 'hotspot-simple-v1',
    granularity, predictionDate: '2026-10-14', predictionEndDate: granularity === 'WEEKLY' ? '2026-10-20' : '2026-10-14',
    uiCompressionRatio: ['35%', '50%', '30%', '40%', '30%', '45%', '45%', '40%'][index],
    uiEstimatedBenefit: ['8,620', '2,310', '1,280', '720', '1,080', '560', '980', '380'][index]
  }))
}

// Comparison numbers are layout fixtures, not evaluated model scores or deployed model claims.
export const mockModelComparison = [
  { name: 'Last Day', description: '最近观测基线 · 方案样例', color: '#1677ff', recall: '68.4%', ndcg: '0.591', coverage: '62.1%', mape: null, trend: [64, 67, 62, 69, 65, 70, 66, 71, 68, 72, 69, 73, 68], values: [68.4, 59.1, 62.1] },
  { name: 'Rolling Mean', description: '滑动均值 · 方案样例', color: '#ff9d45', recall: '76.8%', ndcg: '0.642', coverage: '71.6%', mape: null, trend: [72, 75, 71, 77, 74, 79, 76, 81, 77, 82, 78, 80, 77], values: [76.8, 64.2, 71.6] },
  { name: 'Exponential Decay', description: '指数衰减 · 方案样例', color: '#37bfa5', recall: '73.2%', ndcg: '0.628', coverage: '68.3%', mape: null, trend: [68, 72, 70, 74, 73, 77, 72, 76, 74, 79, 75, 77, 73], values: [73.2, 62.8, 68.3] }
]

export function getMockModels(appId) {
  if (appId !== '10066') return mockModelComparison
  const values = [[65.1, 55.8, 59.0], [71.2, 61.3, 66.7], [74.3, 63.4, 68.9]]
  return mockModelComparison.map((model, index) => ({ ...model, values: values[index], recall: `${values[index][0]}%`, ndcg: (values[index][1] / 100).toFixed(3), coverage: `${values[index][2]}%`, trend: model.trend.map(value => value - (index === 0 ? 4 : index === 1 ? 5 : -1)) }))
}

export const agentQuestions = [
  '今天有哪些值得压缩的文件？', '哪些 AppID 的热点流量占比最高？',
  '最近哪个模型预测效果最好？', '哪些文件压缩后收益最大？', '帮我生成一份 CDN 降本分析报告。'
]
