<!--
  目录缩略图：只在进入视口时才请求同源 `/api/drive/files/{id}/thumbnail`（懒加载），
  避免一页几十行同时打满连接。无缩略图 / 加载失败 / 非文件时回退到类型图标，
  不会出现浏览器默认的破图。
-->
<template>
  <div ref="host" class="relative flex items-center justify-center overflow-hidden bg-surface2 text-muted">
    <AppIcon v-if="!loaded || failed" :name="iconName" :size="iconSize" />
    <img
      v-if="visible && !failed"
      :src="src"
      :alt="altText"
      loading="lazy"
      decoding="async"
      class="absolute inset-0 h-full w-full object-cover"
      @load="loaded = true"
      @error="failed = true"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import AppIcon from './ui/AppIcon.vue'

const props = defineProps({
  fileId: { type: String, default: '' },
  name: { type: String, default: '' },
  isFolder: { type: Boolean, default: false },
  isVideo: { type: Boolean, default: false },
  hasThumbnail: { type: Boolean, default: false },
  iconSize: { type: [Number, String], default: 18 },
})

const host = ref(null)
const visible = ref(false)
const loaded = ref(false)
const failed = ref(false)
let observer = null

const enabled = computed(() => !!props.fileId && props.hasThumbnail && !props.isFolder)
const src = computed(() => `/api/drive/files/${encodeURIComponent(props.fileId)}/thumbnail`)
const altText = computed(() => `${props.name || props.fileId} 缩略图`)
const iconName = computed(() => (props.isFolder ? 'folder' : props.isVideo ? 'play' : 'file'))

function reset() {
  loaded.value = false
  failed.value = false
}

watch(() => props.fileId, reset)

onMounted(() => {
  if (!enabled.value) return
  if (typeof IntersectionObserver === 'undefined') {
    // 老浏览器：没有观察者就直接加载，仍受浏览器自身并发限制
    visible.value = true
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        visible.value = true
        observer?.disconnect()
        observer = null
      }
    },
    { rootMargin: '240px' },
  )
  if (host.value) observer.observe(host.value)
})

onUnmounted(() => observer?.disconnect())
</script>
