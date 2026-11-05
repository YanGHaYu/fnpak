<template>
  <label class="block">
    <span v-if="label" class="mb-1 block text-xs font-medium text-muted">{{ label }}</span>
    <slot />
    <span v-if="hint" class="mt-1 block text-xs text-muted">{{ hint }}</span>
  </label>
</template>

<script setup>
defineProps({
  label: { type: String, default: '' },
  hint: { type: String, default: '' },
})
</script>
