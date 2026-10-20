<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { createCsvImport } from './imports.js'

let storage = null
try { storage = window.localStorage } catch {}
const upload = createCsvImport({ storage })
const { state } = upload
const fileInput = ref(null)
const locked = computed(() => state.busy || state.phase === 'ACCEPTED')
const phaseLabel = computed(() => ({
  IDLE: '等待选择与提交', UPLOADING: '提交中', ACCEPTED: '上传已接收', FAILED: '提交失败'
})[state.phase])

function reset() {
  upload.reset()
  if (fileInput.value) fileInput.value.value = ''
}

onMounted(upload.restore)
onUnmounted(upload.dispose)
</script>

<template>
  <section class="upload-panel" aria-labelledby="csv-upload-title">
    <header class="upload-heading">
      <div>
        <p class="section-code">CSV / 数据上传</p>
        <h2 id="csv-upload-title">上传流量观测文件</h2>
        <p>提交文件后保留导入任务编号。接收成功不代表数据导入完成。</p>
      </div>
      <span class="upload-state" :class="`upload-state--${state.phase.toLowerCase()}`" role="status">{{ phaseLabel }}</span>
    </header>

    <form aria-label="CSV 上传" @submit.prevent="upload.submit">
      <fieldset :disabled="locked">
        <legend class="upload-sr-only">文件与导入配置</legend>
        <div class="upload-file">
          <label for="csv-file">CSV 文件</label>
          <input id="csv-file" ref="fileInput" type="file" accept=".csv,text/csv" @change="upload.selectFile($event.target.files?.[0])">
          <p v-if="state.file" class="upload-filename">{{ state.file.name }} <span>{{ state.file.size.toLocaleString() }} B</span></p>
          <p class="upload-hint">上限 5 MiB，支持 UTF-8 / GB18030，由导入服务解析。请先确认实际表头及单位。</p>
        </div>

        <div class="upload-options">
          <label>
            <span>数据来源</span>
            <select v-model="state.options.datasetSource">
              <option value="REAL">真实业务数据 · REAL</option>
              <option value="FAKE">演示数据 · FAKE</option>
            </select>
          </label>
          <label>
            <span>流量单位</span>
            <select v-model="state.options.sourceUnit">
              <option value="">请选择已确认的单位</option>
              <option value="B">B · 已确认是字节</option>
            </select>
          </label>
          <label>
            <span>默认 App ID（无 App 列时必填）</span>
            <input v-model="state.options.appId" type="text" placeholder="不从文件名推断" autocomplete="off">
          </label>
        </div>

        <details class="upload-mapping" open>
          <summary>CSV 字段映射 · 按文件实际列名填写</summary>
          <div class="upload-mapping-grid">
            <label><span>文件标识列</span><input v-model="state.options.mapping.fileKey" type="text" autocomplete="off"></label>
            <label><span>日期列</span><input v-model="state.options.mapping.date" type="text" autocomplete="off"></label>
            <label><span>流量列</span><input v-model="state.options.mapping.trafficBytes" type="text" autocomplete="off"></label>
            <label><span>App ID 列（可留空）</span><input v-model="state.options.mapping.appId" type="text" autocomplete="off"></label>
          </div>
          <p class="upload-hint">数据类型：文件日流量（FILE_DAILY）。默认映射适用于“文件标识、日期、当日流量（B）、AppID”四列。</p>
        </details>
      </fieldset>

      <div v-if="state.error" class="upload-error" role="alert"><strong>未确认接收</strong><p>{{ state.error }}</p></div>

      <div v-if="state.phase === 'ACCEPTED'" class="upload-receipt" aria-live="polite">
        <strong>{{ state.file ? '上传请求已接收' : '已恢复上次上传的接收记录' }}</strong>
        <p>导入任务编号（importTaskId）</p>
        <code>{{ state.importTaskId }}</code>
        <p>该编号已在当前页面保留；浏览器存储可用时也会保存在本机。导入状态待后续查询，当前不表示导入完成或数据集可用。</p>
      </div>

      <footer class="upload-actions">
        <p v-if="state.busy" aria-live="polite">正在上传，请等待接收回执…</p>
        <p v-else-if="state.phase !== 'ACCEPTED'">文件仅提交给数据导入服务，不改变下方演示榜单。</p>
        <p v-else>本次仅完成上传提交。</p>
        <button v-if="state.phase === 'ACCEPTED'" class="button button--ghost" type="button" @click="reset">选择另一份文件</button>
        <button v-else class="button button--primary" type="submit" :disabled="state.busy || !state.file">
          {{ state.busy ? '提交中…' : state.phase === 'FAILED' ? '重试上传' : '上传 CSV' }}
        </button>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.upload-panel { margin-bottom: 1.5rem; border: 1px solid rgba(122, 167, 167, 0.25); background: #0a1f27; }
.upload-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; padding: 1.5rem; border-bottom: 1px solid rgba(122, 167, 167, 0.18); }
.upload-heading h2 { margin: 0 0 0.65rem; color: #d8e9e7; font-size: 1.5rem; }
.upload-heading > div > p:last-child { margin: 0; color: #8facad; font-size: 0.85rem; line-height: 1.6; }
.upload-state { flex: 0 0 auto; padding: 0.5rem 0.7rem; color: #8facad; border: 1px solid currentColor; font-size: 0.75rem; }
.upload-state--uploading, .upload-state--accepted { color: #2ed3b7; }
.upload-state--failed { color: #ffb178; }
fieldset { min-width: 0; margin: 0; padding: 1.5rem; border: 0; }
fieldset:disabled { opacity: 0.65; }
.upload-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
label { display: grid; gap: 0.6rem; color: #b6ccca; font-size: 0.8rem; }
input, select { width: 100%; min-width: 0; min-height: 42px; padding: 0.65rem; color: #d8e9e7; border: 1px solid rgba(122, 167, 167, 0.32); border-radius: 0; background: #06151b; }
input::placeholder { color: #78979b; }
input[type='file'] { margin-top: 0.6rem; border-style: dashed; }
input[type='file']::file-selector-button { margin-right: 1rem; padding: 0.4rem 0.7rem; color: #2ed3b7; border: 1px solid rgba(46, 211, 183, 0.34); background: #0a1f27; cursor: pointer; }
.upload-filename { display: flex; flex-wrap: wrap; gap: 1rem; margin: 0.75rem 0 0; overflow-wrap: anywhere; font-family: 'Cascadia Code', monospace; font-size: 0.8rem; }
.upload-filename span { color: #8facad; }
.upload-hint { margin: 0.75rem 0 0; color: #8facad; font-size: 0.75rem; line-height: 1.7; }
.upload-options { display: grid; grid-template-columns: 1fr 1fr 1.5fr; gap: 1rem; margin-top: 1.5rem; }
.upload-mapping { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid rgba(122, 167, 167, 0.18); }
.upload-mapping summary { color: #b6ccca; font-size: 0.8rem; cursor: pointer; }
.upload-mapping-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1rem; margin-top: 1rem; }
.upload-error, .upload-receipt { margin: 0 1.5rem 1.5rem; padding: 1rem; border-left: 3px solid currentColor; background: #06151b; }
.upload-error { color: #ffb178; }
.upload-receipt { color: #2ed3b7; }
.upload-error p, .upload-receipt p { margin: 0.6rem 0 0; font-size: 0.8rem; line-height: 1.7; }
.upload-receipt p { color: #8facad; }
.upload-receipt code { display: block; margin-top: 0.5rem; overflow-wrap: anywhere; white-space: normal; color: #d8e9e7; font-size: 0.9rem; }
.upload-actions { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1rem 1.5rem; border-top: 1px solid rgba(122, 167, 167, 0.18); }
.upload-actions p { margin: 0; color: #8facad; font-size: 0.8rem; line-height: 1.6; }
@media (max-width: 900px) { .upload-options, .upload-mapping-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .upload-heading, .upload-actions { align-items: stretch; flex-direction: column; } .upload-state { align-self: flex-start; } .upload-options, .upload-mapping-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
