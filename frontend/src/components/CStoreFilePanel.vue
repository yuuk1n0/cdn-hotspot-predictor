<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createFileAccess, fetchFileMetadata } from '../files.js'
import { formatBytes } from '../top50.js'

const props = defineProps({ file: { type: Object, required: true }, run: { type: Object, required: true }, action: String })
const access = createFileAccess()
const { state } = access
const metadata = ref(null)
const metadataError = ref('')
const loading = ref(false)
let generation = 0
const mediaType = computed(() => {
  const mime = metadata.value?.contentType || ''
  if (mime.startsWith('image/')) return 'image'
  if (mime.startsWith('video/')) return 'video'
  if (mime.startsWith('audio/')) return 'audio'
  return null
})
async function loadMetadata() {
  const requestId = generation
  metadataError.value = ''
  loading.value = true
  try {
    const [item] = await fetchFileMetadata(props.run, [props.file.fileKey])
    if (requestId !== generation) return
    metadata.value = item
    if (item.status !== 'READY') metadataError.value = 'CStore 未能查询到此文件的信息，大小不可用。'
  } catch (error) { if (requestId === generation) metadataError.value = error.message }
  finally { if (requestId === generation) loading.value = false }
}
watch(() => [props.run.runId, props.file.fileKey, props.action], () => {
  generation++
  metadata.value = null
  access.select(props.run, props.file.fileKey)
  loadMetadata()
  if (props.action === 'preview' || props.action === 'download') access.request(props.action === 'preview' ? 'PREVIEW' : 'DOWNLOAD')
}, { immediate: true })
function mediaFailed() { state.url = ''; state.error = '浏览器无法加载此文件，链接可能失效或格式不受支持。可重新获取地址或下载查看。' }
onBeforeUnmount(() => { generation++; access.clear() })
</script>

<template>
  <section class="cstore-panel" aria-label="CStore 文件访问">
    <div class="detail-grid">
      <div>
        <div class="cstore-preview">
          <template v-if="state.url && state.purpose === 'PREVIEW'">
            <img v-if="mediaType === 'image'" :src="state.url" alt="当前文件预览" referrerpolicy="no-referrer" @error="mediaFailed">
            <video v-else-if="mediaType === 'video'" :src="state.url" controls preload="metadata" referrerpolicy="no-referrer" @error="mediaFailed" />
            <audio v-else-if="mediaType === 'audio'" :src="state.url" controls preload="metadata" @error="mediaFailed" />
            <p v-else>已取得访问地址。{{ loading ? '正在查询文件类型…' : '当前文件类型无法在页面内预览，可打开文件或下载查看。' }}</p>
          </template>
          <p v-else>{{ state.busy ? '正在获取访问地址…' : '点击在线预览或下载，按需获取文件访问地址。' }}</p>
        </div>
        <div class="detail-button-row">
          <button class="btn" :disabled="state.busy" @click="access.request('PREVIEW')">{{ state.busy && state.purpose === 'PREVIEW' ? '获取中…' : '在线预览 / 重新获取' }}</button>
          <button class="btn" :disabled="state.busy" @click="access.request('DOWNLOAD')">获取下载地址</button>
          <a v-if="state.url" class="btn" :href="state.url" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer" :download="state.purpose === 'DOWNLOAD' ? '' : undefined">{{ state.purpose === 'DOWNLOAD' ? '下载文件 / 打开保存' : '新窗口打开文件' }}</a>
        </div>
        <p class="cstore-help">每次操作重新获取地址。下载保存方式取决于文件服务和浏览器；关闭详情后清除页面中的地址。</p>
        <p v-if="state.error" class="notice notice--amber" role="alert">{{ state.error }}</p>
      </div>
      <div>
        <h3 class="subheading">实时文件信息</h3>
        <dl class="detail-info">
          <dt>AppID</dt><dd>{{ run.appId }}</dd>
          <dt>原始文件标识</dt><dd class="mono">{{ file.fileKey }}</dd>
          <dt>文件大小</dt><dd>{{ loading ? '查询中…' : metadata?.status === 'READY' ? formatBytes(metadata.fileSizeBytes) : '不可用' }}<small v-if="metadata?.status === 'READY'">{{ metadata.fileSizeBytes }} B</small></dd>
          <dt>文件类型</dt><dd>{{ metadata?.contentType || '未知' }}</dd>
          <dt>信息来源</dt><dd>{{ metadata?.dataSource === 'CSTORE_TEST' ? 'CSTORE_TEST · 测试服务' : metadata ? 'CStore 实时查询' : '尚无可用查询结果' }}</dd>
          <dt>查询时间</dt><dd>{{ metadata?.queriedAt || '—' }}</dd>
        </dl>
        <p v-if="metadataError" class="notice notice--amber" role="alert">{{ metadataError }}</p>
        <button class="btn" :disabled="loading" @click="loadMetadata">刷新实际大小</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.cstore-preview{min-height:210px;display:flex;align-items:center;justify-content:center;background:#eef3f8;border-radius:5px;padding:12px;color:#70839c;text-align:center}.cstore-preview img,.cstore-preview video{max-width:100%;max-height:340px}.cstore-preview audio{max-width:100%}.cstore-help{font-size:11px;line-height:1.7;color:#7d8ea5}.detail-button-row{flex-wrap:wrap}.detail-info small{display:block;margin-top:5px}a.btn{text-decoration:none}
</style>
