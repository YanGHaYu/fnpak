<template>
  <div
    class="rounded-lg border px-3 py-2 text-sm"
    :class="toneClass"
    role="alert"
  >
    <div class="flex items-start gap-2">
      <AppIcon :name="iconName" :size="16" class="mt-0.5" />
      <div class="min-w-0 flex-1">
        <p v-if="title" class="font-medium">{{ title }}</p>
        <p v-if="message" class="mt-0.5 whitespace-pre-wrap break-words opacity-90">{{ message }}</p>
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  tone: { type: String, default: 'info' },
  title: { type: String, default: '' },
  message: { type: String, default: '' },
})

const mol = computed(() => ({ info: 'border-line bg-surface2 text-ink', error: 'border-danger/40 bg-danger-soft text-danger', warning: 'border-warning/40 bg-warning-soft text-warning', success: 'border-success/40 bg-success-soft text-success' }))
const toneClass = computed(() => mol.value[props.tone] || mol.value.info)
const iconName = computed(() => ({ info: 'link', error: 'cancel', warning: 'cancel', success: 'check' }[props.tone] || 'link'))
</script>
