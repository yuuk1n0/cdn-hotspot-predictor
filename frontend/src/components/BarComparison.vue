<script setup>
defineProps({ models: { type: Array, required: true } })
const colors = ['#1677ff','#8ebcff','#39c5a4']
const labels = ['Recall@50','NDCG@50 × 100','流量覆盖率']
</script>
<template><div class="bar-chart"><div class="legend"><span v-for="(label,i) in labels" :key="label"><i :style="{background:colors[i]}"/>{{ label }}</span></div><svg viewBox="0 0 600 248" role="img" aria-label="UI Mock 不同模型指标对比柱状图"><g v-for="tick in [0,25,50,75,100]" :key="tick"><line x1="42" :y1="210-tick*1.8" x2="580" :y2="210-tick*1.8" class="chart-gridline"/><text x="30" :y="214-tick*1.8" text-anchor="end" class="chart-axis">{{ tick }}</text></g><g v-for="(model, index) in models" :key="model.name"><rect v-for="(value,j) in model.values" :key="j" :x="90+index*165+j*25" :y="210-value*1.8" width="20" :height="value*1.8" :fill="colors[j]" rx="1"/><text :x="125+index*165" y="234" text-anchor="middle" class="chart-axis">{{ model.name }}</text></g></svg></div></template>
<style scoped>.bar-chart{padding:0 14px 11px}.legend{justify-content:flex-end;padding:0 3px 15px}.bar-chart svg{display:block;width:100%;height:auto}</style>
