<template>
  <section class="card p-3 md:p-4">
    <header class="mb-3 flex flex-wrap items-center gap-2">
      <h2 class="text-base font-semibold">回收站</h2>
      <span class="text-xs text-muted">{{ status }}</span>
      <span class="flex-1" />
      <AppButton size="sm" variant="ghost" icon="refresh" :loading="busy" @click="load">刷新</AppButton>
      <AppButton v-if="!selectionMode && items.length" class="md:hidden" size="sm" variant="ghost" @click="enterSelection">
        选择
      </AppButton>
    </header>

    <p class="mb-2 text-xs text-muted">这里只列出已删除项；「恢复」放回原目录，「彻底删除」是永久删除、不可恢复。</p>

    <div :class="selection.length ? 'pb-28 md:pb-0' : ''">
      <ProgressiveList :items="items" :step="30">
        <template #default="{ row }">
          <div
            class="mb-2 grid grid-cols-[auto_auto_minmax(0,1fr)_auto] items-center gap-2 rounded-md border border-line bg-surface px-1.5 py-1.5 transition-colors hover:bg-surface2 md:grid-cols-[auto_auto_minmax(0,1fr)_70px_90px_130px_155px_auto]"
            :class="selectionMode ? 'md:bg-surface2/60' : ''"
            @touchstart.passive="startPress(row)"
            @touchend="cancelPress"
            @touchmove="cancelPress"
          >
            <AppCheckbox
              v-if="selectionMode"
              :model-value="isSelected(row)"
              :label="`选择 ${row.name || row.id}`"
              @update:model-value="(value) => toggleRow(row, value)"
            />
            <span v-else class="w-11 md:w-11" />

            <span class="flex h-9 w-9 items-center justify-center rounded-md bg-surface2 text-muted">
              <AppIcon :name="row.isFolder ? 'folder' : 'file'" :size="18" />
            </span>

            <div class="min-w-0">
              <p class="truncate text-sm font-medium" :class="{ 'font-mono': !row.isFolder }">{{ row.name || row.id }}</p>
              <p class="mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted md:hidden">
                <span>{{ row.isFolder ? '文件夹' : '文件' }}</span>
                <span v-if="!row.isFolder">{{ formatSize(row.sizeBytes) }}</span>
                <span class="truncate">原位置：{{ row.parentName || (row.parentId ? '不可用' : '根目录') }}</span>
                <span>删除于 {{ formatTime(row.deleteTime) }}</span>
              </p>
            </div>

            <span class="hidden text-xs text-muted md:block">{{ row.isFolder ? '文件夹' : '文件' }}</span>
            <span class="hidden text-xs text-ink md:block">{{ formatSize(row.sizeBytes) }}</span>
            <span class="hidden truncate text-xs text-muted md:block" :title="row.parentName">
              {{ row.parentName || (row.parentId ? '原位置不可用' : '根目录') }}
            </span>
            <span class="hidden font-mono text-xs text-muted md:block">{{ formatTime(row.deleteTime) }}</span>

            <RowActionMenu
              trigger="hover"
              placement="bottom-end"
              :label="row.name || row.id"
              :actions="trashActions()"
              @select="(key) => runRowAction(row, key)"
            />
          </div>
        </template>
      </ProgressiveList>
      <p v-if="!items.length && !busy" class="py-8 text-center text-sm text-muted">回收站为空</p>
      <p v-else-if="busy" class="py-8 text-center text-sm text-muted">加载中…</p>
    </div>

    <SelectionDock v-if="selection.length">
      <template #info>
        已选 <b>{{ selection.length }}</b> 项
      </template>
      <template #actions>
        <AppButton size="sm" variant="primary" :loading="busy" @click="restoreRows(selection)">
          恢复（{{ selection.length }} 项）
        </AppButton>
        <AppButton size="sm" variant="danger-soft" :loading="busy" @click="deleteForever(selection)">
          彻底删除（{{ selection.length }} 项）
        </AppButton>
        <AppButton size="sm" variant="ghost" @click="clearSelection">取消选择</AppButton>
      </template>
    </SelectionDock>

    <p v-if="hint" class="mt-2 text-xs text-muted">{{ hint }}</p>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import AppButton from '../components/ui/AppButton.vue'
import AppCheckbox from '../components/ui/AppCheckbox.vue'
import AppIcon from '../components/ui/AppIcon.vue'
import ProgressiveList from '../components/ui/ProgressiveList.vue'
import SelectionDock from '../components/ui/SelectionDock.vue'
import RowActionMenu from '../components/RowActionMenu.vue'
import { confirmDialog } from '../components/ui/confirm'
import { api, errorDetail, errorText, formatSize } from '../api'
import { state } from '../store'

const emit = defineEmits(['count'])
const items = ref([])
const status = ref('')
const hint = ref('')
const busy = ref(false)
const selection = ref([])
const selectionMode = ref(false)
const nextPageToken = ref('')

const allSelected = computed(() => items.value.length > 0 && selection.value.length === items.value.length)
const isSelected = (row) => selection.value.some((item) => item.id === row.id)

