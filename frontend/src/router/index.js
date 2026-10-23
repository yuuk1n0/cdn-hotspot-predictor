import { createRouter, createWebHistory } from 'vue-router'
import OverviewView from '../views/OverviewView.vue'
import PredictionView from '../views/PredictionView.vue'
import BacktestView from '../views/BacktestView.vue'
import CompressionView from '../views/CompressionView.vue'
import AgentView from '../views/AgentView.vue'
import NotificationView from '../views/NotificationView.vue'

export const routes = [
  { path: '/', redirect: to => ({ path: to.query.taskId || to.query.runId || ['#prediction', '#top50'].includes(to.hash) ? '/prediction' : '/overview', query: to.query, hash: to.hash }) },
  { path: '/overview', name: 'overview', component: OverviewView, meta: { title: '业务总览' } },
  { path: '/prediction', name: 'prediction', component: PredictionView, meta: { title: '热点预测' } },
  { path: '/backtest', name: 'backtest', component: BacktestView, meta: { title: '模型回测与比较' } },
  { path: '/compression', name: 'compression', component: CompressionView, meta: { title: '压缩降本决策' } },
  { path: '/notifications', name: 'notifications', component: NotificationView, meta: { title: '通知中心' } },
  { path: '/agent', name: 'agent', component: AgentView, meta: { title: 'CDN 智能运营助手' } },
  { path: '/:pathMatch(.*)*', redirect: '/overview' }
]

export const createAppRouter = (history = createWebHistory()) => createRouter({
  history, routes,
  scrollBehavior: to => ['#prediction','#top50'].includes(to.hash) ? { el: to.hash, top: 84 } : { top: 0 }
})
