<template>
  <el-popover
    v-model:visible="open"
    :trigger="trigger"
    :placement="placement"
    :width="trigger === 'click' ? 228 : 196"
    :show-after="trigger === 'hover' ? 80 : 0"
    :hide-after="0"
    :persistent="false"
    :teleported="true"
    popper-class="row-action-popper"
    :fallback-placements="['bottom-start', 'top-start', 'bottom-end', 'top-end']"
  >
    <template #reference>
      <button
        type="button"
        class="row-more"
        :title="`${label}：更多操作`"
        :aria-label="`${label}：更多操作`"
        :aria-expanded="open ? 'true' : 'false'"
        @click.stop
      >
        <span aria-hidden="true">⋯</span>
      </button>
    </template>

    <div class="row-menu" role="menu" @click.stop>
      <p class="row-menu-title">{{ label }}</p>
      <button
        v-for="action in actions"
        :key="action.key"
        type="button"
        role="menuitem"
        class="row-menu-item"
        :class="{ danger: action.danger }"
        :disabled="action.disabled"
        :title="action.hint || action.label"
        @click="choose(action)"
      >
        <el-icon class="row-menu-icon"><component :is="icons[action.icon] || MoreFilled" /></el-icon>
        <span>{{ action.label }}</span>
      </button>
    </div>
  </el-popover>
</template>

<script setup>
import { ref } from 'vue'
import {
  CircleClose,
  CopyDocument,
  Delete,
  Download,
  EditPen,
  FolderOpened,
  MoreFilled,
  Rank,
  Refresh,
  RefreshLeft,
  RefreshRight,
  VideoPause,
  VideoPlay,
} from '@element-plus/icons-vue'

const props = defineProps({
  /** 操作列表：{ key, label, icon, hint?, danger?, disabled? } */
  actions: { type: Array, default: () => [] },
  /** 被操作行/项的显示名，用于触发按钮 title 与浮层标题 */
  label: { type: String, default: '' },
  /** hover：桌面表格（≥769）；click：窄屏卡片（≤768） */
  trigger: { type: String, default: 'hover' },
  placement: { type: String, default: 'bottom-start' },
})
const emit = defineEmits(['select'])
const open = ref(false)

const icons = {
  play: VideoPlay,
  download: Download,
  folder: FolderOpened,
  rename: EditPen,
  move: Rank,
  copy: CopyDocument,
  delete: Delete,
  pause: VideoPause,
  resume: RefreshRight,
  retry: Refresh,
  cancel: CircleClose,
  restore: RefreshLeft,
}

function choose(action) {
  open.value = false
  if (action.disabled) return
  emit('select', action.key)
}
</script>
