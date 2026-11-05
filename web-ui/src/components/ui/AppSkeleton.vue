<template>
  <div class="animate-pulse rounded-md bg-surface2" :style="{ height: `${height}px`, width }" />
</template>

<script setup>
defineProps({
  height: { type: Number, default: 16 },
  width: { type: String, default: '100%' },
})
</script>
