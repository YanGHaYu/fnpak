<template>
  <input
    :value="modelValue"
    :type="type"
    :placeholder="placeholder"
    :disabled="disabled"
    :autocomplete="autocomplete"
    :maxlength="maxlength"
    class="w-full rounded-md border border-line bg-surface px-3 text-sm text-ink placeholder:text-muted/70 focus:border-brand focus-visible:outline-none disabled:opacity-60"
    :class="compact ? 'min-h-10' : 'min-h-11'"
    @input="(event) => emit('update:modelValue', event.target.value)"
    @keyup.enter="emit('submit')"
  />
</template>

<script setup>
const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  type: { type: String, default: 'text' },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  autocomplete: { type: String, default: 'off' },
  maxlength: { type: [String, Number], default: undefined },
  compact: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'submit'])
</script>
