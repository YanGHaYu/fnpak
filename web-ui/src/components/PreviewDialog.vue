<template>
  <AppDialog
    :open="visible"
    :title="item?.name || '预览'"
    :description="description"
    mobile-full
    @update:open="(value) => visible = value"
  >
    <div class="space-y-3">
      <div class="flex flex-wrap items-center gap-2 text-xs">
        <span class="rounded-full border border-line bg-surface2 px-2 py-0.5">{{ kindLabel }}</span>
        <span v-if="item?.sizeBytes" class="rounded-full border border-line px-2 py-0.5 text-muted">
          {{ formatSize(item.sizeBytes) }}
        </span>
        <span v-if="item?.mimeType" class="max-w-full truncate rounded-full border border-line px-2 py-0.5 text-muted">
          {{ item.mimeType }}
        </span>
        <span v-if="encoding" class="rounded-full border border-line px-2 py-0.5 text-muted">{{ encoding }}</span>
      </div>

      <AppAlert v-if="status === 'loading'" title="正在读取…" message="通过服务端转发读取文件头部，大文件不会整份下载。" />

      <AppAlert v-else-if="status === 'error'" tone="error" :title="errorTitle" :message="errorMessage" />

      <template v-else>
        <AppAlert
          v-if="truncated"
          tone="warning"
          title="仅显示前 1MB"
          :message="`文件共 ${formatSize(totalBytes)}，已截断显示；完整内容请下载查看。`"
        />

        <div v-if="kind === 'image'" class="flex items-center justify-center rounded-md bg-surface2 p-2">
          <img
            :src="streamUrl"
            :alt="item?.name || '图片预览'"
            class="max-h-[70vh] max-w-full rounded-md object-contain max-md:max-h-[72vh]"
            referrerpolicy="no-referrer"
            @error="mediaFailed = '浏览器无法显示这张图片（可能是不支持的编码），请下载后查看。'"
          />
        </div>

        <AppAlert v-if="kind === 'image' && mediaFailed" tone="error" title="图片无法预览" :message="mediaFailed" />

        <div v-if="kind === 'audio'" class="rounded-md bg-surface2 p-3">
          <audio
            :src="streamUrl"
            class="w-full"
            controls
            preload="metadata"
            @error="mediaFailed = '当前浏览器无法播放这个音频格式，请下载后用本地播放器打开。'"
          />
        </div>

        <AppAlert v-if="kind === 'audio' && mediaFailed" tone="error" title="音频无法播放" :message="mediaFailed" />

        <div v-if="kind === 'pdf'" class="space-y-2">
          <iframe
            :src="streamUrl"
            :title="`PDF 预览：${item?.name || ''}`"
            class="h-[70vh] w-full rounded-md border border-line bg-surface2"
          />
          <p class="text-xs text-muted">
            若此处为空白，说明当前浏览器没有内置 PDF 阅读器：可点「下载」保存后打开，或在支持的浏览器里查看。
          </p>
        </div>

        <div v-if="kind === 'text'">
          <pre class="max-h-[70vh] overflow-auto whitespace-pre-wrap break-words rounded-md border border-line bg-surface2 p-3 font-mono text-xs leading-5">{{ text }}</pre>
        </div>

        <div v-if="kind === 'markdown'">
          <div class="fnpak-md max-h-[70vh] overflow-auto rounded-md border border-line bg-surface p-3 text-sm" v-html="renderedHtml" />
        </div>
      </template>
    </div>

    <template #footer>
      <div class="flex flex-wrap justify-end gap-2">
        <AppButton @click="visible = false">关闭</AppButton>
        <AppButton v-if="item" variant="primary" icon="download" @click="emit('download', item)">下载到本机</AppButton>
      </div>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import AppAlert from './ui/AppAlert.vue'
import AppButton from './ui/AppButton.vue'
import AppDialog from './ui/AppDialog.vue'
import { formatSize } from '../api'
import { PREVIEW_KIND_LABEL, PREVIEW_MAX_BYTES, decodePreviewBytes, fetchTextPreview, previewKindFor, renderMarkdown } from '../preview'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  item: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'download'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const kind = computed(() => previewKindFor(props.item))
const kindLabel = computed(() => PREVIEW_KIND_LABEL[kind.value] || '文件')
const description = computed(() => (kind.value === 'markdown' ? 'Markdown 预览（已清理内联 HTML）' : `${kindLabel.value}在线预览`))

