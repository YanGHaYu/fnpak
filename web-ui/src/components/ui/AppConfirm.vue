<template>
  <AppDialog :open="confirmState.open" :title="confirmState.title" @update:open="(value) => !value && resolveConfirm(false)">
    <p class="whitespace-pre-wrap break-words text-sm text-ink">{{ confirmState.message }}</p>
    <template #footer>
      <div class="flex flex-wrap justify-end gap-2">
        <AppButton @click="resolveConfirm(false)">{{ confirmState.cancelText }}</AppButton>
        <AppButton :variant="confirmState.danger ? 'danger' : 'primary'" @click="resolveConfirm(true)">
          {{ confirmState.confirmText }}
        </AppButton>
      </div>
    </template>
  </AppDialog>
</template>

<script setup>
import AppButton from './AppButton.vue'
import AppDialog from './AppDialog.vue'
import { confirmState, resolveConfirm } from './confirm'
</script>
