<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from './AppIcon.vue'
const route = useRoute()
const helpOpen = ref(false)
const links = [
  { path: '/overview', title: '业务总览', icon: 'overview' },
  { path: '/prediction', title: '热点预测', icon: 'chart' },
  { path: '/backtest', title: '模型回测', icon: 'backtest' },
  { path: '/compression', title: '压缩降本', icon: 'compression' },
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
        <span class="environment-badge"><i></i>本地前端预览</span>
        <button class="header-icon" aria-label="界面说明" :aria-expanded="helpOpen" @click="helpOpen = !helpOpen"><AppIcon name="help" /></button>
        <span class="header-divider"></span><span class="avatar">UI</span><span class="header-user">预览工作台</span>
      </div>
      <div v-if="helpOpen" class="header-popover" role="status">本阶段为前端 UI 重构。UI Mock 使用静态样例；已有上传和预测功能位于热点预测的“接口数据”区域。</div>
    </header>
    <aside class="sidebar">
      <div class="sidebar-caption">运营工作台 <span>WORKSPACE</span></div>
      <nav aria-label="主导航">
        <RouterLink v-for="link in links" :key="link.path" :to="link.path" :title="link.title" class="nav-item"><AppIcon :name="link.icon" :size="21" /><span>{{ link.title }}</span><span class="nav-active-dot" /></RouterLink>
      </nav>
      <div class="sidebar-footer"><div class="sidebar-grid" aria-hidden="true"></div><span class="sidebar-footer-mark"><AppIcon name="cloud" :size="26" /></span><strong>从热点洞察到降本决策</strong><small>CDN OPERATIONS CONSOLE</small><span class="sidebar-version">前端 UI 预览 · v0.1</span></div>
    </aside>
    <main id="main-content" class="main-content">
      <div class="breadcrumb"><span>工作台</span><AppIcon name="chevron" :size="12" /><strong>{{ route.meta.title }}</strong><span class="breadcrumb-end">CDN 流量运营</span></div>
      <slot />
      <footer class="workspace-footer">CDN 热点预测与压缩降本系统 <span>页面演示与接口数据独立标识</span></footer>
    </main>
  </div>
</template>
