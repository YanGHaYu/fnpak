<template>
  <div
    class="w-full overflow-hidden rounded-full bg-surface3"
    :style="{ height: `${height}px` }"
    role="progressbar"
    :aria-valuenow="Math.round(value)"
    aria-valuemin="0"
    aria-valuemax="100"
  >
    <div
      class="h-full rounded-full transition-[width] duration-300"
      :class="fillClass"
      :style="{ width: `${Math.max(0, Math.min(100, value))}%` }"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  value: { type: Number, default: 0 },
  status: { type: String, default: '' },
  height: { type: Number, default: 8 },
})

const fillClass = computed(() => {
  if (props.status === 'success') return 'bg-success'
  if (props.status === 'error') return 'bg-danger'
  return 'bg-brand'
})
</script>