const streamUrl = computed(() => `/api/media/${encodeURIComponent(props.item?.id || '')}/stream?mode=proxy`)
const status = ref('idle')
const errorTitle = ref('')
const errorMessage = ref('')
const text = ref('')
const renderedHtml = ref('')
const encoding = ref('')
const truncated = ref(false)
const totalBytes = ref(0)
const mediaFailed = ref('')
let controller = null

function reset() {
  status.value = 'idle'
  errorTitle.value = ''
  errorMessage.value = ''
  text.value = ''
  renderedHtml.value = ''
  encoding.value = ''
  truncated.value = false
  totalBytes.value = 0
  mediaFailed.value = ''
  abort()
}

function abort() {
  if (controller) {
    controller.abort()
    controller = null
  }
}

async function start() {
  reset()
  if (!props.modelValue || !props.item) return
  const current = props.item
  const currentKind = kind.value
  if (!currentKind) {
    status.value = 'error'
    errorTitle.value = '不支持预览'
    errorMessage.value = '这个文件类型暂不支持在线预览，请下载后用本地工具打开。'
    return
  }
  // 图片/音频/PDF 交给浏览器直接取流，只有文本类需要先读字节
  if (!['text', 'markdown'].includes(currentKind)) {
    status.value = 'ready'
    return
  }

  status.value = 'loading'
  controller = new AbortController()
  const signal = controller.signal
  try {
    const { bytes, totalBytes: total, truncated: cut } = await fetchTextPreview(current.id, signal)
    if (signal.aborted) return
    totalBytes.value = total
    truncated.value = cut
    const decoded = decodePreviewBytes(bytes, cut)
    if (decoded.binary) {
      status.value = 'error'
      errorTitle.value = '无法在线预览'
      errorMessage.value = `检测到二进制内容或受支持的编码之外的内容（最多读取 ${formatSize(PREVIEW_MAX_BYTES)}），请下载后用本地工具查看。`
      return
    }
    encoding.value = decoded.encoding
    if (currentKind === 'markdown') renderedHtml.value = renderMarkdown(decoded.text)
    else text.value = decoded.text
    status.value = 'ready'
  } catch (error) {
    if (signal.aborted) return
    status.value = 'error'
    errorTitle.value = '读取失败'
    errorMessage.value = `无法读取文件内容：${error?.message || '未知错误'}`
  } finally {
    controller = null
  }
}

watch(() => props.modelValue, (open) => (open ? start() : reset()))
watch(() => props.item?.id, () => (props.modelValue ? start() : undefined))
onUnmounted(abort)
</script>

<style>
/* Markdown 渲染结果不做 scoped 处理（v-html 节点拿不到 scope id），用前缀类收敛样式。 */
.fnpak-md :where(h1, h2, h3, h4) {
  margin: 0.75em 0 0.4em;
  font-weight: 600;
  line-height: 1.3;
}
.fnpak-md h1 { font-size: 1.35em; }
.fnpak-md h2 { font-size: 1.2em; }
.fnpak-md h3 { font-size: 1.05em; }
.fnpak-md p { margin: 0.5em 0; }
.fnpak-md ul, .fnpak-md ol { margin: 0.5em 0; padding-left: 1.4em; }
.fnpak-md ul { list-style: disc; }
.fnpak-md ol { list-style: decimal; }
.fnpak-md code {
  border-radius: 4px;
  background: var(--color-surface2, #f1f5f9);
  padding: 0.1em 0.3em;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.92em;
}
.fnpak-md pre {
  overflow-x: auto;
  border-radius: 6px;
  background: var(--color-surface2, #f1f5f9);
  padding: 0.75em;
  font-size: 0.85em;
}
.fnpak-md pre code { background: transparent; padding: 0; }
.fnpak-md a { color: var(--color-brand, #2563eb); text-decoration: underline; word-break: break-all; }
.fnpak-md img { max-width: 100%; border-radius: 6px; }
.fnpak-md table { border-collapse: collapse; width: 100%; font-size: 0.92em; }
.fnpak-md th, .fnpak-md td { border: 1px solid var(--color-line, #e2e8f0); padding: 0.35em 0.5em; text-align: left; }
.fnpak-md hr { margin: 1em 0; border: 0; border-top: 1px solid var(--color-line, #e2e8f0); }
</style>
