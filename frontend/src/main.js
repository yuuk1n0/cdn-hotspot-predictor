import { createApp } from 'vue'

import App from './App.vue'
import { createAppRouter } from './router/index.js'
import './theme.css'

createApp(App).use(createAppRouter()).mount('#app')
