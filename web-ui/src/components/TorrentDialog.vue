<template>
  <AppDialog
    :open="visible"
    :title="item ? `种子：${item.name}` : '种子详情'"
    description="只读取文件清单与 infohash，不会自动下载到本机"
    mobile-full
    @update:open="(value) => visible = value"
  >
    <div class="space-y-3">
      <AppAlert
        v-if="phase === 'loading'"
        title="正在解析种子…"
        message="正在读取种子字节、计算 infohash 并整理文件清单，稍等片刻。"
      />

      <template v-else-if="phase === 'error'">
        <AppAlert tone="error" title="无法打开种子" :message="failureMessage" />
        <p v-if="failureCode" class="text-xs text-muted">错误码：{{ failureCode }}</p>
      </template>

      <template v-else>
        <div class="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <div class="rounded-md border border-line bg-surface2 px-3 py-2">
            <p class="text-xs text-muted">总大小</p>
            <p class="mt-0.5 text-sm font-medium tabular-nums">{{ formatSize(listing.totalSizeBytes) || '—' }}</p>
          </div>
          <div class="rounded-md border border-line bg-surface2 px-3 py-2">
            <p class="text-xs text-muted">文件</p>
            <p class="mt-0.5 text-sm font-medium tabular-nums">{{ listing.fileCount }}</p>
          </div>
          <div class="rounded-md border border-line bg-surface2 px-3 py-2">
            <p class="text-xs text-muted">目录</p>
            <p class="mt-0.5 text-sm font-medium tabular-nums">{{ listing.folderCount }}</p>
          </div>
          <div class="rounded-md border border-line bg-surface2 px-3 py-2">
            <p class="text-xs text-muted">Tracker</p>
            <p class="mt-0.5 text-sm font-medium tabular-nums">{{ listing.trackers.length }}</p>
          </div>
        </div>

        <p class="break-all text-xs text-muted">
          infohash：<span class="font-mono">{{ listing.infoHash || '—' }}</span>
        </p>

        <AppAlert v-if="saved" tone="success" title="已加入离线" :message="savedMessage" />
        <AppAlert v-else-if="saveError" tone="error" title="加入离线失败" :message="saveError" />

        <div class="overflow-hidden rounded-md border border-line">
          <div class="flex items-center justify-between border-b border-line bg-surface2 px-3 py-2 text-xs text-muted">
            <span>文件清单</span>
            <span>{{ listing.files.length }} / {{ listing.fileCount }}</span>
          </div>
          <ul class="max-h-[45vh] divide-y divide-line overflow-auto">
            <li v-for="file in visibleFiles" :key="file.path" class="flex items-center gap-3 px-3 py-2 text-sm">
              <span class="min-w-0 flex-1 truncate" :title="file.path">{{ file.path }}</span>
              <span class="shrink-0 tabular-nums text-xs text-muted">{{ formatSize(file.sizeBytes) || '0 B' }}</span>
            </li>
          </ul>
          <p v-if="hiddenCount" class="border-t border-line px-3 py-2 text-xs text-muted">
            仅显示前 {{ visibleFiles.length }} 个，还有 {{ hiddenCount }} 个未显示；加入离线不受影响。
          </p>
        </div>

        <p v-if="listing.trackers.length" class="break-all text-xs text-muted">
          Tracker：{{ listing.trackers.slice(0, 3).join('、') }}<span v-if="listing.trackers.length > 3"> 等 {{ listing.trackers.length }} 个</span>
        </p>
        <p v-if="!listing.trackers.length" class="text-xs text-muted">种子里没有 Tracker，离线成功率取决于 DHT。</p>
      </template>
    </div>

    <template #footer>
      <div class="flex flex-wrap justify-end gap-2">
        <AppButton @click="visible = false">关闭</AppButton>
        <AppButton v-if="phase === 'error'" @click="start">重试</AppButton>
        <AppButton
          v-if="phase === 'ready' && !saved"
          variant="primary"
          :disabled="saving || !listing.magnet"
          @click="addOffline"
        >
          {{ saving ? '提交中…' : '加入离线' }}
        </AppButton>
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
import { openTorrent, saveOfflineLink, torrentErrorCode, torrentErrorText } from '../torrent'

const MAX_VISIBLE_FILES = 200

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  item: { type: Object, default: null },
  parentId: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const phase = ref('loading')
const listing = ref(null)
const failureMessage = ref('')
const failureCode = ref('')
const saving = ref(false)
const saveError = ref('')
const saved = ref(false)
const savedMessage = ref('')

let runId = 0

const visibleFiles = computed(() => (listing.value?.files || []).slice(0, MAX_VISIBLE_FILES))
const hiddenCount = computed(() => Math.max(0, (listing.value?.fileCount || 0) - visibleFiles.value.length))

function reset() {
  runId += 1
  phase.value = 'loading'
  listing.value = null
  failureMessage.value = ''
  failureCode.value = ''
  saving.value = false
  saveError.value = ''
  saved.value = false
  savedMessage.value = ''
}

async function start() {
  reset()
  if (!props.modelValue || !props.item) return
  const currentRun = runId
  const result = await openTorrent(props.item.id)
  if (currentRun !== runId || !props.modelValue) return
  if (!result.ok) {
    phase.value = 'error'
    failureMessage.value = torrentErrorText(result)
    failureCode.value = torrentErrorCode(result)
    return
  }
  listing.value = result.data || {}
  phase.value = 'ready'
}

async function addOffline() {
  if (!listing.value?.magnet || saving.value || saved.value) return
  saving.value = true
  saveError.value = ''
  const result = await saveOfflineLink(listing.value.magnet, props.parentId)
  saving.value = false
  if (!result.ok) {
    saveError.value = torrentErrorText(result)
    return
  }
  saved.value = true
  savedMessage.value = result.data?.instantComplete
    ? `已秒传，文件 ID：${result.data.fileId || '（未知）'}`
    : `已提交离线任务：${result.data?.taskId || '（无任务 ID）'}，可在「离线」Tab 查看进度`
  emit('saved')
}

watch(() => props.modelValue, (open) => {
  if (open) start()
  else runId += 1
})
watch(() => props.item?.id, () => {
  if (props.modelValue) start()
})
onUnmounted(() => {
  runId += 1
})
</script>
