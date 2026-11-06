// 主题三态（跟随系统 / 浅色 / 深色）。首屏由 index.html 的内联脚本先行应用，这里负责运行期状态与系统监听。
import { computed, reactive } from 'vue'

export const THEME_KEY = 'fnpak-theme'
export const LEGACY_THEME_KEY = 'piko-theme'
export const THEME_MODES = ['system', 'light', 'dark']
export const META_COLORS = { light: '#f5f6f8', dark: '#0e141b' }

let mediaQuery = null
let started = false

/** 优先读新键（含 system），再回退旧键（只有 light/dark），都没有则跟随系统。 */
export function readThemeMode() {
  try {
    const saved = localStorage.getItem(THEME_KEY)
    if (THEME_MODES.includes(saved)) return saved
    const legacy = localStorage.getItem(LEGACY_THEME_KEY)
    if (legacy === 'light' || legacy === 'dark') return legacy
  } catch (_) {
    // 隐私模式下 localStorage 可能不可用，按默认值走
  }
  return 'system'
}

export function systemPrefersDark() {
  if (mediaQuery) return mediaQuery.matches
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  } catch (_) {
    return false
  }
}

/** 把有效主题写到根元素，并让 meta theme-color 与之一致。 */
export function applyResolvedTheme(dark) {
  document.documentElement.classList.toggle('dark', dark)
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', dark ? META_COLORS.dark : META_COLORS.light)
}

export const themeState = reactive({
  mode: readThemeMode(),
  systemDark: systemPrefersDark(),
})

export const isDark = computed(() =>
  themeState.mode === 'system' ? themeState.systemDark : themeState.mode === 'dark',
)

/** 用户显式选择时持久化；跟随系统不写存储，避免把系统偏好固化成显式值。 */
export function setThemeMode(mode) {
  if (!THEME_MODES.includes(mode)) return
  themeState.mode = mode
  try {
    localStorage.setItem(THEME_KEY, mode)
  } catch (_) {
    // 存不下也不影响本次会话
  }
  applyResolvedTheme(isDark.value)
}

export function toggleTheme() {
  setThemeMode(isDark.value ? 'light' : 'dark')
}

/** 幂等初始化：注册系统偏好监听，之后切换立即生效。 */
export function initTheme() {
  if (started || typeof window === 'undefined') return
  started = true
  try {
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  } catch (_) {
    mediaQuery = null
  }
  if (mediaQuery) {
    mediaQuery.addEventListener('change', (event) => {
      themeState.systemDark = event.matches
      // 已显式选过 light/dark 的用户不被系统覆盖
      if (themeState.mode === 'system') applyResolvedTheme(isDark.value)
    })
  }
  applyResolvedTheme(isDark.value)
}
