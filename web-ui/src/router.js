import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import SettingsView from './views/SettingsView.vue'
import { refreshAccess, state } from './store'

// 单页两条路由：`/` 工作台，`/settings` 设置。
// 打包时用 history 模式，服务端对所有非 /api 路径回 index.html 做兜底。
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/settings', name: 'settings', component: SettingsView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  // 每次进入设置页都确认一次访问门：口令改过或会话过期时直接退回首页口令卡
  if (to.name === 'settings' && state.accessOk !== true) {
    if (!(await refreshAccess())) return { name: 'home' }
  }
  return true
})

export default router
