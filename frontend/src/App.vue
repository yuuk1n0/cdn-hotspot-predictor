<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import CsvImportPanel from './CsvImportPanel.vue'
import PredictionPanel from './PredictionPanel.vue'
import Top50Table from './Top50Table.vue'

import {
  fetchTop50,
  fetchTop50Options
} from './top50.js'

const filters = reactive({
  startDate: '',
  endDate: '',
  appId: '',
  granularity: '',
  modelVersion: '',
  compressionStatus: ''
})
const options = ref({
  minDate: '',
  maxDate: '',
  appIds: [],
  granularities: [],
  modelVersions: [],
  compressionStatuses: []
})
const items = ref([])
const loading = ref(true)
const error = ref('')
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const resultRange = computed(() => {
  if (total.value === 0) return '0'
  const start = (page.value - 1) * pageSize.value + 1
  return `${start}–${Math.min(page.value * pageSize.value, total.value)}`
})

async function initialize() {
  loading.value = true
  error.value = ''
  try {
    options.value = await fetchTop50Options()
    filters.startDate = options.value.maxDate
    filters.endDate = options.value.maxDate
    await loadTop50()
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : '无法加载看板数据'
  } finally {
    loading.value = false
  }
}

async function loadTop50() {
  loading.value = true
  error.value = ''
  try {
    const result = await fetchTop50(filters, page.value, pageSize.value)
    items.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (requestError) {
    items.value = []
    total.value = 0
    error.value = requestError instanceof Error ? requestError.message : '无法加载看板数据'
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  loadTop50()
}

function resetFilters() {
  filters.startDate = options.value.maxDate
  filters.endDate = options.value.maxDate
  filters.appId = ''
  filters.granularity = ''
  filters.modelVersion = ''
  filters.compressionStatus = ''
  page.value = 1
  loadTop50()
}

function changePage(targetPage) {
  if (targetPage < 1 || targetPage > pageCount.value || targetPage === page.value) return
  page.value = targetPage
  loadTop50()
}

function changePageSize() {
  page.value = 1
  loadTop50()
}

onMounted(initialize)
</script>

<template>
  <main class="dashboard-shell">
    <header class="topbar">
      <a class="brand" href="#top50" aria-label="返回 Top50 看板顶部">
        <span class="brand__signal" aria-hidden="true"><i></i><i></i><i></i></span>
        <span><strong>CDN HOTSET</strong><small>预测与降本控制台</small></span>
      </a>
      <div class="environment"><span></span>本地验证环境</div>
    </header>

    <section id="top50" class="workspace" aria-labelledby="page-title">
      <div class="hero">
        <div>
          <p class="section-code">MODULE 03 / TOP50 RADAR</p>
          <h1 id="page-title">热点文件预测看板</h1>
          <p class="hero__copy">按预测窗口与业务维度检索热点候选，优先识别高流量、高压缩价值文件。</p>
        </div>
        <div class="mock-stamp" role="status">
          <span>REAL / FAKE · MOCK / MODEL</span>
          <strong>任务榜单与原型独立展示</strong>
          <small>来源明确标注 · 不混用预测结果</small>
        </div>
      </div>

      <CsvImportPanel />
      <PredictionPanel />

      <form class="filter-panel" aria-label="Top50 筛选条件" @submit.prevent="applyFilters">
        <div class="filter-panel__heading">
          <div><span>FAKE DATA / 第二阶段原型</span><strong>筛选演示预测结果</strong></div>
          <p>全部条件由 Spring Boot 转换为 MySQL 查询</p>
        </div>

        <div class="filter-grid">
          <label><span>开始日期</span><input v-model="filters.startDate" type="date" :min="options.minDate" :max="options.maxDate"></label>
          <label><span>结束日期</span><input v-model="filters.endDate" type="date" :min="options.minDate" :max="options.maxDate"></label>
          <label>
            <span>App ID</span>
            <select v-model="filters.appId">
              <option value="">全部 App</option>
              <option v-for="appId in options.appIds" :key="appId" :value="appId">{{ appId }}</option>
            </select>
          </label>
          <label>
            <span>预测粒度</span>
            <select v-model="filters.granularity">
              <option value="">日 / 周</option>
              <option v-for="option in options.granularities" :key="option.value" :value="option.value">{{ option.label }}预测</option>
            </select>
          </label>
          <label>
            <span>预测模型</span>
            <select v-model="filters.modelVersion">
              <option value="">全部模型</option>
              <option v-for="model in options.modelVersions" :key="model" :value="model">{{ model }}</option>
            </select>
          </label>
          <label>
            <span>压缩状态</span>
            <select v-model="filters.compressionStatus">
              <option value="">全部状态</option>
              <option v-for="option in options.compressionStatuses" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
        </div>

        <div class="filter-actions">
          <button class="button button--ghost" type="button" :disabled="loading" @click="resetFilters">重置条件</button>
          <button class="button button--primary" type="submit" :disabled="loading"><span>{{ loading ? '查询中' : '查询榜单' }}</span><span aria-hidden="true">↗</span></button>
        </div>
      </form>

      <section class="result-panel" aria-labelledby="result-title">
        <header class="result-header">
          <div><p class="section-code">LIVE QUERY RESULT</p><h2 id="result-title">Top50 候选序列</h2></div>
          <div class="result-meta"><span><b>{{ total }}</b> 条命中</span><span>当前显示 {{ resultRange }}</span><span>最多返回 Top50</span></div>
        </header>

        <div v-if="error" class="state-card state-card--error" role="alert">
          <span class="state-card__icon">!</span>
          <div><strong>查询失败</strong><p>{{ error }}。请检查 Spring Boot 与 MySQL 服务后重试。</p></div>
          <button class="button button--ghost" type="button" @click="loadTop50">重新查询</button>
        </div>
        <div v-else-if="loading" class="state-card" aria-live="polite">
          <span class="loader" aria-hidden="true"></span>
          <div><strong>正在扫描热点候选</strong><p>查询条件已发送至 Spring Boot，正在等待 MySQL 返回结果。</p></div>
        </div>
        <div v-else-if="items.length === 0" class="state-card">
          <span class="state-card__icon">0</span>
          <div><strong>没有匹配结果</strong><p>当前筛选组合未命中演示数据，请扩大日期范围或重置筛选条件。</p></div>
          <button class="button button--ghost" type="button" @click="resetFilters">重置条件</button>
        </div>

        <template v-else>
          <Top50Table :items="items" />
          <footer class="pagination">
            <label>每页<select v-model.number="pageSize" @change="changePageSize"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select>条</label>
            <div><button type="button" :disabled="page === 1 || loading" @click="changePage(page - 1)">上一页</button><span><b>{{ page }}</b> / {{ pageCount }}</span><button type="button" :disabled="page === pageCount || loading" @click="changePage(page + 1)">下一页</button></div>
          </footer>
        </template>
      </section>
    </section>
  </main>
</template>

<style>
:root {
  color: #d8e9e7;
  background: #06151b;
  font-family: "Segoe UI", "Microsoft YaHei", sans-serif;
  font-synthesis: none;
  color-scheme: dark;
}

* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  min-width: 320px;
  min-height: 100vh;
  background: radial-gradient(circle at 74% 0%, rgba(28, 123, 128, 0.18), transparent 32rem), linear-gradient(rgba(46, 211, 183, 0.035) 1px, transparent 1px), linear-gradient(90deg, rgba(46, 211, 183, 0.035) 1px, transparent 1px), #06151b;
  background-size: auto, 36px 36px, 36px 36px, auto;
}
button, input, select { font: inherit; }
button, select { cursor: pointer; }
button:disabled, select:disabled { cursor: not-allowed; opacity: 0.5; }
button:focus-visible, input:focus-visible, select:focus-visible, a:focus-visible { outline: 2px solid #ffb178; outline-offset: 3px; }
.dashboard-shell { min-height: 100vh; }
.topbar {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 70px;
  padding: 0 4vw;
  border-bottom: 1px solid rgba(122, 167, 167, 0.18);
  background: rgba(6, 21, 27, 0.9);
  backdrop-filter: blur(16px);
}
.brand { display: flex; align-items: center; gap: 0.85rem; color: inherit; text-decoration: none; }
.brand__signal { display: flex; align-items: end; gap: 3px; width: 34px; height: 34px; padding: 7px; border: 1px solid rgba(46, 211, 183, 0.34); background: rgba(46, 211, 183, 0.08); }
.brand__signal i { width: 4px; background: #2ed3b7; }
.brand__signal i:nth-child(1) { height: 35%; }
.brand__signal i:nth-child(2) { height: 65%; }
.brand__signal i:nth-child(3) { height: 100%; }
.brand strong, .brand small { display: block; }
.brand strong { font: 800 0.9rem/1 "Cascadia Code", monospace; letter-spacing: 0.11em; }
.brand small { margin-top: 0.3rem; color: #78979b; font-size: 0.68rem; }
.environment { display: flex; align-items: center; gap: 0.55rem; color: #9db5b6; font: 600 0.72rem/1 "Cascadia Code", monospace; }
.environment span { width: 7px; height: 7px; border-radius: 50%; background: #2ed3b7; box-shadow: 0 0 0 5px rgba(46, 211, 183, 0.09); }
.workspace { width: min(1720px, 100%); margin: 0 auto; padding: 4.5rem 4vw 5rem; }
.hero { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 3rem; align-items: end; margin-bottom: 2.5rem; }
.section-code { margin: 0 0 0.75rem; color: #2ed3b7; font: 700 0.7rem/1 "Cascadia Code", monospace; letter-spacing: 0.13em; }
h1, h2, p { margin-top: 0; }
h1 { margin-bottom: 1rem; color: #f1f8f7; font-family: "Bahnschrift Condensed", "Arial Narrow", "Microsoft YaHei", sans-serif; font-size: clamp(2.7rem, 6vw, 5.7rem); font-stretch: condensed; line-height: 0.94; letter-spacing: -0.055em; }
.hero__copy { max-width: 45rem; margin-bottom: 0; color: #8facad; font-size: 1rem; line-height: 1.75; }
.mock-stamp { min-width: 255px; padding: 1.2rem 1.35rem; border: 1px solid rgba(255, 155, 95, 0.5); background: repeating-linear-gradient(-45deg, transparent 0 8px, rgba(255, 155, 95, 0.035) 8px 16px), rgba(255, 155, 95, 0.04); }
.mock-stamp span, .mock-stamp strong, .mock-stamp small { display: block; }
.mock-stamp span { color: #ff9b5f; font: 800 0.7rem/1 "Cascadia Code", monospace; letter-spacing: 0.15em; }
.mock-stamp strong { margin-top: 0.7rem; color: #ffd5b9; font-size: 1.05rem; }
.mock-stamp small { margin-top: 0.45rem; color: #a58f83; font-size: 0.72rem; }
.filter-panel, .result-panel { border: 1px solid rgba(122, 167, 167, 0.18); background: rgba(10, 31, 39, 0.88); box-shadow: 0 28px 60px rgba(0, 0, 0, 0.16); }
.filter-panel { padding: 1.5rem; }
.filter-panel__heading, .result-header, .pagination { display: flex; align-items: center; justify-content: space-between; gap: 1.5rem; }
.filter-panel__heading { margin-bottom: 1.25rem; }
.filter-panel__heading span, .filter-panel__heading strong { display: block; }
.filter-panel__heading span { color: #5b7e82; font: 700 0.63rem/1 "Cascadia Code", monospace; letter-spacing: 0.12em; }
.filter-panel__heading strong { margin-top: 0.4rem; color: #d8e9e7; }
.filter-panel__heading p { margin-bottom: 0; color: #68878a; font-size: 0.75rem; }
.filter-grid { display: grid; grid-template-columns: repeat(6, minmax(145px, 1fr)); gap: 0.8rem; }
.filter-grid label > span { display: block; margin-bottom: 0.5rem; color: #7e9a9d; font-size: 0.72rem; font-weight: 700; }
input, select { width: 100%; min-height: 42px; border: 1px solid rgba(122, 167, 167, 0.24); border-radius: 0; padding: 0 0.75rem; color: #d8e9e7; background: #081a21; }
input:hover, select:hover { border-color: rgba(46, 211, 183, 0.55); }
.filter-actions { display: flex; justify-content: flex-end; gap: 0.75rem; margin-top: 1.25rem; padding-top: 1.25rem; border-top: 1px solid rgba(122, 167, 167, 0.14); }
.button { min-height: 42px; border: 1px solid transparent; padding: 0 1.15rem; font-size: 0.78rem; font-weight: 750; }
.button--primary { display: flex; align-items: center; gap: 2rem; color: #041613; border-color: #2ed3b7; background: #2ed3b7; }
.button--primary:hover:not(:disabled) { background: #65e5cf; }
.button--ghost { color: #b6ccca; border-color: rgba(122, 167, 167, 0.32); background: transparent; }
.button--ghost:hover:not(:disabled) { color: #e7f4f2; border-color: #78979b; }
.result-panel { margin-top: 1rem; overflow: hidden; }
.result-header { padding: 1.5rem; border-bottom: 1px solid rgba(122, 167, 167, 0.16); }
.result-header h2 { margin: 0; color: #edf7f5; font-family: "Bahnschrift Condensed", "Arial Narrow", "Microsoft YaHei", sans-serif; font-size: 1.65rem; letter-spacing: -0.02em; }
.result-meta { display: flex; flex-wrap: wrap; gap: 0.7rem; }
.result-meta span { padding: 0.48rem 0.65rem; border: 1px solid rgba(122, 167, 167, 0.18); color: #78979b; font: 600 0.68rem/1 "Cascadia Code", monospace; }
.result-meta b { color: #2ed3b7; }
.state-card { display: flex; align-items: center; gap: 1rem; min-height: 190px; padding: 2rem; color: #a8c0bf; }
.state-card--error { color: #ddaaa0; }
.state-card__icon, .loader { display: grid; flex: 0 0 auto; place-items: center; width: 42px; height: 42px; border: 1px solid currentColor; font: 800 1rem/1 "Cascadia Code", monospace; }
.loader { border-right-color: transparent; border-radius: 50%; animation: rotate 0.8s linear infinite; }
.state-card strong { color: #d8e9e7; }
.state-card p { margin: 0.4rem 0 0; font-size: 0.85rem; }
.state-card .button { margin-left: auto; }
.table-scroll { overflow-x: auto; }
table { width: 100%; min-width: 1580px; border-collapse: collapse; table-layout: fixed; }
th, td { padding: 1rem 0.85rem; border-bottom: 1px solid rgba(122, 167, 167, 0.12); text-align: left; vertical-align: middle; }
th { position: sticky; z-index: 2; top: 0; color: #6f9294; background: #0a1f27; font: 700 0.65rem/1 "Cascadia Code", monospace; letter-spacing: 0.08em; }
th:nth-child(1) { width: 90px; } th:nth-child(2) { width: 245px; } th:nth-child(3) { width: 75px; } th:nth-child(4) { width: 105px; } th:nth-child(5) { width: 115px; } th:nth-child(6) { width: 90px; } th:nth-child(7) { width: 260px; } th:nth-child(8) { width: 125px; } th:nth-child(9) { width: 100px; } th:nth-child(10) { width: 135px; } th:nth-child(11) { width: 125px; }
tbody tr { transition: background 140ms ease; }
tbody tr:hover { background: rgba(46, 211, 183, 0.035); }
td { color: #afc4c3; font-size: 0.78rem; }
.rank-cell strong { display: block; color: #ffb178; font: 800 0.85rem/1 "Cascadia Code", monospace; }
.rank-cell span { display: block; width: 48px; height: 3px; margin-top: 0.55rem; overflow: hidden; background: rgba(255, 155, 95, 0.13); }
.rank-cell i { display: block; width: var(--rank-heat); height: 100%; background: #ff9b5f; }
.file-key { overflow: hidden; color: #e0eeec; font-family: "Cascadia Code", monospace; font-size: 0.72rem; text-overflow: ellipsis; white-space: nowrap; }
.app-id, .date-cell + small { display: block; margin-top: 0.45rem; color: #56777a; font: 600 0.62rem/1 "Cascadia Code", monospace; }
.type-tag { color: #8fafb0; font-size: 0.72rem; }
.number-cell, .score-cell, code, .date-cell { font-family: "Cascadia Code", monospace; white-space: nowrap; }
.number-cell--primary, .score-cell { color: #5ce0c8; font-weight: 700; }
.reason { display: -webkit-box; overflow: hidden; margin: 0; color: #8ba5a6; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.status-pill { display: inline-flex; align-items: center; min-height: 25px; padding: 0 0.55rem; border: 1px solid; font-size: 0.68rem; white-space: nowrap; }
.status-pill--pending { color: #ffb178; border-color: rgba(255, 177, 120, 0.35); background: rgba(255, 177, 120, 0.06); }
.status-pill--processing { color: #70b9e8; border-color: rgba(112, 185, 232, 0.35); background: rgba(112, 185, 232, 0.06); }
.status-pill--compressed { color: #65d5a5; border-color: rgba(101, 213, 165, 0.35); background: rgba(101, 213, 165, 0.06); }
code { color: #9db5b6; font-size: 0.68rem; }
.date-cell { color: #c3d6d4; font-size: 0.72rem; }
.pagination { padding: 1rem 1.5rem; color: #78979b; font-size: 0.72rem; }
.pagination label, .pagination > div { display: flex; align-items: center; gap: 0.5rem; }
.pagination select { width: 70px; min-height: 34px; }
.pagination > div { gap: 0.85rem; }
.pagination button { min-height: 34px; border: 1px solid rgba(122, 167, 167, 0.25); padding: 0 0.8rem; color: #a9c0bf; background: transparent; }
.pagination button:hover:not(:disabled) { border-color: #2ed3b7; color: #2ed3b7; }
.pagination b { color: #2ed3b7; }
@keyframes rotate { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; animation-duration: 0.01ms !important; animation-iteration-count: 1 !important; transition-duration: 0.01ms !important; } }
@media (max-width: 1120px) { .filter-grid { grid-template-columns: repeat(3, minmax(160px, 1fr)); } }
@media (max-width: 720px) {
  .topbar, .workspace { padding-right: 1rem; padding-left: 1rem; }
  .workspace { padding-top: 2.5rem; }
  .environment, .filter-panel__heading p { display: none; }
  .hero { grid-template-columns: 1fr; gap: 1.5rem; }
  .mock-stamp { min-width: 0; }
  .filter-grid { grid-template-columns: 1fr 1fr; }
  .result-header, .pagination, .state-card { align-items: flex-start; flex-direction: column; }
  .state-card .button { margin-left: 0; }
}
@media (max-width: 480px) { .filter-grid { grid-template-columns: 1fr; } .filter-actions .button { flex: 1; } }
</style>
