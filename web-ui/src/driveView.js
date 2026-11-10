// 目录视图模式：只作用于「目录」Tab，与下载/离线/回收站三页无关。
// 首次访问不写死一种：桌面默认列表（信息密度高），窄屏默认卡片（缩略图 + 大触控目标）；
// 用户手动切换后写 localStorage，后续以此为准。
export const DRIVE_VIEW_STORAGE_KEY = 'fnpak-drive-view'

export const DRIVE_VIEW_OPTIONS = [
  { key: 'list', label: '列表视图', icon: 'list' },
  { key: 'card', label: '卡片视图', icon: 'grid' },
]

const VALID_VIEWS = DRIVE_VIEW_OPTIONS.map((option) => option.key)

/** 与 Tailwind 的 md 断点保持一致：≥768px 视为桌面。 */
export function defaultDriveView() {
  try {
    if (typeof window !== 'undefined' && window.matchMedia?.('(min-width: 768px)').matches) {
      return 'list'
    }
  } catch (_) {
    /* 非浏览器环境/隐私模式：退回卡片 */
  }
  return 'card'
}

export function readDriveView() {
  try {
    const saved = localStorage.getItem(DRIVE_VIEW_STORAGE_KEY)
    if (VALID_VIEWS.includes(saved)) return saved
  } catch (_) {
    /* 隐私模式/配额满：用默认值 */
  }
  return defaultDriveView()
}

export function saveDriveView(value) {
  if (!VALID_VIEWS.includes(value)) return
  try {
    localStorage.setItem(DRIVE_VIEW_STORAGE_KEY, value)
  } catch (_) {
    /* 切换仍生效，只是不记住 */
  }
}
