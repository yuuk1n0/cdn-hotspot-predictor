<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref, useId } from 'vue'
const props = defineProps({ labels: { type: Array, default: () => [] }, series: { type: Array, default: () => [] }, unit: { type: String, default: 'GB' }, maxY: Number, ariaLabel: { type: String, default: 'UI Mock 趋势图' }, sourceLabel: { type: String, default: 'UI Mock' }, showLegend: { type: Boolean, default: true } })
const id = useId().replaceAll(':', '')
const hoverIndex = ref(null)
const chartElement = ref(null)
const chartWidth = ref(700)
const bounds = reactive({ left: 44, right: 679, top: 17, bottom: 206 })
let resizeObserver
onMounted(() => {
  resizeObserver = new ResizeObserver(entries => {
    chartWidth.value = Math.max(260, entries[0].contentRect.width)
    bounds.right = chartWidth.value - 21
  })
  if (chartElement.value) resizeObserver.observe(chartElement.value)
})
onBeforeUnmount(() => resizeObserver?.disconnect())
const maximum = computed(() => props.maxY || Math.ceil(Math.max(1, ...props.series.flatMap(s => s.values).filter(v => Number.isFinite(v))) / 100) * 100)
const x = index => bounds.left + index * (bounds.right - bounds.left) / Math.max(1, props.labels.length - 1)
const y = value => bounds.bottom - value / maximum.value * (bounds.bottom - bounds.top)
const ticks = computed(() => Array.from({ length: 5 }, (_, i) => maximum.value * i / 4))
const labelStep = computed(() => Math.max(1, Math.ceil(props.labels.length / 10)))
function line(values) {
  let gap = true
  return values.map((value, index) => {
    if (!Number.isFinite(value)) { gap = true; return '' }
    const command = gap ? 'M' : 'L'; gap = false
    return `${command}${x(index)},${y(value)}`
  }).join(' ')
}
function area(values) {
  if (!values.length || values.some(value => !Number.isFinite(value))) return ''
  return `${line(values)} L${x(values.length - 1)},${bounds.bottom} L${x(0)},${bounds.bottom} Z`
}
function track(event) {
  const rect = event.currentTarget.getBoundingClientRect()
  const relative = (event.clientX - rect.left) / rect.width * chartWidth.value
  hoverIndex.value = Math.max(0, Math.min(props.labels.length - 1, Math.round((relative - bounds.left) / (bounds.right - bounds.left) * (props.labels.length - 1))))
}
</script>
<template>
  <div class="line-chart">
    <div v-if="showLegend" class="legend line-legend"><span v-for="item in series" :key="item.name"><i :style="{ background: item.color }" />{{ item.name }}</span></div>
    <div class="chart-unit">{{ unit === '%' ? '指标' : '流量' }} / {{ unit }}<span v-if="unit === '%'"> · 样例指标</span></div>
    <div ref="chartElement" class="chart-wrap" :aria-label="ariaLabel">
      <svg :viewBox="`0 0 ${chartWidth} 238`" role="img" :aria-label="ariaLabel" @pointermove="track" @pointerleave="hoverIndex = null">
        <defs><linearGradient v-for="(item, index) in series" :id="`${id}-${index}`" :key="item.name" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" :stop-color="item.color" stop-opacity=".18"/><stop offset="100%" :stop-color="item.color" stop-opacity=".015"/></linearGradient></defs>
        <g v-for="tick in ticks" :key="tick"><line :x1="bounds.left" :y1="y(tick)" :x2="bounds.right" :y2="y(tick)" class="chart-gridline"/><text :x="bounds.left - 10" :y="y(tick) + 4" class="chart-axis" text-anchor="end">{{ Number.isInteger(tick) ? tick : tick.toFixed(1) }}{{ unit === '%' ? '%' : '' }}</text></g>
        <g v-for="(label, index) in labels" :key="label"><line v-if="index % labelStep === 0" :x1="x(index)" :y1="bounds.top" :x2="x(index)" :y2="bounds.bottom" stroke="#f3f6fa"/><text v-if="index % labelStep === 0" :x="x(index)" y="227" text-anchor="middle" class="chart-axis">{{ label }}</text></g>
        <g v-for="(item, index) in series" :key="item.name"><path v-if="item.fill" :d="area(item.values)" :fill="`url(#${id}-${index})`"/><path :d="line(item.values)" fill="none" :stroke="item.color" stroke-width="2.2" :stroke-dasharray="item.dashed ? '5 4' : undefined" stroke-linejoin="round"/><template v-for="(value, i) in item.values" :key="i"><circle v-if="Number.isFinite(value)" :cx="x(i)" :cy="y(value)" r="2.7" :fill="item.color" stroke="white" stroke-width="1.2"/></template></g>
        <line v-if="hoverIndex !== null" :x1="x(hoverIndex)" y1="17" :x2="x(hoverIndex)" y2="206" stroke="#b3c4dc" stroke-dasharray="3 3"/>
      </svg>
      <div v-if="hoverIndex !== null" class="chart-tooltip"><strong>{{ labels[hoverIndex] }} · {{ sourceLabel }}</strong><span v-for="item in series" :key="item.name"><i :style="{background:item.color}"/>{{ item.name }}<b>{{ item.values[hoverIndex] ?? '无观测' }} {{ unit }}</b></span></div>
    </div>
  </div>
</template>
<style scoped>.line-chart{position:relative}.line-legend{justify-content:flex-end;padding:0 20px 10px;min-height:24px}.chart-wrap{position:relative}.chart-wrap svg{height:238px}.chart-tooltip{position:absolute;top:17px;left:20%;pointer-events:none;padding:12px 14px;background:#fffffff5;box-shadow:0 3px 18px #263d5c20;border:1px solid #e8edf4;border-radius:5px;font-size:10px;color:#6e839e;min-width:175px}.chart-tooltip strong{display:block;color:#4b6383;font-size:10px;margin-bottom:9px}.chart-tooltip span{display:flex;gap:6px;margin-top:7px;align-items:center}.chart-tooltip i{width:5px;height:5px;border-radius:50%}.chart-tooltip b{margin-left:auto;color:#516d91;font-weight:500}</style>
