<script setup>
import { inject, onMounted, reactive, ref, watch } from 'vue'
import BaseDialog from '../BaseDialog.vue'
import { alertApi } from '../../alerts.js'
import { uiContextKey } from '../../ui/context.js'

const { previewAppId } = inject(uiContextKey, { previewAppId: ref('10350') })
const routes = ref([])
const selectedAppId = ref('')
const newAppId = ref('')
const route = ref(null)
const busy = ref(false)
const routeBusy = ref(false)
const error = ref('')
const notice = ref('')
const testOpen = ref(false)
const form = reactive({
  alertsEnabled: false,
  businessName: '',
  ownerText: '',
  backupText: '',
  fixedChatId: '',
  testChatId: '',
  p1AutoCreateGroup: true,
  ackDeadlineMinutes: 5,
  remindIntervalMinutes: 15,
  maxReminderCount: 4,
  activeWindowMinutes: 60
})

watch(selectedAppId, value => {
  notice.value = ''
  error.value = ''
  if (value === '__new__') {
    route.value = null
    reset()
    return
  }
  if (value) void loadRoute(value)
})

async function loadRoutes() {
  busy.value = true
  error.value = ''
  try {
    const result = await alertApi.listRoutes()
    if (!Array.isArray(result)) throw new Error('告警路由列表响应无效。')
    routes.value = result
    const target = result.some(item => item.appId === selectedAppId.value)
      ? selectedAppId.value
      : result.some(item => item.appId === previewAppId.value)
        ? previewAppId.value
        : result[0]?.appId || '__new__'
    selectedAppId.value = target
    if (target !== '__new__') await loadRoute(target)
  } catch (cause) {
    error.value = cause.message || '告警路由加载失败。'
  } finally {
    busy.value = false
  }
}

async function loadRoute(appId) {
  if (!appId) return
  routeBusy.value = true
  error.value = ''
  try {
    const result = await alertApi.getRoute(appId)
    if (result?.appId !== appId || !Number.isInteger(result?.version)) throw new Error('告警配置响应与当前 App 不匹配。')
    route.value = result
    populate(result)
  } catch (cause) {
    error.value = cause.message || '告警配置加载失败。'
  } finally {
    routeBusy.value = false
  }
}

function populate(value) {
  Object.assign(form, {
    alertsEnabled: Boolean(value.alertsEnabled),
    businessName: value.businessName || '',
    ownerText: (value.ownerOpenIds || []).join(', '),
    backupText: (value.backupOpenIds || []).join(', '),
    fixedChatId: value.fixedChatId || '',
    testChatId: value.testChatId || '',
    p1AutoCreateGroup: Boolean(value.p1AutoCreateGroup),
    ackDeadlineMinutes: value.ackDeadlineMinutes ?? 5,
    remindIntervalMinutes: value.remindIntervalMinutes ?? 15,
    maxReminderCount: value.maxReminderCount ?? 4,
    activeWindowMinutes: value.activeWindowMinutes ?? 60
  })
}

function reset() {
  populate({
    alertsEnabled: false,
    businessName: '',
    ownerOpenIds: [],
    backupOpenIds: [],
    fixedChatId: '',
    testChatId: '',
    p1AutoCreateGroup: true,
    ackDeadlineMinutes: 5,
    remindIntervalMinutes: 15,
    maxReminderCount: 4,
    activeWindowMinutes: 60
  })
}

function ids(text) {
  return text.split(/[\s,，]+/).map(item => item.trim()).filter(Boolean)
}

async function save() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const appId = selectedAppId.value === '__new__' ? newAppId.value.trim() : selectedAppId.value
    if (!/^[A-Za-z0-9_-]{1,32}$/.test(appId)) throw new Error('AppID 须为 1 至 32 个字母、数字、下划线或连字符。')
    const body = {
      expectedVersion: route.value?.version ?? 0,
      alertsEnabled: Boolean(form.alertsEnabled),
      businessName: form.businessName.trim(),
      ownerOpenIds: ids(form.ownerText),
      backupOpenIds: ids(form.backupText),
      fixedChatId: form.fixedChatId.trim(),
      testChatId: form.testChatId.trim(),
      p1AutoCreateGroup: Boolean(form.p1AutoCreateGroup),
      ackDeadlineMinutes: Number(form.ackDeadlineMinutes),
      remindIntervalMinutes: Number(form.remindIntervalMinutes),
      maxReminderCount: Number(form.maxReminderCount),
      activeWindowMinutes: Number(form.activeWindowMinutes)
    }
    const saved = await alertApi.updateRoute(appId, body)
    route.value = saved
    selectedAppId.value = appId
    newAppId.value = ''
    populate(saved)
    notice.value = `配置已保存，版本 ${saved.version}。`
    const result = await alertApi.listRoutes()
    if (Array.isArray(result)) routes.value = result
  } catch (cause) {
    error.value = cause.message || '告警配置保存失败。'
  } finally {
    busy.value = false
  }
}

