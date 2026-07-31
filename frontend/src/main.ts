import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from '@/App.vue'
import { router } from '@/router'
import { initTheme } from '@/theme'
import '@/styles/tokens.css'

// 先定主题再挂载，避免应用渲染出来之后再刷一次配色
initTheme()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
