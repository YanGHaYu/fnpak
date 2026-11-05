<template>
  <el-card class="gate" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">访问口令</span>
        <span class="muted" style="font-size: 12px">FNPak 只允许持有口令的人打开</span>
      </div>
    </template>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="口令">
        <el-input
          v-model="passcode"
          type="password"
          show-password
          placeholder="粘贴 NAS 上的 access-token 内容"
          autofocus
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <div class="toolbar" style="margin-top: 12px">
        <el-button type="primary" :loading="busy" @click="submit">进入</el-button>
        <span class="hint">口令文件在 NAS 上：$TRIM_PKGVAR/access-token</span>
      </div>
    </el-form>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
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
