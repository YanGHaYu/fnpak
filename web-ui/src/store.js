import { reactive } from 'vue'
import { api, setAccessLostHandler, setErrorLogger } from './api'

export const state = reactive({
  // null = 还没探明；false = 需要口令（页面显示口令卡）
  accessOk: null,
  accessMessage: '',
  health: null,
  version: '',
  auth: { authenticated: false, initializing: false, account: '', displayName: '' },
  // 前端侧最近错误，设置页的「诊断」区块直接展示
  logs: [],
})

export function markAccessLost(message) {
  state.accessOk = false
  state.accessMessage = message || ''
}

setAccessLostHandler(markAccessLost)

setErrorLogger((entry) => {
  state.logs.unshift(entry)
  if (state.logs.length > 40) state.logs.pop()
})

export async function refreshAccess() {
  const result = await api('/api/access/state')
  if (result.ok && result.data?.authenticated) {
    state.accessOk = true
    state.accessMessage = ''
    return true
  }
  state.accessOk = false
  return false
}

export async function refreshHealth() {
  const result = await api('/api/health')
  if (result.ok && result.data?.ok) {
    state.health = result.data
    state.version = result.data.version || ''
  } else {
    state.health = null
  }
}

export async function refreshAuth() {
  const result = await api('/api/auth/state')
  if (result.ok && result.data) {
    state.auth = result.data
  } else {
    state.auth = { authenticated: false, initializing: false, account: '', displayName: '' }
  }
  return state.auth
}
