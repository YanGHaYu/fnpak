// 种子的前端契约：打开只读取清单（服务端不落盘、不入库），加入离线复用 /api/links/save。
import { api, errorText } from './api'

const TORRENT_EXT = new Set(['torrent'])

function extensionOf(name) {
  const text = String(name || '')
  const dot = text.lastIndexOf('.')
  return dot <= 0 ? '' : text.slice(dot + 1).toLowerCase()
}

export function isTorrentName(name) {
  return TORRENT_EXT.has(extensionOf(name))
}

/** 目录行是否给「打开 / 直接离线」入口；mime 只作为扩展名缺失时的兜底。 */
export function torrentEntryState(item) {
  if (!item || item.isFolder) return false
  if (isTorrentName(item.name)) return true
  return /bittorrent/i.test(String(item.mimeType || ''))
}

export function openTorrent(fileId) {
  return api('/api/links/torrent', {
    method: 'POST',
    body: JSON.stringify({ fileId }),
  })
}

export function saveOfflineLink(link, parentId = '') {
  return api('/api/links/save', {
    method: 'POST',
    body: JSON.stringify({ link, parentId }),
  })
}

export const torrentErrorCode = (result) => result?.data?.error?.code || ''

const FALLBACK_TEXT = {
  torrent_fetch_failed: '无法获取种子文件，请稍后重试',
  not_torrent: '这个文件不是 .torrent 种子',
  torrent_too_large: '种子文件超过 10MB，无法解析',
  torrent_v2_unsupported: '暂不支持 BT v2 种子',
  torrent_too_many_files: '种子内的文件数量超过上限',
  torrent_invalid: '种子文件已损坏或格式不正确',
  missing_file_id: '缺少文件 ID',
  invalid_body: '请求体格式不正确',
}

export function torrentErrorText(result) {
  const code = torrentErrorCode(result)
  const message = result?.data?.error?.message
  if (message) return message
  return FALLBACK_TEXT[code] || errorText(result)
}
