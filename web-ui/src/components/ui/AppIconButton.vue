<template>
  <button
    type="button"
    :title="label"
    :aria-label="label"
    :aria-expanded="expanded === undefined ? undefined : String(expanded)"
    :disabled="disabled"
    class="inline-flex h-11 w-11 items-center justify-center rounded-md text-muted transition-colors hover:bg-surface2 hover:text-ink focus-visible:outline-none disabled:opacity-50 md:h-8 md:w-8"
  >
    <AppIcon :name="icon" :size="18" />
  </button>
</template>

<script setup>
import AppIcon from './AppIcon.vue'

defineProps({
  icon: { type: String, default: 'more' },
  label: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  expanded: { type: Boolean, default: undefined },
})
</script>
