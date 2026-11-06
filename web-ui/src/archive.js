// 在线解压的前端契约：密码只放在 POST body，不进 URL；轮询任务状态走 /api/archive/tasks/{id}。
import { api, errorText } from './api'

const EXTRACTABLE_EXT = new Set(['zip', 'rar', '7z'])
// 与服务端 isArchiveVolume 保持一致：这些名字一律不给解压入口
const VOLUME_PATTERNS = [
  /\.part[0-9]+\.rar$/i,
  /\.(7z|zip|rar)\.[0-9]{3}$/i,
  /\.[zr][0-9]{2}$/i,
]

export function archiveNameState(name) {
  const text = String(name || '')
  const dot = text.lastIndexOf('.')
  const ext = dot <= 0 ? '' : text.slice(dot + 1).toLowerCase()
  if (!EXTRACTABLE_EXT.has(ext)) return 'unsupported'
  if (VOLUME_PATTERNS.some((pattern) => pattern.test(text))) return 'volume'
  return 'extractable'
}

export function archiveEntryState(item) {
  if (!item || item.isFolder) return 'unsupported'
  return archiveNameState(item.name)
}

export function archiveList(fileId, password = '', path = '') {
  return api('/api/archive/list', {
    method: 'POST',
    body: JSON.stringify({ fileId, password, path }),
  })
}

export function archiveExtract(fileId, password = '', paths = []) {
  return api('/api/archive/extract', {
    method: 'POST',
    body: JSON.stringify({ fileId, password, paths }),
  })
}

export function archiveTask(taskId) {
  return api(`/api/archive/tasks/${encodeURIComponent(taskId)}`)
}

export const archiveErrorCode = (result) => result?.data?.error?.code || ''
export const archiveErrorText = (result) => errorText(result)
