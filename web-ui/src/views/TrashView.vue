<template>
  <div>
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-head">
          <span class="title">回收站</span>
          <span class="muted">{{ status }}</span>
          <div class="spacer" />
          <el-button size="small" :loading="busy" @click="load">刷新</el-button>
        </div>
      </template>

      <p class="hint" style="margin: 0 0 8px">
        这里只列出已删除项；「恢复」放回原目录，「彻底删除」是永久删除、不可恢复。
      </p>

      <el-table
        ref="tableRef"
        class="desktop-table"
        :data="items"
        size="small"
        row-key="id"
        empty-text="回收站为空"
        @selection-change="onSelection"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column label="名称" min-width="230">
          <template #default="{ row }">
            <div class="name-cell">
              <span class="name-text" :class="{ mono: !row.isFolder }">{{ row.name || row.id }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ row.isFolder ? '文件夹' : '文件' }}</template>
        </el-table-column>
        <el-table-column label="原位置" min-width="160">
          <template #default="{ row }">
            <span>{{ row.parentName || (row.parentId ? '原位置不可用' : '根目录') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.isFolder ? '' : formatSize(row.sizeBytes) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="删除时间" width="170">
          <template #default="{ row }"><span class="mono">{{ formatTime(row.deleteTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="64" align="center" class-name="action-col">
          <template #default="{ row }">
            <RowActionMenu
              trigger="hover"
              :label="row.name || row.id"
              :actions="trashActions(row)"
              @select="(key) => runRowAction(row, key)"
            />
          </template>
        </el-table-column>
      </el-table>

      <div class="mobile-only mobile-toolbar">
        <el-button size="small" @click="toggleAll">{{ allSelected ? '取消全选' : '全选' }}</el-button>
      </div>
      <div class="mobile-only card-list">
        <el-card v-for="row in items" :key="row.id" shadow="never" class="row-card">
          <div class="row-card-action">
            <RowActionMenu
              trigger="click"
              placement="bottom-end"
              :label="row.name || row.id"
              :actions="trashActions(row)"
              @select="(key) => runRowAction(row, key)"
            />
          </div>
          <div class="row-card-main">
            <el-checkbox
              class="row-check"
              :model-value="isSelected(row)"
              @change="(value) => toggleRow(row, value)"
            />
            <div class="row-card-body">
              <div class="row-card-title">
                <span :class="{ mono: !row.isFolder }">{{ row.name || row.id }}</span>
              </div>
              <div class="row-card-meta">
                <span>{{ row.isFolder ? '文件夹' : '文件' }}</span>
                <span v-if="!row.isFolder" class="num">{{ formatSize(row.sizeBytes) }}</span>
                <span>原位置：{{ row.parentName || (row.parentId ? '不可用' : '根目录') }}</span>
                <span>删除于 {{ formatTime(row.deleteTime) }}</span>
              </div>
            </div>
          </div>
        </el-card>
        <p v-if="!items.length && !busy" class="hint">回收站为空</p>
      </div>

      <div v-if="selection.length" class="selection-bar">
        <div class="selection-info">
          <span>已选 <b>{{ selection.length }}</b> 项</span>
        </div>
        <div class="selection-actions">
          <el-button size="small" type="primary" :loading="busy" @click="restoreRows(selection)">
            恢复（{{ selection.length }} 项）
          </el-button>
          <el-button size="small" type="danger" plain :loading="busy" @click="deleteForever(selection)">
            彻底删除（{{ selection.length }} 项）
          </el-button>
          <el-button size="small" @click="clearSelection">取消选择</el-button>
        </div>
      </div>

      <p v-if="hint" class="hint" style="margin: 8px 0 0">{{ hint }}</p>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import RowActionMenu from '../components/RowActionMenu.vue'
import { api, errorDetail, errorText, formatSize } from '../api'
import { state } from '../store'

const emit = defineEmits(['count'])
const items = ref([])
const status = ref('')
const hint = ref('')
const busy = ref(false)
const selection = ref([])
const tableRef = ref(null)
const nextPageToken = ref('')

const allSelected = computed(() => items.value.length > 0 && selection.value.length === items.value.length)
const isSelected = (row) => selection.value.some((item) => item.id === row.id)

const trashActions = (row) => [
  { key: 'restore', label: '恢复', icon: 'restore', hint: '放回原目录' },
  { key: 'delete', label: '彻底删除', icon: 'delete', danger: true, hint: '永久删除，不可恢复' },
]

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
  // 拿不到真实总数（还有下一页）时不显示数字，避免在 Tab 上报一个假条数
  emit('count', nextPageToken.value ? 0 : items.value.length)
  hint.value = ''
}

function onSelection(rows) {
  selection.value = rows
}

function clearSelection() {
  tableRef.value?.clearSelection()
  selection.value = []
}

function toggleRow(row, value) {
  tableRef.value?.toggleRowSelection(row, value)
}

function toggleAll() {
  tableRef.value?.toggleAllSelection()
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
  try {
    await ElMessageBox.confirm(
      `${title}` + listed + '\n彻底删除后不可恢复。',
      '彻底删除',
      { type: 'error', confirmButtonText: '彻底删除', cancelButtonText: '取消' },
    )
    return true
  } catch (_) {
    return false
  }
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
  const failedRows = items.value.filter((row) => failed.some((item) => item.id === row.id))
  failedRows.forEach((row) => tableRef.value?.toggleRowSelection(row, true))
  hint.value = failed.length
    ? `已恢复 ${okIds.length} 项；失败 ${failed.length} 项：${failed.map((item) => item.message || '未知原因').join('；')}`
    : `已恢复 ${okIds.length} 项，已从回收站移除`
  status.value = `${items.value.length} 项`
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
  const failedRows = items.value.filter((row) => failed.some((item) => item.id === row.id))
  failedRows.forEach((row) => tableRef.value?.toggleRowSelection(row, true))
  hint.value = failed.length
    ? `已彻底删除 ${okIds.length} 项；失败 ${failed.length} 项：${failed.map((item) => item.message || '未知原因').join('；')}`
    : `已彻底删除 ${okIds.length} 项，不可恢复`
  status.value = `${items.value.length} 项`
}

onMounted(load)

// 登录完成后补拉一次：首屏可能还没拿到 PikPak 登录态
watch(
  () => state.auth.authenticated,
  (authenticated) => {
    if (authenticated) load()
  },
)

defineExpose({ load })
</script>
