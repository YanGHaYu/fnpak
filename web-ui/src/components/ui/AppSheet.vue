<!-- 移动端底部抽屉（设置 / 目录选择等整页或半页内容），桌面下居中显示为普通弹层。 -->
<template>
  <DialogRoot :open="open" @update:open="(value) => emit('update:open', value)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-50 bg-black/45" />
      <DialogContent
        class="fixed inset-x-0 bottom-0 z-50 flex max-h-[92vh] flex-col rounded-t-xl border border-line bg-surface shadow-modal focus-visible:outline-none md:left-1/2 md:top-1/2 md:inset-x-auto md:w-[min(560px,94vw)] md:-translate-x-1/2 md:-translate-y-1/2 md:rounded-lg"
      >
        <div class="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
          <DialogTitle class="text-base font-semibold">{{ title }}</DialogTitle>
          <IconButton icon="x" label="关闭" @click="emit('update:open', false)" />
        </div>
        <div class="min-h-0 flex-1 overflow-auto px-4 py-4 safe-bottom">
          <slot />
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<script setup>
import { DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'
import IconButton from './AppIconButton.vue'

defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
})
const emit = defineEmits(['update:open'])
</script>
