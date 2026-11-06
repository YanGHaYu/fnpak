// 目录列表排序：只作用于「目录」Tab，与下载/离线/回收站无关。
// 名称用中文自然序（数字 2 排在 10 前）；文件夹在任何维度/方向都恒定在前。
export const DRIVE_SORT_STORAGE_KEY = 'fnpak-drive-sort'

export const DRIVE_SORT_OPTIONS = [
  { key: 'time', label: '时间' },
  { key: 'name', label: '文件名' },
  { key: 'type', label: '文件类型' },
]

export const DRIVE_SORT_DIRECTIONS = [
  { key: 'asc', label: '正序' },
  { key: 'desc', label: '倒序' },
]

export const DEFAULT_DRIVE_SORT = { dimension: 'time', direction: 'desc' }

const collator = new Intl.Collator('zh-Hans-CN', { numeric: true, sensitivity: 'base' })

export function readDriveSort() {
  try {
    const saved = JSON.parse(localStorage.getItem(DRIVE_SORT_STORAGE_KEY) || 'null')
    const dimension = DRIVE_SORT_OPTIONS.some((option) => option.key === saved?.dimension)
      ? saved.dimension
      : DEFAULT_DRIVE_SORT.dimension
    const direction = DRIVE_SORT_DIRECTIONS.some((option) => option.key === saved?.direction)
      ? saved.direction
      : DEFAULT_DRIVE_SORT.direction
    return { dimension, direction }
  } catch (_) {
    return { ...DEFAULT_DRIVE_SORT }
  }
}

export function saveDriveSort(value) {
  try {
    localStorage.setItem(DRIVE_SORT_STORAGE_KEY, JSON.stringify(value))
  } catch (_) {
    /* 隐私模式/配额满：排序仍生效，只是不记住 */
  }
}

// 扩展名：最后一个「.」之后的小写串；`.gitignore`、末尾带点、无点都按无扩展名处理。
export function extensionOf(name) {
  const text = String(name || '')
  const dot = text.lastIndexOf('.')
  if (dot <= 0 || dot === text.length - 1) return ''
  return text.slice(dot + 1).toLowerCase()
}

const byId = (a, b) => String(a.id || '').localeCompare(String(b.id || ''))

const byName = (a, b) => collator.compare(String(a.name || ''), String(b.name || '')) || byId(a, b)

/** 文件夹在「文件类型」维度没有扩展名可分组：按名称排序，但仍要跟随正/倒序。 */
const byNameDirection = (direction, a, b) => {
  const sign = direction === 'asc' ? 1 : -1
  return collator.compare(String(a.name || ''), String(b.name || '')) * sign || byId(a, b)
}

/** 主键比较：direction 只反转主键，兜底键始终升序，保证结果确定。 */
function comparePrimary(dimension, direction, a, b) {
  const sign = direction === 'asc' ? 1 : -1
  if (dimension === 'time') {
    const left = a.createdAtMs
    const right = b.createdAtMs
    // 缺失值无论正序还是倒序都排在最后
    if (left == null && right == null) return 0
    if (left == null) return 1
    if (right == null) return -1
    return (left - right) * sign
  }
  if (dimension === 'name') {
    return collator.compare(String(a.name || ''), String(b.name || '')) * sign
  }
  // 文件类型：按扩展名分组；无扩展名在正序时最后、倒序时最前
  const left = extensionOf(a.name)
  const right = extensionOf(b.name)
  if (left === right) return 0
  if (left === '') return direction === 'asc' ? 1 : -1
  if (right === '') return direction === 'asc' ? -1 : 1
  return collator.compare(left, right) * sign
}

export function sortDriveItems(items, dimension, direction) {
  const source = Array.isArray(items) ? items : []
  const folders = []
  const files = []
  for (const row of source) (row.isFolder ? folders : files).push(row)
  const comparator = (a, b) => comparePrimary(dimension, direction, a, b) || byName(a, b)
  // 文件夹没有扩展名概念：按类型排序时文件夹段固定按名称，并跟随正/倒序
  folders.sort(dimension === 'type' ? (a, b) => byNameDirection(direction, a, b) : comparator)
  files.sort(comparator)
  return [...folders, ...files]
}
