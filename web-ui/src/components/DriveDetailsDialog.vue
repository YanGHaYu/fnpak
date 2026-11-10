<!--
  文件/文件夹详情：数据全部来自当前目录列表行（服务端的 FileItem），因此打开即显、
  不再发请求。列表行没有的字段（如文件夹总大小）明确标「待扫描」，不编造。
-->
<template>
  <AppDialog
    :open="open"
    title="详情"
    :description="row?.name || row?.id || ''"
    mobile-full
    @update:open="(value) => emit('update:open', value)"
  >
    <div v-if="row" class="space-y-4">
      <div class="flex items-start gap-3">
        <DriveThumb
          :file-id="row.id"
          :name="row.name"
          :is-folder="row.isFolder"
          :is-video="row.isVideo"
          :has-thumbnail="!!row.hasThumbnail"
          :icon-size="26"
          class="h-16 w-16 shrink-0 rounded-md border border-line"
        />
        <div class="min-w-0">
          <p class="break-all text-sm font-medium">{{ row.name || row.id }}</p>
          <p class="mt-1 text-xs text-muted">{{ typeText }}</p>
        </div>
      </div>

      <dl class="grid gap-2">
        <div
          v-for="field in fields"
          :key="field.label"
          class="grid grid-cols-[80px_minmax(0,1fr)] items-start gap-2 md:grid-cols-[104px_minmax(0,1fr)]"
        >
          <dt class="text-xs text-muted md:text-sm">{{ field.label }}</dt>
          <dd
            class="min-w-0 break-all font-mono text-xs md:text-sm"
            :class="field.muted ? 'text-muted' : 'text-ink'"
          >
            {{ field.value }}
          </dd>
        </div>
      </dl>

      <p class="text-xs text-muted">
        文件夹大小与文件数量需递归扫描云端目录，列表接口不返回；缩略图缺失时显示类型图标。
      </p>
    </div>
  </AppDialog>
</template>

<script setup>
import { computed } from 'vue'
import AppDialog from './ui/AppDialog.vue'
import DriveThumb from './DriveThumb.vue'
import { formatDateTime, formatSize } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  row: { type: Object, default: null },
  pathLabel: { type: String, default: '根目录' },
})
const emit = defineEmits(['update:open'])

const typeText = computed(() => {
  const row = props.row
  if (!row) return ''
  if (row.isFolder) return '文件夹'
  return row.mimeType || '未知类型'
})

function formatDuration(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return ''
  const total = Math.round(ms / 1000)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (value) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`
}

const fields = computed(() => {
  const row = props.row
  if (!row) return []
  const list = [
    { label: '类型', value: row.isFolder ? '文件夹' : row.mimeType || '未知类型' },
    { label: '大小', value: row.isFolder ? '—（文件夹需扫描）' : formatSize(row.sizeBytes) || '0 B', muted: row.isFolder },
    { label: '添加时间', value: formatDateTime(row.createdAtMs, true) || '—', muted: !formatDateTime(row.createdAtMs) },
    { label: '修改时间', value: formatDateTime(row.modifiedAtMs, true) || '—', muted: !formatDateTime(row.modifiedAtMs) },
  ]
  const duration = formatDuration(row.durationMs)
  if (duration) list.push({ label: '时长', value: duration })
  list.push({ label: 'GCID / 哈希', value: row.hash || '—', muted: !row.hash })
  list.push({ label: '文件 ID', value: row.id || '—' })
  list.push({ label: '父目录 ID', value: row.parentId || '根目录', muted: !row.parentId })
  list.push({ label: '所在目录', value: props.pathLabel || '根目录' })
  return list
})
</script>
