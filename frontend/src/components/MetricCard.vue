<script setup>
import AppIcon from './AppIcon.vue'
defineProps({ label: String, value: [String, Number], unit: String, icon: String, note: String, delta: String, tone: String, hideFoot: Boolean })
</script>
<template><article class="card metric-card"><div class="metric-label"><span>{{ label }}</span><span class="metric-icon" :class="tone ? `metric-icon--${tone}` : ''"><AppIcon :name="icon || 'chart'" :size="18" /></span></div><div class="metric-value">{{ value ?? '—' }}<small v-if="unit">{{ unit }}</small></div><div v-if="!hideFoot" class="metric-foot"><span>{{ note || 'UI Mock · 静态演示值' }}</span><span v-if="delta" class="positive">↑ {{ delta }} <span class="text-muted">样例变化</span></span></div></article></template>
