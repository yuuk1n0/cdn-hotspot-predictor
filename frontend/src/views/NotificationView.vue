<script setup>
import { ref } from 'vue'
import IncidentPanel from '../components/alerts/IncidentPanel.vue'
import AlertRoutePanel from '../components/alerts/AlertRoutePanel.vue'
import AlertDeliveryPanel from '../components/alerts/AlertDeliveryPanel.vue'

const activeTab = ref('incidents')
const tabs = [
  { id: 'incidents', label: '实时告警', caption: '当前事故与投递轨迹' },
  { id: 'routes', label: '业务通知配置', caption: '负责人、群和提醒节奏' },
  { id: 'deliveries', label: '发送记录', caption: '例行通知与事故证据' }
]
</script>

<template>
  <div class="notification-center" data-page="notifications">
    <header class="page-heading notification-heading">
      <div>
        <p class="heading-kicker">INCIDENT NOTIFICATION</p>
        <h1>通知中心</h1>
        <p>把线上事故送到正确的人，把每一次投递留下证据。</p>
      </div>
      <div class="heading-status">
        <span class="status-light"></span>
        <div><strong>飞书事故通道</strong><small>网页只读 · 卡片内认领</small></div>
      </div>
    </header>

    <nav class="notification-tabs" aria-label="通知中心功能">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        :class="{ active: activeTab === tab.id }"
        :aria-current="activeTab === tab.id ? 'page' : undefined"
        @click="activeTab = tab.id"
      >
        <span>{{ tab.label }}</span>
        <small>{{ tab.caption }}</small>
      </button>
    </nav>

    <IncidentPanel v-if="activeTab === 'incidents'" />
    <AlertRoutePanel v-else-if="activeTab === 'routes'" />
    <AlertDeliveryPanel v-else />
  </div>
</template>

<style scoped>
.notification-center{--center-ink:#17324f;--center-blue:#176f9c;--center-line:#dce5ef}.notification-heading{position:relative;padding:0 2px;margin-bottom:17px}.heading-kicker{font-family:'Cascadia Code',Consolas,monospace;font-size:9px;letter-spacing:.14em;color:#748da6;margin:0 0 5px}.notification-heading h1{font-size:26px;letter-spacing:-.6px;color:var(--center-ink);margin-bottom:5px}.notification-heading p{margin-bottom:0;color:#7d8fa4;font-size:11px}.heading-status{display:flex;align-items:center;gap:10px;padding:10px 13px;border:1px solid #dbe8ef;background:#f7fbfd;border-radius:6px}.status-light{width:8px;height:8px;border-radius:50%;background:#28a283;box-shadow:0 0 0 5px #28a28316}.heading-status strong,.heading-status small{display:block}.heading-status strong{font-size:10px;color:#315871}.heading-status small{font-size:9px;color:#8a9bab;margin-top:2px}
.notification-tabs{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));border:1px solid var(--center-line);border-radius:7px;overflow:hidden;background:white;margin-bottom:16px}.notification-tabs button{display:grid;gap:2px;text-align:left;border:0;border-right:1px solid #e9eff5;background:white;padding:13px 16px;min-height:58px;color:#576f89}.notification-tabs button:last-child{border-right:0}.notification-tabs button:hover{background:#f9fbfd}.notification-tabs button.active{background:linear-gradient(180deg,#edf8fc,#f6fbfd);box-shadow:inset 0 -3px var(--center-blue);color:#176b91}.notification-tabs span{font-size:12px;font-weight:650}.notification-tabs small{font-size:9px;color:#91a0b0}
@media(max-width:680px){.notification-heading{align-items:flex-start}.notification-tabs{grid-template-columns:1fr}.notification-tabs button{border-right:0;border-bottom:1px solid #e9eff5}.notification-tabs button:last-child{border-bottom:0}}
</style>
