// 目录行的单一点击语义：菜单、名称点击、整行空白点击都走这里，避免各处重复判断。
import { previewKindFor } from './preview'
import { archiveEntryState } from './archive'
import { torrentEntryState } from './torrent'

/**
 * @returns {'enter'|'video'|'preview'|'extract'|'torrent-open'|'download'|'none'}
 */
export function rowActionFor(row) {
  if (!row) return 'none'
  if (row.isFolder) return 'enter'
  if (row.isVideo) return 'video'
  const previewKind = previewKindFor(row)
  if (previewKind) return previewKind === 'video' ? 'video' : 'preview'
  if (archiveEntryState(row) === 'extractable') return 'extract'
  if (torrentEntryState(row)) return 'torrent-open'
  return 'download'
}

/** 供行内 aria-label 使用：让读屏用户知道点这一行会发生什么。 */
export function rowActionLabel(row) {
  const name = row?.name || row?.id || ''
  switch (rowActionFor(row)) {
    case 'enter': return `打开文件夹 ${name}`
    case 'video': return `播放 ${name}`
    case 'preview': return `预览 ${name}`
    case 'extract': return `解压 ${name}`
    case 'torrent-open': return `打开种子 ${name}`
    case 'download': return `下载 ${name}`
    default: return name
  }
}
