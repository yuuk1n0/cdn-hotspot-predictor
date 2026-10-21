<script setup>
import { provide, ref } from 'vue'
import AppLayout from './components/AppLayout.vue'
import { uiContextKey } from './ui/context.js'

const selectedDataset = ref(null)
const previewAppId = ref('10350')
provide(uiContextKey, { selectedDataset, previewAppId, selectDataset: value => { selectedDataset.value = value } })
</script>

<template><AppLayout><RouterView /></AppLayout></template>
