<template>
  <el-dialog
    v-model="visible"
    class="player-dialog"
    :title="item?.name || '播放'"
    width="min(980px, 94vw)"
    destroy-on-close
    @closed="teardown"
  >
    <div class="toolbar" style="margin-bottom: 8px">
      <el-tag size="small" :type="modeType">{{ modeText }}</el-tag>
      <el-tag size="small" effect="plain">{{ metaText }}</el-tag>
      <div class="spacer" />
      <el-button size="small" @click="forceProxy">强制转发</el-button>
    </div>
    <video ref="videoRef" class="video-box" controls playsinline />
    <p class="hint mono" style="white-space: pre-wrap; margin-top: 8px">{{ diag }}</p>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { api, errorText, formatSize } from '../api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  item: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue'])

const videoRef = ref(null)
const mode = ref('')
const modeReason = ref('')
const sizeBytes = ref(0)
const mimeType = ref('')
const diag = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const modeText = computed(() => {
  if (!mode.value) return '查询模式…'
  return mode.value === 'redirect' ? '直链模式（302）' : '转发模式（服务端代理）'
})
const modeType = computed(() => (mode.value ? 'success' : 'info'))
const metaText = computed(
  () => `${formatSize(sizeBytes.value || props.item?.sizeBytes)} · ${mimeType.value || props.item?.mimeType || '未知类型'}`,
)

async function probeStream(id) {
  try {
    const response = await fetch(`/api/media/${encodeURIComponent(id)}/stream`, {
      headers: { Range: 'bytes=0-1023' },
      credentials: 'same-origin',
      cache: 'no-store',
    })
    return {
      status: response.status,
      text:
        `预检：HTTP ${response.status}${response.redirected ? '（已跟随 302 到直链）' : ''}` +
        ` · content-type=${response.headers.get('content-type') || '—'}` +
        ` · content-range=${response.headers.get('content-range') || '—'}` +
        ` · mode=${response.headers.get('x-fnpak-media-mode') || '—'}`,
    }
  } catch (error) {
    return { status: 0, text: `预检失败：${error.name}: ${error.message}` }
  }
}

async function start() {
  const item = props.item
  if (!item) return
  diag.value = ''

  const info = await api(`/api/media/${encodeURIComponent(item.id)}/info`)
  if (!props.modelValue) return
  if (!info.ok) {
    diag.value = errorText(info)
    return
  }
  const data = info.data || {}
  mode.value = data.mode || ''
  modeReason.value = data.modeReason || '—'
  sizeBytes.value = data.sizeBytes || item.sizeBytes || 0
  mimeType.value = data.mimeType || item.mimeType || ''

  const probe = await probeStream(item.id)
  if (!props.modelValue) return
  diag.value = `${probe.text}\n选路原因：${modeReason.value}`
  if (probe.status === 416) return
  if (videoRef.value) {
    videoRef.value.src = `/api/media/${encodeURIComponent(item.id)}/stream`
    videoRef.value.load()
  }
}

function forceProxy() {
  if (!props.item || !videoRef.value) return
  mode.value = 'proxy'
  modeReason.value = '手动强制转发'
  videoRef.value.src = `/api/media/${encodeURIComponent(props.item.id)}/stream?mode=proxy`
  videoRef.value.load()
  diag.value = `${diag.value}\n已切换为 ?mode=proxy`
}

function teardown() {
  if (videoRef.value) {
    videoRef.value.pause()
    videoRef.value.removeAttribute('src')
    videoRef.value.load()
  }
  mode.value = ''
  diag.value = ''
}

defineExpose({ start })
</script>
