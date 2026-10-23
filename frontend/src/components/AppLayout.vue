<script setup>
import { inject, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from './AppIcon.vue'
import { uiContextKey } from '../ui/context.js'
import { appOptions } from '../ui/mock-data.js'
const route = useRoute()
const { previewAppId, datasetBusy, datasetError, refreshDatasets } = inject(uiContextKey, {
  previewAppId: ref('10350'), datasetBusy: ref(false), datasetError: ref(''), refreshDatasets: () => {}
})
const helpOpen = ref(false)
const links = [
  { path: '/overview', title: '业务总览', icon: 'overview' },
  { path: '/prediction', title: '热点预测', icon: 'chart' },
  { path: '/backtest', title: '模型回测', icon: 'backtest' },
  { path: '/compression', title: '压缩降本', icon: 'compression' },
  { path: '/notifications', title: '通知中心', icon: 'bell' },
  { path: '/agent', title: '智能 Agent', icon: 'agent' }
]
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳到主内容</a>
    <header class="system-header">
      <RouterLink to="/overview" class="system-brand" aria-label="CDN 系统首页">
        <svg class="brand-cube" width="34" height="38" viewBox="0 0 34 38" aria-hidden="true"><path d="m17 1 16 9-16 9L1 10Z" fill="#65cfff"/><path d="m1 12 15 9v16L1 28Z" fill="#1687ff"/><path d="m18 21 15-9v16l-15 9Z" fill="#35aaff"/><path d="m17 1 16 9-16 9L1 10Z" fill="none" stroke="#a3e4ff"/></svg>
        <span>CDN 热点文件动态预测与压缩降本系统</span>
      </RouterLink>
      <div class="header-tools">
        <label class="app-switch" title="全局 AppID 切换"><span>AppID</span><select v-model="previewAppId"><option v-for="app in appOptions" :key="app.id" :value="app.id">{{ app.id }}</option></select></label>
        <button v-if="datasetError" class="header-icon" title="重试加载真实数据集" @click="refreshDatasets">!</button>
        <span v-if="datasetBusy" class="app-switch-state">加载数据集中</span>
        <span class="environment-badge"><i></i>本地前端预览</span>
        <button class="header-icon" aria-label="界面说明" :aria-expanded="helpOpen" @click="helpOpen = !helpOpen"><AppIcon name="help" /></button>
        <span class="header-divider"></span><span class="avatar">UI</span><span class="header-user">预览工作台</span>
      </div>
      <div v-if="helpOpen" class="header-popover" role="status">热点预测的界面样例带 UI MOCK 标识；通知中心与智能 Agent 的结果从业务接口读取。发送通知须先预览并确认目标与内容。</div>
    </header>
    <aside class="sidebar">
      <div class="sidebar-caption">运营工作台 <span>WORKSPACE</span></div>
      <nav aria-label="主导航">
        <RouterLink v-for="link in links" :key="link.path" :to="link.path" :title="link.title" class="nav-item"><AppIcon :name="link.icon" :size="21" /><span>{{ link.title }}</span><span class="nav-active-dot" /></RouterLink>
      </nav>
      <div class="sidebar-footer"><div class="sidebar-grid" aria-hidden="true"></div><span class="sidebar-footer-mark"><AppIcon name="cloud" :size="26" /></span><strong>从热点洞察到降本决策</strong><small>CDN OPERATIONS CONSOLE</small><span class="sidebar-version">业务工作台</span></div>
    </aside>
    <main id="main-content" class="main-content">
      <div class="breadcrumb"><span>工作台</span><AppIcon name="chevron" :size="12" /><strong>{{ route.meta.title }}</strong><span class="breadcrumb-end">CDN 流量运营</span></div>
      <slot />
      <footer class="workspace-footer">CDN 热点预测与压缩降本系统 <span>界面样例与接口数据均标明来源</span></footer>
    </main>
  </div>
</template>

<style scoped>
.app-switch{display:flex;align-items:center;gap:6px;font-size:10px;color:#7d91aa}.app-switch select{height:28px;border:1px solid #dbe5f1;border-radius:4px;background:#fff;color:#35577f;padding:0 7px;font-size:11px}.app-switch-state{font-size:9px;color:#91a4bb}.header-icon{font-weight:700}
</style>
