<!-- 目标目录选择器：懒加载子目录，递归渲染；只列文件夹，点名称即选中，点箭头展开。 -->
<template>
  <div class="pl-1">
    <div
      class="flex min-h-11 items-center gap-1 rounded-md px-1 text-sm transition-colors md:min-h-9"
      :class="node.id === selectedId ? 'bg-brand-soft text-brand' : 'hover:bg-surface2'"
    >
      <button
        type="button"
        class="flex h-9 w-9 items-center justify-center rounded text-muted hover:text-ink focus-visible:outline-none"
        :aria-label="node.expanded ? '收起' : '展开'"
        @click.stop="toggle"
      >
        <AppIcon :name="node.expanded ? 'chevronDown' : 'chevronRight'" :size="16" />
      </button>
      <button type="button" class="min-w-0 flex-1 truncate text-left" @click="$emit('select', node)">
        {{ node.name }}
      </button>
      <span v-if="node.loading" class="text-xs text-muted">加载中…</span>
      <span v-else-if="node.error" class="max-w-[45%] truncate text-xs text-danger" :title="node.error">{{ node.error }}</span>
      <span v-else-if="node.loaded && !node.children.length" class="text-xs text-muted">无子目录</span>
    </div>
    <div v-if="node.expanded" class="ml-4 border-l border-line pl-1">
      <DirTreePicker
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :selected-id="selectedId"
        @select="$emit('select', $event)"
      />
    </div>
  </div>
</template>

<script setup>
import AppIcon from './ui/AppIcon.vue'
import { api, errorText } from '../api'

const props = defineProps({
  node: { type: Object, required: true },
  selectedId: { type: String, default: '' },
})
defineEmits(['select'])

async function load() {
  if (props.node.loading) return
  if (props.node.loaded) {
    props.node.expanded = true
    return
  }
  props.node.loading = true
  props.node.error = ''
  let pageToken = ''
  let guard = 0
  const children = []
  do {
    const result = await api(
      `/api/drive/list?parentId=${encodeURIComponent(props.node.id)}&pageToken=${encodeURIComponent(pageToken)}`,
    )
    if (!result.ok) {
      props.node.loading = false
      props.node.error = errorText(result)
      return
    }
    const data = result.data || {}
    const path = props.node.path || []
    for (const row of data.items || []) {
      if (!row.isFolder) continue
      children.push({
        id: row.id,
        name: row.name || row.id,
        path: [...path, row.name || row.id],
        isFolder: true,
        children: [],
        loaded: false,
        loading: false,
        expanded: false,
        error: '',
      })
    }
    pageToken = data.nextPageToken || ''
  } while (pageToken && ++guard < 10)
  props.node.children = children
  props.node.loaded = true
  props.node.loading = false
  props.node.expanded = true
}

function toggle() {
  if (!props.node.loaded) {
    load()
    return
  }
  props.node.expanded = !props.node.expanded
}

defineExpose({ load })
</script>