async function validate() {
  if (!route.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await alertApi.validateRoute(route.value.appId)
    notice.value = result.valid ? '固定群、测试群和负责人验证通过。' : '验证未通过。'
  } catch (cause) {
    error.value = cause.message || '告警配置验证失败。'
  } finally {
    busy.value = false
  }
}

async function sendTest() {
  if (!route.value || !form.testChatId.trim()) return
  testOpen.value = false
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await alertApi.testRoute(route.value.appId)
    notice.value = 'DEMO 测试卡片已提交到测试群；最终结果请查看发送记录。'
  } catch (cause) {
    error.value = cause.message || '测试卡片发送失败。'
  } finally {
    busy.value = false
  }
}

onMounted(loadRoutes)
</script>

<template>
  <section class="route-workspace" aria-labelledby="route-title">
    <header class="route-heading">
      <div>
        <p class="eyebrow">ROUTING CONTROL</p>
        <h2 id="route-title">业务通知配置</h2>
        <p>每个 AppID 独立决定负责人、事故群和提醒节奏。告警默认关闭。</p>
      </div>
      <div class="route-version">
        <small>配置版本</small>
        <strong>{{ route?.version ?? 'NEW' }}</strong>
      </div>
    </header>

    <div v-if="error" class="notice notice--amber" role="alert">{{ error }}</div>
    <div v-if="notice" class="notice" role="status">{{ notice }}</div>

    <div class="route-layout">
      <nav class="route-index" aria-label="AppID 告警配置">
        <button
          v-for="item in routes"
          :key="item.appId"
          type="button"
          :class="{ active: selectedAppId === item.appId }"
          @click="selectedAppId = item.appId"
        >
          <span>{{ item.appId }}</span>
          <strong>{{ item.businessName || '未命名业务' }}</strong>
          <small>{{ item.alertsEnabled ? '自动告警开启' : '自动告警关闭' }}</small>
        </button>
        <button type="button" class="route-index__new" :class="{ active: selectedAppId === '__new__' }" @click="selectedAppId = '__new__'">
          <span>+</span><strong>新增 AppID</strong><small>建立独立业务路由</small>
        </button>
      </nav>

      <form class="route-form" @submit.prevent="save">
        <div v-if="routeBusy || busy && !route" class="route-loading">正在读取配置…</div>
        <template v-else>
          <section class="form-section">
            <div class="section-number">01</div>
            <div class="section-content">
              <h3>业务与开关</h3>
              <div class="form-grid">
                <label v-if="selectedAppId === '__new__'">AppID
                  <input v-model.trim="newAppId" placeholder="例如 10350" autocomplete="off">
                </label>
                <label v-else>AppID<input :value="selectedAppId" disabled></label>
                <label>业务名称<input v-model="form.businessName" maxlength="128" placeholder="内容分发"></label>
                <label class="switch-field">
                  <input v-model="form.alertsEnabled" type="checkbox">
                  <span><strong>启用事故告警</strong><small>关闭时不创建群、不发送事故卡片</small></span>
                </label>
                <label class="switch-field">
                  <input v-model="form.p1AutoCreateGroup" type="checkbox">
                  <span><strong>P1 自动建群</strong><small>失败时回退到固定应急群</small></span>
                </label>
              </div>
            </div>
          </section>

          <section class="form-section">
            <div class="section-number">02</div>
            <div class="section-content">
              <h3>负责人和群</h3>
              <div class="form-grid">
                <label>负责人 Open ID<input v-model="form.ownerText" placeholder="ou_xxx, ou_yyy"></label>
                <label>备份负责人 Open ID<input v-model="form.backupText" placeholder="P1 建群和超时升级使用"></label>
                <label>固定应急群 chat_id<input v-model="form.fixedChatId" placeholder="oc_xxx"></label>
                <label>测试群 chat_id<input v-model="form.testChatId" placeholder="仅接收 DEMO 测试卡"></label>
              </div>
              <p class="section-help">页面不发送认领身份。负责人点击飞书卡片按钮后，由服务端验证签名和 operator_id。</p>
            </div>
          </section>

          <section class="form-section">
            <div class="section-number">03</div>
            <div class="section-content">
              <h3>提醒节奏</h3>
              <div class="form-grid form-grid--four">
                <label>认领时限（分钟）<input v-model.number="form.ackDeadlineMinutes" type="number" min="1" max="1440"></label>
                <label>提醒间隔（分钟）<input v-model.number="form.remindIntervalMinutes" type="number" min="1" max="1440"></label>
                <label>最大提醒次数<input v-model.number="form.maxReminderCount" type="number" min="1" max="50"></label>
                <label>事故合并窗口（分钟）<input v-model.number="form.activeWindowMinutes" type="number" min="1" max="10080"></label>
              </div>
            </div>
          </section>

          <footer class="route-actions">
            <button class="btn btn-primary" type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存配置' }}</button>
            <button class="btn" type="button" :disabled="busy || !route" @click="validate">验证群和负责人</button>
            <button class="btn" type="button" :disabled="busy || !route || !form.testChatId.trim()" @click="testOpen = true">发送 DEMO 测试卡</button>
          </footer>
        </template>
      </form>
    </div>

    <BaseDialog :open="testOpen" title="发送 DEMO 测试卡" width="560px" @close="testOpen = false">
      <div class="dialog-body">
        <p>目标测试群：<strong>{{ form.testChatId }}</strong></p>
        <p>这条消息标记为 DEMO，不会发送到固定应急群，也不代表事故通知成功。</p>
        <div class="detail-button-row">
          <button class="btn" type="button" @click="testOpen = false">取消</button>
          <button class="btn btn-primary" type="button" :disabled="busy" @click="sendTest">发送测试卡</button>
        </div>
      </div>
    </BaseDialog>
  </section>
