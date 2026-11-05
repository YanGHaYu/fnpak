<template>
  <div class="mx-auto w-full max-w-md py-6 md:py-16">
    <div class="card p-5 md:p-6">
      <div class="mb-4 flex items-start justify-between gap-3">
        <div>
          <h1 class="text-lg font-semibold">首次设置访问口令</h1>
          <p class="mt-1 text-xs text-muted">FNPak 还没有访问口令，设置后才能进入</p>
        </div>
      </div>

      <AppAlert
        v-if="!state.setup.setupWindowOpen"
        tone="warning"
        title="首次设置窗口已关闭"
        message="请用命令行重置：删除数据目录下的 access-token 文件（若存在）后重启服务，即可重新进入设置向导。"
        class="mb-3"
      />

      <form class="space-y-3" @submit.prevent="submit">
        <AppField label="口令">
          <AppInput
            v-model="passcode"
            type="password"
            placeholder="至少 12 个字符，只能用字母、数字和 - _ ."
            :disabled="!state.setup.setupWindowOpen"
            @submit="submit"
          />
        </AppField>
        <AppField label="确认口令">
          <AppInput
            v-model="confirmPasscode"
            type="password"
            placeholder="再输入一次"
            :disabled="!state.setup.setupWindowOpen"
            @submit="submit"
          />
        </AppField>
        <p class="text-xs text-muted">
          规则：长度 ≥ 12，仅允许 <span class="font-mono">A-Z a-z 0-9 . _ -</span>；设置成功后直接建立会话并进入首页。
        </p>
        <AppAlert v-if="error" tone="error" :message="error" />
        <div class="flex flex-wrap items-center gap-3">
          <AppButton
            variant="primary"
            :loading="busy"
            :disabled="!state.setup.setupWindowOpen"
            @click="submit"
          >
            设置并进入
          </AppButton>
          <span class="text-xs text-muted">
            窗口默认 15 分钟，可用 <span class="font-mono">--setup-window-minutes</span> 调整（0 = 不限）
          </span>
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
const confirmPasscode = ref('')
const error = ref('')
const busy = ref(false)

const PATTERN = /^[A-Za-z0-9._-]{12,}$/

async function submit() {
  const value = passcode.value.trim()
  const confirm = confirmPasscode.value.trim()
  error.value = ''
  if (value.length < 12) {
    error.value = `口令至少 12 个字符（当前 ${value.length}）`
    return
  }
  if (!PATTERN.test(value)) {
    error.value = '口令只能用字母、数字和 - _ .'
    return
  }
  if (confirm && value !== confirm) {
    error.value = '两次输入的口令不一致'
    return
  }
  busy.value = true
  const result = await api('/api/setup/access-token', {
    method: 'POST',
    body: JSON.stringify({ passcode: value, confirmPasscode: confirm || value }),
  })
  busy.value = false
  if (!result.ok) {
    error.value = errorDetail(result)
    if (result.status === 403) state.setup.setupWindowOpen = false
    return
  }
  state.setup.needsSetup = false
  passcode.value = ''
  confirmPasscode.value = ''
  markAccessLost('')
  await refreshAccess()
  router.push('/')
}
</script>
