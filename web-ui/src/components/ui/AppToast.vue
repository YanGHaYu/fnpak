<template>
  <div class="pointer-events-none fixed inset-x-0 top-3 z-[60] flex flex-col items-center gap-2 px-3">
    <TransitionGroup name="toast">
      <div
        v-for="item in toastState.items"
        :key="item.id"
        class="pointer-events-auto flex max-w-[92vw] items-center gap-2 rounded-lg border px-3 py-2 text-sm shadow-pop"
        :class="toneClass(item.type)"
      >
        <AppIcon :name="iconFor(item.type)" :size="16" />
        <span class="break-words">{{ item.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import AppIcon from './AppIcon.vue'
import { toastState } from './toast'

const TONES = {
  success: 'border-success/40 bg-success-soft text-success',
  warning: 'border-warning/40 bg-warning-soft text-warning',
  error: 'border-danger/40 bg-danger-soft text-danger',
  info: 'border-line bg-surface text-ink',
}
const toneClass = (type) => TONES[type] || TONES.info
const iconFor = (type) => ({ success: 'check', warning: 'cancel', error: 'cancel' }[type] || 'link')
</script>
