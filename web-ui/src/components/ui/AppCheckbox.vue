<template>
  <CheckboxRoot
    :model-value="indeterminate ? 'indeterminate' : modelValue"
    :disabled="disabled"
    class="flex h-11 w-11 shrink-0 items-center justify-center rounded-md focus-visible:outline-none disabled:opacity-50"
    :aria-label="label"
    @update:model-value="(value) => emit('update:modelValue', value === true)"
  >
    <span
      class="flex h-5 w-5 items-center justify-center rounded border transition-colors"
      :class="modelValue || indeterminate ? 'border-brand-strong bg-brand-strong text-white' : 'border-line bg-surface text-transparent'"
    >
      <span v-if="indeterminate && !modelValue" class="h-0.5 w-2.5 rounded bg-white" aria-hidden="true" />
      <AppIcon v-else name="check" :size="14" />
    </span>
  </CheckboxRoot>
</template>

<script setup>
import { CheckboxRoot } from 'reka-ui'
import AppIcon from './AppIcon.vue'

defineProps({
  modelValue: { type: Boolean, default: false },
  /** 部分选中：显示横杠而不是勾，语义上与「未全选」兼容。 */
  indeterminate: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  label: { type: String, default: '选择' },
})
const emit = defineEmits(['update:modelValue'])
</script>
