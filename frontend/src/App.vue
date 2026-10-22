<script setup>
import { onMounted, provide, ref, watch } from 'vue'
import AppLayout from './components/AppLayout.vue'
import { loadLatestFileDatasets } from './datasets.js'
import { uiContextKey } from './ui/context.js'
import { appOptions } from './ui/mock-data.js'

const selectedDataset = ref(null)
const previewAppId = ref('10350')
const fileDatasets = ref({})
const datasetBusy = ref(false)
const datasetError = ref('')

async function refreshDatasets() {
  datasetBusy.value = true
  datasetError.value = ''
  try {
    fileDatasets.value = await loadLatestFileDatasets(appOptions.map(app => app.id))
    selectedDataset.value = fileDatasets.value[previewAppId.value] || null
  } catch (error) {
    fileDatasets.value = {}
    selectedDataset.value = null
    datasetError.value = error.message || '真实数据集查询失败。'
  } finally {
    datasetBusy.value = false
  }
}

watch(previewAppId, appId => {
  if (selectedDataset.value?.appId !== appId) selectedDataset.value = fileDatasets.value[appId] || null
})
onMounted(refreshDatasets)

provide(uiContextKey, {
  selectedDataset, previewAppId, fileDatasets, datasetBusy, datasetError, refreshDatasets,
  selectDataset: value => {
    selectedDataset.value = value
    if (value?.appId) previewAppId.value = value.appId
  }
})
</script>

<template><AppLayout><RouterView /></AppLayout></template>
