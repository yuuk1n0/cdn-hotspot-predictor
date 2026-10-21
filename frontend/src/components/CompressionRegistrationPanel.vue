<script setup>
import { ref, watch } from 'vue'
import { createCompression, listCompressions, updateCompression } from '../compressions.js'

const props = defineProps({ run: { type: Object, required: true }, file: { type: Object, required: true } })
const records = ref([])
const selected = ref(null)
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const message = ref('')
const form = ref({})
let requestSequence = 0
let retry = null

function reset() {
  selected.value = null
  form.value = { status: 'PROCESSING', evidenceSource: props.run.datasetSource === 'REAL' && props.run.predictionSource === 'MODEL' ? 'REAL' : 'TEST', handler: '', remark: '', originalSizeBytes: '', originalSizeEvidence: '', compressedFileKey: '', compressedVersion: '', compressedSizeBytes: '', compressedAt: '', metric: 'MANUAL_REVIEW', qualityValue: '', passed: false, evidenceNote: '' }
  retry = null
}

async function load() {
  const sequence = ++requestSequence
  reset()
  records.value = []
  error.value = ''
  message.value = ''
  busy.value = false
  loading.value = true
  try {
    const rows = await listCompressions(props.run, props.file.fileKey)
    if (sequence === requestSequence) records.value = rows
  } catch (e) { if (sequence === requestSequence) error.value = e.message }
  finally { if (sequence === requestSequence) loading.value = false }
}

watch(() => [props.run?.runId, props.run?.appId, props.file?.fileKey], load, { immediate: true })

function edit(record) {
  selected.value = record
  form.value = {
    status: record.status, evidenceSource: record.evidenceSource, handler: record.handler, remark: record.remark || '',
    originalSizeBytes: record.originalSizeBytes || '', originalSizeEvidence: record.originalSizeEvidence || '',
    compressedFileKey: record.compressedFileKey || '', compressedVersion: record.compressedVersion || '',
    compressedSizeBytes: record.compressedSizeBytes || '', compressedAt: record.compressedAt || '',
    metric: record.quality?.metric || 'MANUAL_REVIEW', qualityValue: record.quality?.value ?? '',
    passed: record.quality?.passed ?? false, evidenceNote: record.quality?.evidenceNote || ''
  }
  error.value = ''
  message.value = ''
  retry = null
}

function payload() {
  const fields = { status: form.value.status, handler: form.value.handler.trim(), remark: form.value.remark.trim() }
  if (!selected.value) fields.evidenceSource = form.value.evidenceSource
  if (fields.status === 'COMPRESSED' && selected.value?.status !== 'COMPRESSED') {
    if (form.value.evidenceSource === 'TEST') {
      fields.originalSizeBytes = form.value.originalSizeBytes.trim()
      fields.originalSizeSource = 'MANUAL_TEST'
      fields.originalSizeEvidence = form.value.originalSizeEvidence.trim()
    }
    fields.compressedFileKey = form.value.compressedFileKey.trim()
    fields.compressedVersion = form.value.compressedVersion.trim()
    fields.compressedSizeBytes = form.value.compressedSizeBytes.trim()
    fields.compressedAt = new Date(form.value.compressedAt).toISOString()
    fields.quality = { metric: form.value.metric, evidenceNote: form.value.evidenceNote.trim() }
    if (fields.quality.metric === 'MANUAL_REVIEW') fields.quality.passed = form.value.passed
    if (['VMAF', 'SSIM'].includes(fields.quality.metric)) fields.quality.value = Number(form.value.qualityValue)
  }
  return fields
}

async function save() {
  if (busy.value || loading.value) return
  const sequence = requestSequence
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const fields = payload()
    let record
    if (selected.value) record = await updateCompression(props.run, props.file.fileKey, selected.value, fields)
    else {
      const signature = JSON.stringify(fields)
      if (!retry || retry.signature !== signature) retry = { signature, key: crypto.randomUUID() }
      record = await createCompression(props.run, props.file.fileKey, fields, retry.key)
    }
    if (sequence !== requestSequence) return
    retry = null
    selected.value = record
    records.value = await listCompressions(props.run, props.file.fileKey)
    if (sequence === requestSequence) { edit(record); message.value = '登记已保存，数据来自 Java 接口。' }
  } catch (e) { if (sequence === requestSequence) error.value = e.message }
  finally { if (sequence === requestSequence) busy.value = false }
}
</script>

