<!-- 目标目录选择器：懒加载子目录，递归渲染；只列文件夹，点名称即选中，点箭头展开。 -->
<template>
  <div class="dir-node">
    <div class="dir-node-row" :class="{ selected: node.id === selectedId }" @click="$emit('select', node)">
      <el-button text class="dir-toggle" @click.stop="toggle">
        {{ node.expanded ? '▾' : '▸' }}
      </el-button>
      <span class="dir-name">{{ node.name }}</span>
      <span v-if="node.loading" class="muted dir-state">加载中…</span>
      <span v-else-if="node.error" class="error-line dir-state">{{ node.error }}</span>
      <span v-else-if="node.loaded && !node.children.length" class="muted dir-state">无子目录</span>
    </div>
    <div v-if="node.expanded" class="dir-children">
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
