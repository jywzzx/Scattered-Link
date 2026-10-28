import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as Icons from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@/styles/index.scss'

import App from './App.vue'
import router from './router'
import { pinia } from './stores'

const app = createApp(App)

// 图标全量注册：桌面端体积不敏感，换来模板里直接写 <el-icon><Connection /></el-icon>
for (const [name, comp] of Object.entries(Icons)) {
  app.component(name, comp)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn, size: 'default', zIndex: 2000 })

app.mount('#app')
