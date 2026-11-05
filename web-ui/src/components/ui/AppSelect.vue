<template>
  <select
    :value="modelValue"
    :disabled="disabled"
    class="min-h-11 w-full rounded-md border border-line bg-surface px-3 text-sm text-ink focus:border-brand focus-visible:outline-none disabled:opacity-60 md:min-h-10"
    @change="(event) => emit('update:modelValue', event.target.value)"
  >
    <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
    <option v-for="option in options" :key="option.value" :value="option.value" :disabled="option.disabled">
      {{ option.label }}
    </option>
  </select>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: '' },
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])
</script>
