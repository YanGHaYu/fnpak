// 在线预览的类型判定与文本读取：只读媒体流，不做转码、不做缩略图。
// Markdown 用 marked 解析后交给 DOMPurify 清理，内联 HTML 一律不执行。
import DOMPurify from 'dompurify'
import { marked } from 'marked'

/** 文本/Markdown 预览最多读 1MB，避免把超大日志整份拉进浏览器。 */
export const PREVIEW_MAX_BYTES = 1024 * 1024

const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico', 'avif'])
const AUDIO_EXT = new Set(['mp3', 'wav', 'ogg', 'oga', 'flac', 'aac', 'm4a', 'opus', 'weba'])
// mkv/avi/wmv 等容器浏览器支持有限：仍给入口，播放失败时回退到下载
const VIDEO_EXT = new Set(['mp4', 'webm', 'ogv', 'mov', 'm4v', 'mkv', 'ts'])
const MARKDOWN_EXT = new Set(['md', 'markdown', 'mdown', 'mkd'])
const TEXT_EXT = new Set([
  'txt', 'log', 'text', 'csv', 'tsv', 'json', 'jsonl', 'xml', 'yaml', 'yml', 'toml', 'ini', 'conf', 'cfg',
  'properties', 'env', 'gradle', 'srt', 'ass', 'vtt', 'nfo',
  'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'vue', 'svelte', 'py', 'java', 'kt', 'kts', 'c', 'h', 'hpp', 'cpp',
  'cs', 'go', 'rs', 'rb', 'php', 'lua', 'pl', 'sh', 'bash', 'zsh', 'bat', 'cmd', 'ps1', 'sql',
  'html', 'htm', 'css', 'scss', 'less', 'diff', 'patch', 'gitignore', 'dockerfile', 'makefile',
])

/** 文件名扩展名：`a.tar.gz` 取 `gz`；没有扩展名或点开头（.bashrc）时为空。 */
export function previewExtension(name) {
  const text = String(name || '')
  const dot = text.lastIndexOf('.')
  if (dot <= 0 || dot === text.length - 1) return ''
  return text.slice(dot + 1).toLowerCase()
}

const IMAGE_MIME = /^image\//i
const AUDIO_MIME = /^audio\//i
const VIDEO_MIME = /^video\//i
const TEXT_MIME = /^(text\/|application\/(json|xml|yaml|x-yaml|javascript|x-javascript|x-sh|x-httpd-php|toml|sql|rtf|x-latex))/i

/**
 * 判定预览方式；扩展名优先（PikPak 的 mime 常是 octet-stream），再按 mime 兜底。
 * 返回 image / audio / video / pdf / markdown / text / null（null 表示只给下载）。
 */
export function previewKindFor(item) {
  if (!item || item.isFolder) return null
  const ext = previewExtension(item.name)
  if (MARKDOWN_EXT.has(ext)) return 'markdown'
  if (ext === 'pdf') return 'pdf'
  if (IMAGE_EXT.has(ext)) return 'image'
  if (AUDIO_EXT.has(ext)) return 'audio'
  if (VIDEO_EXT.has(ext)) return 'video'
  if (TEXT_EXT.has(ext)) return 'text'

  const mime = String(item.mimeType || '').toLowerCase()
  if (mime === 'application/pdf') return 'pdf'
  if (mime.startsWith('text/markdown')) return 'markdown'
  if (IMAGE_MIME.test(mime)) return 'image'
  if (AUDIO_MIME.test(mime)) return 'audio'
  if (VIDEO_MIME.test(mime)) return 'video'
  if (TEXT_MIME.test(mime)) return 'text'
  return null
}

export const PREVIEW_KIND_LABEL = {
  image: '图片',
  audio: '音频',
  video: '视频',
  pdf: 'PDF',
  markdown: 'Markdown',
  text: '纯文本',
}

function totalFromRange(header) {
  const match = String(header || '').match(/\/\s*(\d+)\s*$/)
  return match ? Number(match[1]) : null
}

/**
 * 同源转发读取文件头部：强制 `?mode=proxy`，主动 Range 只取前 1MB。
 * 上游若忽略 Range 回 200，也在读满 1MB 后取消连接，不把整份文件读进来。
 */
