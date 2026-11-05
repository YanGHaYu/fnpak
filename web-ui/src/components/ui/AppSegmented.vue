<template>
  <div class="flex flex-wrap gap-1 rounded-md bg-surface2 p-1" role="tablist">
    <button
      v-for="option in options"
      :key="option.key"
      type="button"
      role="tab"
      :aria-selected="String(option.key === modelValue)"
      class="min-h-10 flex-1 rounded-md px-3 text-sm font-medium transition-colors focus-visible:outline-none"
      :class="option.key === modelValue ? 'bg-surface text-ink shadow-card' : 'text-muted hover:text-ink'"
      @click="emit('update:modelValue', option.key)"
    >
      {{ option.label }}
    </button>
  </div>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: '' },
  options: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])
</script>
