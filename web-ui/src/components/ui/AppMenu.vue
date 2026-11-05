<!-- 行内/批量操作浮层：reka-ui Popover 提供定位与 esc/遮罩关闭，视觉自建。 -->
<template>
  <PopoverRoot v-model:open="open">
    <PopoverTrigger as-child>
      <slot name="trigger">
        <button
          type="button"
          class="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-md text-muted transition-colors hover:bg-surface2 hover:text-ink focus-visible:outline-none md:h-8 md:w-8"
          :title="`${label}：更多操作`"
          :aria-label="`${label}：更多操作`"
          :aria-expanded="String(open)"
          @mouseenter="onTriggerEnter"
          @mouseleave="onTriggerLeave"
          @click.stop
        >
          <AppIcon name="more" :size="18" />
        </button>
      </slot>
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent
        :side="placement === 'top-end' ? 'top' : 'bottom'"
        :align="placement === 'top-end' || placement === 'bottom-end' ? 'end' : 'start'"
        :side-offset="4"
        class="z-50 w-56 rounded-lg border border-line bg-surface p-1 shadow-pop focus-visible:outline-none"
        @mouseenter="onContentEnter"
        @mouseleave="onTriggerLeave"
      >
        <p class="truncate px-2.5 py-1 text-xs text-muted" :title="label">{{ label }}</p>
        <button
          v-for="action in actions"
          :key="action.key"
          type="button"
          role="menuitem"
          class="flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors disabled:cursor-not-allowed disabled:opacity-45"
          :class="action.danger ? 'text-danger hover:bg-danger-soft' : 'text-ink hover:bg-surface2'"
          :disabled="action.disabled"
          :title="action.hint || action.label"
          @click.stop="choose(action)"
        >
          <AppIcon :name="action.icon || 'more'" :size="16" />
          <span class="truncate">{{ action.label }}</span>
        </button>
        <p v-if="!actions.length" class="px-2.5 py-2 text-sm text-muted">没有可用操作</p>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<script setup>
import { onUnmounted, ref } from 'vue'
import { PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger } from 'reka-ui'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  actions: { type: Array, default: () => [] },
  label: { type: String, default: '' },
  trigger: { type: String, default: 'click' },
  placement: { type: String, default: 'bottom-start' },
})
const emit = defineEmits(['select'])
const open = ref(false)
let timer = null

function clearTimer() {
  if (timer) clearTimeout(timer)
  timer = null
}

function onTriggerEnter() {
  if (props.trigger !== 'hover') return
  clearTimer()
  timer = setTimeout(() => (open.value = true), 80)
}

function onContentEnter() {
  if (props.trigger !== 'hover') return
  clearTimer()
}

function onTriggerLeave() {
  if (props.trigger !== 'hover') return
  clearTimer()
  timer = setTimeout(() => (open.value = false), 150)
}

function choose(action) {
  open.value = false
  if (action.disabled) return
  emit('select', action.key)
}

onUnmounted(clearTimer)
</script>
