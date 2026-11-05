<template>
  <AppMenu
    :actions="actions"
    :label="label"
    :trigger="trigger"
    :placement="placement"
    @select="(key) => emit('select', key)"
  />
</template>

<script setup>
import AppMenu from './ui/AppMenu.vue'

defineProps({
  /** 操作列表：{ key, label, icon, hint?, danger?, disabled? } */
  actions: { type: Array, default: () => [] },
  label: { type: String, default: '' },
  /** hover：桌面行；click：窄屏 / 子项 */
  trigger: { type: String, default: 'click' },
  placement: { type: String, default: 'bottom-start' },
})
const emit = defineEmits(['select'])
</script>
