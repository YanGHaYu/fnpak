// 统一请求封装：同源 Cookie 会话；后端错误结构固定为 {"error":{"code","message"}}。
// 口令失效（401 access_denied）在一处收敛到口令卡，避免每个调用点各写一遍。
let accessLostHandler = () => {}
let errorLogger = () => {}

export function setAccessLostHandler(handler) {
  accessLostHandler = handler
}

export function setErrorLogger(handler) {
  errorLogger = handler
}

export async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    ...options,
  })
  let data = null
  if (response.status !== 204) {
    const text = await response.text()
    if (text) {
      try {
        data = JSON.parse(text)
      } catch (_) {
        data = null
      }
    }
  }
  if (response.status === 401 && data?.error?.code === 'access_denied') {
    accessLostHandler('访问口令已失效，请重新输入')
  } else if (!response.ok) {
    // 故障排查入口靠这份前端侧的最近错误，口令卡之外的失败都记一条
    errorLogger({
      time: new Date().toLocaleTimeString(),
      path,
      status: response.status,
      code: data?.error?.code || '',
      message: data?.error?.message || '',
    })
  }
  return { ok: response.ok, status: response.status, data }
}

export const errorText = (result) =>
  result?.data?.error?.message || `请求失败（HTTP ${result?.status ?? '—'}）`

export const errorDetail = (result) => {
  const code = result?.data?.error?.code
  return code ? `${errorText(result)} [${code}]` : errorText(result)
}

export function formatSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

export const formatSpeed = (bytes) => (bytes > 0 ? `${formatSize(bytes)}/s` : '—')
