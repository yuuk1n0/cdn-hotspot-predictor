<script setup>
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from './AppIcon.vue'
import BaseDialog from './BaseDialog.vue'
import LineChart from './LineChart.vue'
import CompressionRegistrationPanel from './CompressionRegistrationPanel.vue'
import { chartDates, mockApps } from '../ui/mock-data.js'
import { fileTypeLabels, formatBytes, formatScore, compressionStatusLabels } from '../top50.js'
const props = defineProps({ file: Object, run: Object, uiMock: { type: Boolean, default: false }, action: { type: String, default: 'details' } })
const emit = defineEmits(['close'])
const tab = ref('basic')
const tabs = [{ id:'basic',label:'基本信息' },{ id:'forecast',label:'预测信息' },{ id:'compression',label:'压缩信息' },{ id:'history',label:'历史趋势' }]
watch(() => [props.file, props.action], () => { tab.value = props.action === 'compression' ? 'compression' : 'basic' }, { immediate: true })
const sample = computed(() => mockApps[props.file?.appId] || mockApps['10350'])
const series = computed(() => [{ name:'历史访问流量 · UI Mock', color:'#1677ff',values:sample.value.hotspotSeries,fill:true }])
</script>
<template>
  <BaseDialog :open="Boolean(file)" :title="file?.fileKey || '文件详情'" @close="emit('close')">
    <template v-if="file"><div class="dialog-source"><span class="source-badge" :class="{'source-badge--api':!uiMock}">{{ uiMock ? 'UI MOCK · 静态文件样例' : `${run?.datasetSource || '接口数据'} / ${run?.predictionSource || '来源见原运行'}` }}</span><span>App {{ file.appId }}</span></div><nav class="panel-tabs" aria-label="文件详情 Tab"><button v-for="item in tabs" :key="item.id" :class="{active:tab===item.id}" :aria-pressed="tab===item.id" @click="tab=item.id">{{ item.label }}</button></nav><div class="dialog-body">
      <div v-if="tab==='basic'" class="detail-grid"><div class="detail-preview"><AppIcon name="file" :size="42"/><strong>文件业务信息</strong><p>仅展示当前运行快照已有字段。</p></div><dl class="detail-info"><dt>AppID</dt><dd>{{ file.appId }}</dd><dt>FileKey</dt><dd class="mono">{{ file.fileKey }}</dd><dt>文件类型</dt><dd>{{ fileTypeLabels[file.fileType] || '未知' }}</dd><dt>文件大小</dt><dd>{{ formatBytes(file.fileSizeBytes) }}</dd><dt>信息来源</dt><dd>{{ uiMock ? 'UI Mock 固定样例' : `${run?.datasetSource || '接口数据'} / ${run?.predictionSource || '来源见原运行'}` }}</dd></dl></div>
      <div v-else-if="tab==='forecast'"><h3 class="subheading">预测与推荐信息</h3><dl class="detail-info"><dt>预测窗口</dt><dd>{{ file.predictionDate || run?.predictionStartDate || '未知' }} — {{ file.predictionEndDate || run?.predictionEndDate || '未知' }}</dd><dt>窗口累计流量</dt><dd class="fact-value">{{ file.predictedBytes ?? '未知' }} <template v-if="file.predictedBytes != null">B</template></dd><dt>预测分数</dt><dd>{{ formatScore(file.score) }}</dd><dt>推荐理由</dt><dd>{{ file.reason || '接口未提供' }}</dd><dt>模型版本</dt><dd class="mono">{{ file.modelVersion || run?.modelVersion || '未知' }}</dd><dt>最近实际流量</dt><dd>{{ formatBytes(file.recentActualBytes) }}</dd><dt v-if="run?.runId">runId</dt><dd v-if="run?.runId" class="mono">{{ run.runId }}</dd></dl></div>
      <CompressionRegistrationPanel v-else-if="tab==='compression' && !uiMock && run?.runId" :key="`${run.runId}:${file.fileKey}`" :file="file" :run="run" />
      <div v-else-if="tab==='compression'" class="empty-panel"><AppIcon name="compression" :size="38"/><h3>样例文件无压缩登记</h3><p>请从已发布预测运行的真实文件进入压缩登记。</p><RouterLink class="btn" to="/compression" @click="emit('close')">查看压缩降本 UI <AppIcon name="arrow" :size="14"/></RouterLink></div>
      <div v-else><template v-if="uiMock"><div class="notice notice--amber">UI Mock 历史趋势 · 与当前文件真实访问无关</div><LineChart :labels="chartDates" :series="series" aria-label="UI Mock 文件历史访问趋势"/></template><div v-else class="empty-panel"><AppIcon name="chart" :size="38"/><h3>历史趋势未接入</h3><p>当前只展示已有接口中的预测字段，未请求文件历史统计。</p></div></div>
    </div></template>
  </BaseDialog>
</template>
<style scoped>.dialog-source{display:flex;align-items:center;justify-content:space-between;padding:16px 22px 0;font-size:11px;color:#8b9db4}.dialog-source+.panel-tabs{margin-bottom:0}.detail-info small{font-size:9px;display:block;margin-top:5px;color:#8f9fb3}</style>
