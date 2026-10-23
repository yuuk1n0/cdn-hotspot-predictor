<script setup>
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import AppIcon from './AppIcon.vue'
const props = defineProps({ open: Boolean, title: String, width: { type: String, default: '980px' } })
const emit = defineEmits(['close'])
const element = ref(null)
const headingId = useId()
watch(() => props.open, async open => {
  await nextTick()
  if (!element.value) return
  if (open && !element.value.open) element.value.showModal()
  else if (!open && element.value.open) element.value.close()
}, { immediate: true })
function backdrop(event) {
  if (event.target !== element.value) return
  const rect = element.value.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) emit('close')
}
function cycleFocus(event) {
  if (event.ctrlKey || event.altKey || event.metaKey) return
  const nodes = [...element.value.querySelectorAll('button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')].filter(node => node.getClientRects().length > 0)
  if (!nodes.length) { event.preventDefault(); element.value.focus(); return }
  const first = nodes[0], last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
onBeforeUnmount(() => { if (element.value?.open) element.value.close() })
</script>
<template><Teleport to="body"><dialog ref="element" class="dialog ui-dialog" :style="{width:`min(${width}, calc(100vw - 40px))`}" :aria-labelledby="headingId" @cancel.prevent="emit('close')" @keydown.tab="cycleFocus" @click="backdrop"><header class="dialog-heading"><h2 :id="headingId">{{ title }}</h2><button class="dialog-close" type="button" aria-label="关闭弹窗" @click="emit('close')"><AppIcon name="close" :size="19"/></button></header><slot /></dialog></Teleport></template>
<style>.ui-dialog{padding:0;border:1px solid #e1e8f1;margin:auto}.ui-dialog::backdrop{background:#112b4e62;backdrop-filter:blur(2px)}.ui-dialog:not([open]){display:none}</style>
