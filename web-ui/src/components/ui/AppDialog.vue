<template>
  <DialogRoot :open="open" @update:open="(value) => emit('update:open', value)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-50 bg-black/45 backdrop-blur-[2px] data-[state=open]:animate-in" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 flex max-h-[90vh] w-[min(560px,94vw)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-lg border border-line bg-surface shadow-modal focus-visible:outline-none"
        :class="mobileFull ? 'max-md:h-full max-md:max-h-full max-md:w-full max-md:translate-y-0 max-md:top-0 max-md:rounded-none max-md:border-0' : ''"
        @open-auto-focus="onAutoFocus"
      >
        <div class="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
          <div class="min-w-0">
            <DialogTitle class="truncate text-base font-semibold">{{ title }}</DialogTitle>
            <DialogDescription v-if="description" class="mt-0.5 truncate text-xs text-muted">
              {{ description }}
            </DialogDescription>
          </div>
          <IconButton icon="x" label="关闭" @click="emit('update:open', false)" />
        </div>
        <div class="min-h-0 flex-1 overflow-auto px-4 py-3">
          <slot />
        </div>
        <div v-if="$slots.footer" class="border-t border-line px-4 py-3 max-md:safe-bottom">
          <slot name="footer" />
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<script setup>
import { DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'
import IconButton from './AppIconButton.vue'

defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  mobileFull: { type: Boolean, default: false },
})
const emit = defineEmits(['update:open'])

function onAutoFocus(event) {
  // 弹层打开时不抢占输入框焦点（播放器/确认框场景更自然），仍保留键盘可达性
  event.preventDefault()
}
</script>