<template>
  <section class="registration" aria-label="压缩登记">
    <h3>压缩处理登记</h3>
    <p>预测运行 {{ run.runId }} · App {{ run.appId }} · 原文件 {{ file.fileKey }}</p>
    <p>登记只记录处理证据；压缩降本效果需完成后续观测。</p>
    <p v-if="loading">正在读取登记记录…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <div v-if="records.length"><strong>现有登记（{{ records.length }}）</strong>
      <ul><li v-for="record in records" :key="record.id"><button type="button" @click="edit(record)">{{ record.status }} · {{ record.evidenceSource }} · {{ record.handler }} · v{{ record.version }}</button>
        <span v-if="record.status === 'COMPRESSED'"> · {{ record.originalSizeBytes }} → {{ record.compressedSizeBytes }} B · 压缩率 {{ record.compressionRatio == null ? '未知' : `${(record.compressionRatio * 100).toFixed(1)}%` }} · 30%门槛 {{ record.compressionThresholdPassed ? '达到' : '未达到' }} · 质量 {{ record.qualityPassed == null ? '未知' : record.qualityPassed ? '通过' : '未通过' }} · 效果待后续观察</span></li></ul>
    </div>
    <button type="button" :disabled="busy || loading" @click="reset">新建登记</button>
    <form @submit.prevent="save">
      <label>证据来源<select v-model="form.evidenceSource" :disabled="!!selected || busy"><option v-if="run.datasetSource === 'REAL'" value="REAL">REAL · CStore 原大小</option><option value="TEST">TEST · 人工测试证据</option></select></label>
      <label>状态<select v-model="form.status" :disabled="selected?.status === 'COMPRESSED' || busy"><option value="PROCESSING">处理中</option><option value="COMPRESSED">已压缩</option></select></label>
      <label>处理人<input v-model="form.handler" required maxlength="128" :disabled="busy"></label>
      <label>备注<textarea v-model="form.remark" maxlength="2000" :disabled="busy"></textarea></label>
      <template v-if="form.status === 'COMPRESSED'">
        <p v-if="form.evidenceSource === 'REAL'">原大小由 Java 实时核验 CStore；已有登记的原大小：{{ selected?.originalSizeBytes ?? '待查询' }} B。</p>
        <template v-else><label>人工原大小（B）<input v-model="form.originalSizeBytes" required inputmode="numeric" :disabled="selected?.status === 'COMPRESSED' || busy"></label>
          <label>原大小测试来源说明<input v-model="form.originalSizeEvidence" required maxlength="2000" :disabled="selected?.status === 'COMPRESSED' || busy"></label></template>
        <label>压缩文件 fileKey<input v-model="form.compressedFileKey" :required="!form.compressedVersion" :disabled="selected?.status === 'COMPRESSED' || busy"></label>
        <label>压缩版本<input v-model="form.compressedVersion" :disabled="selected?.status === 'COMPRESSED' || busy"></label>
        <label>压缩后大小（B）<input v-model="form.compressedSizeBytes" required inputmode="numeric" :disabled="selected?.status === 'COMPRESSED' || busy"></label>
        <label>压缩完成时间<input v-model="form.compressedAt" required type="datetime-local" :disabled="selected?.status === 'COMPRESSED' || busy"></label>
        <label>质量证据<select v-model="form.metric" :disabled="selected?.status === 'COMPRESSED' || busy"><option value="MANUAL_REVIEW">人工复核</option><option value="VMAF">VMAF</option><option value="SSIM">SSIM</option><option value="UNKNOWN">未知</option></select></label>
        <label v-if="form.metric === 'MANUAL_REVIEW'">复核结果<select v-model="form.passed" :disabled="selected?.status === 'COMPRESSED' || busy"><option :value="true">通过</option><option :value="false">未通过</option></select></label>
        <label v-if="form.metric === 'VMAF' || form.metric === 'SSIM'">质量分数<input v-model="form.qualityValue" required type="number" step="any" min="0" :max="form.metric === 'VMAF' ? 100 : 1" :disabled="selected?.status === 'COMPRESSED' || busy"></label>
        <label>质量证据说明<input v-model="form.evidenceNote" required maxlength="2000" :disabled="selected?.status === 'COMPRESSED' || busy"></label>
      </template>
      <button type="submit" :disabled="busy || loading">{{ busy ? '保存中…' : selected ? '更新登记' : '新建登记' }}</button>
    </form>
  </section>
</template>

<style scoped>
.registration{padding:16px 22px;color:#ced9e8;font-size:13px}.registration h3{margin:0 0 8px}.registration p{line-height:1.5;overflow-wrap:anywhere}.registration form{display:grid;gap:12px;margin-top:16px}.registration label{display:grid;gap:5px}.registration input,.registration select,.registration textarea{max-width:100%;padding:8px;border:1px solid #53647a;border-radius:5px;background:#14233a;color:#f2f6fa}.registration button{padding:7px 12px;cursor:pointer}.registration ul{padding-left:20px}.registration li{margin:6px 0;overflow-wrap:anywhere}.error{color:#f59696}
</style>