const trashActions = () => [
  { key: 'restore', label: '恢复', icon: 'restore', hint: '放回原目录' },
  { key: 'delete', label: '彻底删除', icon: 'delete', danger: true, hint: '永久删除，不可恢复' },
]

let pressTimer = null
function startPress(row) {
  cancelPress()
  pressTimer = setTimeout(() => {
    selectionMode.value = true
    if (!isSelected(row)) toggleRow(row, true)
  }, 500)
}
function cancelPress() {
  if (pressTimer) clearTimeout(pressTimer)
  pressTimer = null
}

function enterSelection() {
  selectionMode.value = true
}

async function load() {
  if (state.auth.authenticated !== true) {
    items.value = []
    status.value = '登录后可查看回收站'
    emit('count', 0)
    return
  }
  busy.value = true
  const result = await api(`/api/drive/trash/list?pageToken=${encodeURIComponent(nextPageToken.value)}`)
  busy.value = false
  if (!result.ok) {
    status.value = errorText(result)
    items.value = []
    emit('count', 0)
    return
  }
  items.value = result.data?.items || []
  nextPageToken.value = result.data?.nextPageToken || ''
  clearSelection()
  status.value = `${items.value.length} 项${nextPageToken.value ? '（还有下一页，暂不翻页）' : ''}`
  emit('count', nextPageToken.value ? 0 : items.value.length)
  hint.value = ''
}

function clearSelection() {
  selection.value = []
  selectionMode.value = false
}

function toggleRow(row, value) {
  const exists = isSelected(row)
  if (value && !exists) selection.value = [...selection.value, row]
  if (!value && exists) selection.value = selection.value.filter((item) => item.id !== row.id)
}

function formatTime(raw) {
  if (!raw) return '—'
  const numeric = Number(raw)
  if (Number.isFinite(numeric) && numeric > 1_000_000_000) {
    const millis = numeric < 10_000_000_000 ? numeric * 1000 : numeric
    return new Date(millis).toLocaleString()
  }
  const parsed = new Date(raw)
  return Number.isNaN(parsed.getTime()) ? raw : parsed.toLocaleString()
}

async function runRowAction(row, key) {
  if (key === 'restore') {
    await restoreRows([row])
  } else {
    await deleteForever([row])
  }
}

async function confirmDelete(rows) {
  const names = rows.map((row) => `「${row.name || row.id}」`)
  const title = rows.length === 1 ? `彻底删除${names[0]}？` : `彻底删除这 ${rows.length} 项？`
  const listed = rows.length > 1 ? `\n${names.slice(0, 3).join('、')}${names.length > 3 ? ' 等' : ''}` : ''
  return confirmDialog({
    title: '彻底删除',
    message: `${title}${listed}\n彻底删除后不可恢复。`,
    confirmText: '彻底删除',
    danger: true,
  })
}

async function restoreRows(rows) {
  const ids = rows.map((row) => row.id)
  if (!ids.length || busy.value) return
  busy.value = true
  const result = await api('/api/drive/trash/restore', { method: 'POST', body: JSON.stringify({ ids }) })
  busy.value = false
  if (!result.ok) {
    hint.value = errorDetail(result)
    return
  }
  const okIds = (result.data?.items || []).filter((item) => item.ok).map((item) => item.id)
  const failed = (result.data?.items || []).filter((item) => !item.ok)
  items.value = items.value.filter((row) => !okIds.includes(row.id))
  selection.value = selection.value.filter((row) => !okIds.includes(row.id))
  if (selection.value.length === 0) selectionMode.value = false
  hint.value = failed.length
    ? `已恢复 ${okIds.length} 项；失败 ${failed.length} 项：${failed.map((item) => item.message || '未知原因').join('；')}`
    : `已恢复 ${okIds.length} 项，已从回收站移除`
  status.value = `${items.value.length} 项`
  emit('count', items.value.length)
}

async function deleteForever(rows) {
  const ids = rows.map((row) => row.id)
  if (!ids.length || busy.value) return
  if (!(await confirmDelete(rows))) return
  busy.value = true
  const result = await api('/api/drive/trash/delete', { method: 'POST', body: JSON.stringify({ ids }) })
  busy.value = false
  if (!result.ok) {
    hint.value = errorDetail(result)
    return
  }
  const okIds = (result.data?.items || []).filter((item) => item.ok).map((item) => item.id)
  const failed = (result.data?.items || []).filter((item) => !item.ok)
  items.value = items.value.filter((row) => !okIds.includes(row.id))
  selection.value = selection.value.filter((row) => !okIds.includes(row.id))
  if (selection.value.length === 0) selectionMode.value = false
  hint.value = failed.length
    ? `已彻底删除 ${okIds.length} 项；失败 ${failed.length} 项：${failed.map((item) => item.message || '未知原因').join('；')}`
    : `已彻底删除 ${okIds.length} 项，不可恢复`
  status.value = `${items.value.length} 项`
  emit('count', items.value.length)
}

onMounted(load)

watch(
  () => state.auth.authenticated,
  (authenticated) => {
    if (authenticated) load()
  },
)

defineExpose({ load })
</script>
