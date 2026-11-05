<template>
  <CheckboxRoot
    :model-value="modelValue"
    :disabled="disabled"
    class="flex h-11 w-11 shrink-0 items-center justify-center rounded-md focus-visible:outline-none disabled:opacity-50"
    :aria-label="label"
    @update:model-value="(value) => emit('update:modelValue', value === true)"
  >
    <span
      class="flex h-5 w-5 items-center justify-center rounded border transition-colors"
      :class="modelValue ? 'border-brand bg-brand text-white' : 'border-line bg-surface text-transparent'"
    >
      <AppIcon name="check" :size="14" />
    </span>
  </CheckboxRoot>
</template>

<script setup>
import { CheckboxRoot } from 'reka-ui'
import AppIcon from './AppIcon.vue'

defineProps({
  modelValue: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  label: { type: String, default: '选择' },
})
const emit = defineEmits(['update:modelValue'])
</script>
