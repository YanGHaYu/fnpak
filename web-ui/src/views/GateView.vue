<template>
  <div class="mx-auto w-full max-w-md py-6 md:py-16">
    <div class="card p-5 md:p-6">
      <div class="mb-4 flex items-start justify-between gap-3">
        <div>
          <h1 class="text-lg font-semibold">访问口令</h1>
          <p class="mt-1 text-xs text-muted">FNPak 只允许持有口令的人打开</p>
        </div>
        <span class="rounded-full bg-surface2 px-2 py-0.5 text-xs text-muted">未登录</span>
      </div>

      <form class="space-y-3" @submit.prevent="submit">
        <AppField label="口令">
          <AppInput
            v-model="passcode"
            type="password"
            placeholder="粘贴 NAS 上的 access-token 内容"
            autocomplete="current-password"
            @submit="submit"
          />
        </AppField>
        <AppAlert v-if="error" tone="error" :message="error" />
        <div class="flex flex-wrap items-center gap-3">
          <AppButton variant="primary" :loading="busy" @click="submit">进入</AppButton>
          <span class="text-xs text-muted">口令文件在 NAS 上：$TRIM_PKGVAR/access-token</span>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AppAlert from '../components/ui/AppAlert.vue'
import AppButton from '../components/ui/AppButton.vue'
import AppField from '../components/ui/AppField.vue'
import AppInput from '../components/ui/AppInput.vue'
import { api, errorDetail } from '../api'
import { markAccessLost, refreshAccess, state } from '../store'

const router = useRouter()
const passcode = ref('')
const error = ref(state.accessMessage || '')
const busy = ref(false)

async function submit() {
  const value = passcode.value.trim()
  if (!value) {
    error.value = '请先输入口令'
    return
  }
  busy.value = true
  error.value = ''
  const result = await api('/api/access/login', {
    method: 'POST',
    body: JSON.stringify({ passcode: value }),
  })
  busy.value = false
  if (!result.ok) {
    error.value = errorDetail(result)
    return
  }
  passcode.value = ''
  markAccessLost('')
  await refreshAccess()
  router.push('/')
}
</script>
