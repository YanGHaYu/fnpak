<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">Piko <span class="ver">{{ state.version || '' }}</span></div>
      <div class="spacer" />
      <el-tag size="small" effect="plain" :type="healthType">{{ healthText }}</el-tag>
      <el-button text size="small" @click="toggleTheme">{{ dark ? '浅色' : '深色' }}</el-button>
      <el-button
        v-if="state.accessOk"
        text
        size="small"
        @click="router.push(route.path === '/settings' ? '/' : '/settings')"
      >
        {{ route.path === '/settings' ? '返回首页' : '设置' }}
      </el-button>
    </header>
    <main class="main">
      <SetupView v-if="state.setup.needsSetup === true" />
      <GateView v-else-if="state.accessOk !== true" />
      <router-view v-else />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import GateView from './views/GateView.vue'
import SetupView from './views/SetupView.vue'
import { refreshAccess, refreshHealth, refreshSetup, state } from './store'

const route = useRoute()
const router = useRouter()

const dark = ref(false)
let healthTimer = null

const healthText = computed(() => {
  if (!state.health) return '服务状态未知'
  return `${state.health.version} · 运行 ${state.health.uptimeSeconds}s`
})
const healthType = computed(() => (state.health ? 'success' : 'warning'))

function applyTheme(value) {
  dark.value = value
  document.documentElement.classList.toggle('dark', value)
  localStorage.setItem('piko-theme', value ? 'dark' : 'light')
}

function toggleTheme() {
  applyTheme(!dark.value)
}

onMounted(async () => {
  const saved = localStorage.getItem('piko-theme')
  applyTheme(saved ? saved === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches)
  await refreshHealth()
  await refreshSetup()
  if (state.setup.needsSetup !== true) await refreshAccess()
  healthTimer = setInterval(refreshHealth, 10000)
})

onUnmounted(() => {
  if (healthTimer) clearInterval(healthTimer)
})
</script>
