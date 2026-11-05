<template>
  <button
    type="button"
    :disabled="disabled || loading"
    :class="[
      'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors select-none',
      'min-h-11 px-4 text-sm md:min-h-9 md:px-3 md:text-[13px]',
      'focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-55',
      variantClass,
      block ? 'w-full' : '',
    ]"
  >
    <span
      v-if="loading"
      class="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent"
      aria-hidden="true"
    />
    <AppIcon v-else-if="icon" :name="icon" :size="16" />
    <slot />
  </button>
</template>

<script setup>
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  variant: { type: String, default: 'secondary' },
  icon: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  block: { type: Boolean, default: false },
})

const VARIANTS = {
  primary: 'bg-brand text-white hover:bg-brand/90',
  secondary: 'border border-line bg-surface text-ink hover:bg-surface2',
  ghost: 'text-muted hover:bg-surface2 hover:text-ink',
  danger: 'bg-danger text-white hover:bg-danger/90',
  'danger-soft': 'border border-danger/40 bg-danger-soft text-danger hover:bg-danger/10',
  warning: 'bg-warning text-white hover:bg-warning/90',
}

const variantClass = computed(() => VARIANTS[props.variant] || VARIANTS.secondary)
</script>