</template>

<style scoped>
.route-workspace{--route-ink:#18334f;--route-line:#dce5ef;--route-blue:#176f9c;--route-teal:#1f7a68;display:flex;flex-direction:column;gap:16px}.route-heading{display:flex;justify-content:space-between;gap:24px;padding:21px 23px;color:#edf7fb;background:linear-gradient(105deg,#123349,#174c5d);border:1px solid #1e5062;border-radius:8px}.route-heading h2{font-size:24px;margin:3px 0 5px}.route-heading p{margin:0;color:#c2dce3;font-size:12px;line-height:1.7}.eyebrow{font-family:'Cascadia Code',Consolas,monospace;font-size:9px;letter-spacing:.13em;color:#7fd4d1;margin:0}.route-version{min-width:92px;align-self:center;padding:10px 14px;border:1px solid rgba(255,255,255,.18);background:rgba(255,255,255,.08);border-radius:6px;text-align:center}.route-version small{display:block;font-size:9px;color:#b9d2db}.route-version strong{display:block;font-size:23px;margin-top:2px;color:#fff}
.route-layout{display:grid;grid-template-columns:230px minmax(0,1fr);gap:16px}.route-index{display:flex;flex-direction:column;gap:8px}.route-index button{display:grid;gap:4px;text-align:left;border:1px solid var(--route-line);background:white;border-radius:6px;padding:12px 13px;color:var(--route-ink)}.route-index button:hover{border-color:#9db7ce}.route-index button.active{border-color:#56a4bf;background:#f2fafc;box-shadow:0 0 0 2px #9cd5e52a}.route-index button span{font-family:'Cascadia Code',Consolas,monospace;font-size:10px;color:#6b8ba3}.route-index button strong{font-size:12px}.route-index button small{font-size:9px;color:#91a0af}.route-index__new{border-style:dashed!important;color:#568098!important}.route-index__new span{font-size:18px!important;color:var(--route-blue)!important}
.route-form{background:white;border:1px solid var(--route-line);border-radius:7px;padding:8px 22px 20px;min-height:480px}.form-section{display:grid;grid-template-columns:42px minmax(0,1fr);gap:13px;padding:19px 0;border-bottom:1px solid #edf2f7}.section-number{width:34px;height:34px;display:grid;place-items:center;border-radius:50%;background:#edf7fa;color:#267e91;font-family:'Cascadia Code',Consolas,monospace;font-size:10px}.section-content h3{font-size:14px;color:#294963;margin:2px 0 13px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px 16px}.form-grid--four{grid-template-columns:repeat(4,minmax(0,1fr))}.form-grid label{display:grid;gap:6px;font-size:10px;color:#6e8196}.form-grid input{height:35px;font-size:11px}.switch-field{grid-template-columns:18px minmax(0,1fr)!important;align-items:center;padding:9px 10px;border:1px solid #e4ebf3;border-radius:5px;background:#fbfcfe}.switch-field input{height:auto}.switch-field strong{display:block;font-size:11px;color:#36536f}.switch-field small{display:block;font-size:9px;color:#8d9cad;margin-top:3px}.section-help{font-size:9px;color:#8e9dad;line-height:1.7;margin:11px 0 0}.route-actions{display:flex;gap:10px;flex-wrap:wrap;padding-top:17px}.route-loading{min-height:320px;display:grid;place-items:center;color:#8b9aac;font-size:11px}
@media(max-width:980px){.route-layout{grid-template-columns:1fr}.route-index{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr))}.form-grid--four{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:620px){.route-heading{flex-direction:column}.route-form{padding:6px 14px 16px}.form-section{grid-template-columns:1fr}.section-number{display:none}.form-grid,.form-grid--four{grid-template-columns:1fr}.route-actions .btn{width:100%}}
</style>
