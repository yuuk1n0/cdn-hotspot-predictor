<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
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
  <section id="top50" class="legacy-top50">
    <div class="notice notice--amber"><strong>FAKE · 既有原型接口数据</strong><span>本区域保留原筛选与分页请求，数据由已有接口返回，与独立 UI Mock 区分。</span></div>
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
</template>
