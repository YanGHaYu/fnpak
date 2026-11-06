<template>
  <div class="min-h-dvh bg-bg text-ink">
    <header class="sticky top-0 z-30 border-b border-line bg-surface/85 backdrop-blur">
      <div class="mx-auto flex h-14 w-full max-w-[1280px] items-center gap-2 px-3 md:h-16 md:gap-4 md:px-6">
        <div class="flex min-w-0 items-center gap-2">
          <span class="shrink-0 text-base font-semibold tracking-tight md:text-lg">FNPak</span>
          <span v-if="state.version" class="rounded-full bg-surface2 px-1.5 py-0.5 font-mono text-[10px] text-muted">
            {{ state.version }}
          </span>
        </div>

        <!-- 桌面 / 平板：顶部 Tab -->
        <nav v-if="showNav" class="hidden min-w-0 flex-1 items-center gap-1 md:flex" aria-label="主导航">
          <router-link
            v-for="item in navItems"
            :key="item.key"
            :to="item.to"
            class="relative flex min-h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md px-2.5 text-sm font-medium transition-colors lg:px-3"
            :class="activeTab === item.key ? 'bg-brand-soft text-brand' : 'text-muted hover:bg-surface2 hover:text-ink'"
          >
            <AppIcon :name="item.icon" :size="16" />
            <span>{{ item.label }}</span>
            <span
              v-if="item.count"
              class="ml-0.5 rounded-full bg-surface3 px-1.5 text-[10px] font-semibold text-ink"
            >{{ item.count > 99 ? '99+' : item.count }}</span>
          </router-link>
        </nav>
        <div v-else class="flex-1" />

        <div class="flex items-center gap-1">
          <span
            class="hidden items-center gap-1.5 whitespace-nowrap rounded-full border border-line px-2 py-1 text-xs lg:flex"
            :class="state.health ? 'text-success' : 'text-warning'"
            :title="healthText"
          >
            <span class="h-1.5 w-1.5 rounded-full" :class="state.health ? 'bg-success' : 'bg-warning'" />
            {{ healthText }}
          </span>
          <AppIconButton
            :icon="isDark ? 'sun' : 'moon'"
            :label="isDark ? '切换到浅色' : '切换到深色'"
            @click="toggleTheme"
          />
          <router-link
            v-if="showNav"
            :to="route.path === '/settings' ? '/' : '/settings'"
            class="inline-flex h-11 w-11 items-center justify-center rounded-md text-muted transition-colors hover:bg-surface2 hover:text-ink md:h-8 md:w-8"
            :aria-label="route.path === '/settings' ? '返回首页' : '设置'"
            :title="route.path === '/settings' ? '返回首页' : '设置'"
          >
            <AppIcon :name="route.path === '/settings' ? 'home' : 'settings'" :size="18" />
          </router-link>
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-[1280px] px-3 py-3 md:px-6 md:py-5" :class="showNav ? 'pb-[calc(var(--tabbar)+env(safe-area-inset-bottom)+1rem)] md:pb-8' : ''">
      <SetupView v-if="state.setup.needsSetup === true" />
      <GateView v-else-if="state.accessOk !== true" />
      <router-view v-else />
    </main>

    <!-- 移动端：底部 Tab（安全区 padding） -->
    <nav
      v-if="showNav"
      class="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface/95 backdrop-blur safe-bottom md:hidden"
      aria-label="底部导航"
    >
      <div class="grid grid-cols-4">
        <router-link
          v-for="item in navItems"
          :key="item.key"
          :to="item.to"
          class="relative flex min-h-[56px] flex-col items-center justify-center gap-0.5 text-xs"
          :class="activeTab === item.key ? 'text-brand' : 'text-muted'"
        >
          <AppIcon :name="item.icon" :size="20" />
          <span>{{ item.label }}</span>
          <span
            v-if="item.count"
            class="absolute right-[22%] top-1.5 min-w-4 rounded-full bg-danger-strong px-1 text-[10px] font-semibold leading-4 text-white"
          >{{ item.count > 99 ? '99+' : item.count }}</span>
        </router-link>
      </div>
    </nav>

    <AppToast />
    <AppConfirm />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppConfirm from './components/ui/AppConfirm.vue'
import AppIcon from './components/ui/AppIcon.vue'
import AppIconButton from './components/ui/AppIconButton.vue'
import AppToast from './components/ui/AppToast.vue'
import GateView from './views/GateView.vue'
import SetupView from './views/SetupView.vue'
import { tabCounts } from './counts'
import { initTheme, isDark, toggleTheme } from './theme'
import { refreshAccess, refreshHealth, refreshSetup, state } from './store'

const route = useRoute()
const router = useRouter()

let healthTimer = null

const showNav = computed(() => state.accessOk === true && state.setup.needsSetup !== true)
const activeTab = computed(() => {
  if (route.path === '/trash') return 'trash'
  const tab = String(route.query.tab || '')
  return ['downloads', 'offline'].includes(tab) ? tab : 'drive'
})
const navItems = computed(() => [
  { key: 'drive', label: '目录', icon: 'folder', to: { path: '/' }, count: 0 },
  { key: 'downloads', label: '本机下载', icon: 'download', to: { path: '/', query: { tab: 'downloads' } }, count: tabCounts.downloads },
  { key: 'offline', label: '离线任务', icon: 'clock', to: { path: '/', query: { tab: 'offline' } }, count: tabCounts.offline },
  { key: 'trash', label: '回收站', icon: 'trash', to: { path: '/trash' }, count: tabCounts.trash },
])
const healthText = computed(() => {
  if (!state.health) return '服务状态未知'
  return `${state.health.version} · 运行 ${state.health.uptimeSeconds}s`
})

onMounted(async () => {
  // 首屏已由 index.html 内联脚本应用；这里注册系统偏好监听（system 模式下运行期即时生效）
  initTheme()
  await refreshHealth()
  await refreshSetup()
  if (state.setup.needsSetup !== true) await refreshAccess()
  healthTimer = setInterval(refreshHealth, 10000)
})

onUnmounted(() => {
  if (healthTimer) clearInterval(healthTimer)
})
</script>
