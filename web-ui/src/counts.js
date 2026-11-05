import { reactive } from 'vue'

// 四个 Tab 的角标数字：由各页面在拿到真实数据后写入，AppShell 只负责展示。
export const tabCounts = reactive({
  downloads: 0,
  offline: 0,
  trash: 0,
})