export async function fetchTextPreview(id, signal, maxBytes = PREVIEW_MAX_BYTES) {
  const response = await fetch(`/api/media/${encodeURIComponent(id)}/stream?mode=proxy`, {
    headers: { Range: `bytes=0-${maxBytes - 1}` },
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  })
  if (!response.ok) {
    throw new Error(`读取文件内容失败（HTTP ${response.status}）`)
  }

  const headerTotal = totalFromRange(response.headers.get('content-range'))
  const lengthHeader = Number(response.headers.get('content-length'))
  let totalBytes = headerTotal ?? (Number.isFinite(lengthHeader) && lengthHeader > 0 ? lengthHeader : null)
  let truncated = headerTotal != null && headerTotal > maxBytes

  const chunks = []
  let received = 0
  const reader = response.body?.getReader()
  if (reader) {
    try {
      while (received < maxBytes) {
        const { done, value } = await reader.read()
        if (done) break
        if (!value || !value.length) continue
        const room = maxBytes - received
        if (value.length > room) {
          chunks.push(value.subarray(0, room))
          received += room
          truncated = true
          break
        }
        chunks.push(value)
        received += value.length
      }
    } finally {
      // 主动中断剩余字节：Range 被忽略时也不继续下载
      if (received >= maxBytes) runCatchingCancel(reader)
    }
  } else {
    const buffer = await response.arrayBuffer()
    const view = new Uint8Array(buffer)
    chunks.push(view.subarray(0, maxBytes))
    received = Math.min(view.length, maxBytes)
    if (view.length > maxBytes) truncated = true
    totalBytes = totalBytes ?? view.length
  }

  if (totalBytes == null) totalBytes = received
  if (totalBytes > maxBytes) truncated = true

  const bytes = new Uint8Array(received)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.length
  }
  return { bytes, totalBytes, truncated }
}

function runCatchingCancel(reader) {
  try {
    reader.cancel()
  } catch (_) {
    // 已关闭或不可取消：忽略
  }
}

/** 空字节或控制字符比例过高 → 视为二进制，不预览。 */
export function looksBinary(bytes) {
  if (!bytes.length) return false
  const limit = Math.min(bytes.length, 4096)
  let control = 0
  for (let index = 0; index < limit; index++) {
    const code = bytes[index]
    if (code === 0) return true
    // 允许 \t \n \r \f \v 与 ESC（终端日志里的颜色码）
    if (code < 32 && code !== 9 && code !== 10 && code !== 13 && code !== 12 && code !== 11 && code !== 27) control++
  }
  return control / limit > 0.1
}

function decodeWith(bytes, encoding) {
  return new TextDecoder(encoding, { fatal: true }).decode(bytes)
}

/**
 * 文本解码：UTF-8 优先，失败回退 GBK（中文老文件常见）；两者都失败按二进制处理。
 * 返回 { text, encoding } 或 { text: null, binary: true }。
 */
export function decodePreviewBytes(bytes, truncated) {
  if (looksBinary(bytes)) return { text: null, binary: true }
  // 截断可能切在多字节字符中间：先去掉末尾一个字节再试，避免误判成解码失败
  const candidate = truncated && bytes.length > 1 ? bytes.subarray(0, bytes.length - 1) : bytes
  const encodings = ['utf-8', 'gbk']
  for (const encoding of encodings) {
    try {
      const text = decodeWith(candidate, encoding).replace(/^\uFEFF/, '')
      return { text, encoding: encoding.toUpperCase() }
    } catch (_) {
      // 换下一种编码
    }
  }
  return { text: null, binary: true }
}

/**
 * Markdown 渲染：marked 只把源码转成标记，DOMPurify 再清掉脚本/事件/危险协议。
 * 不允许多内联 HTML 注入（`<script>`、`onerror=`、`javascript:` 链接全部失效）。
 */
export function renderMarkdown(source) {
  const html = marked.parse(String(source || ''), { gfm: true, breaks: true, async: false })
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'iframe', 'object', 'embed'],
    FORBID_ATTR: ['style', 'srcset'],
  })
}
