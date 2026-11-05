<template>
  <el-card class="gate setup" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">首次设置访问口令</span>
        <span class="muted" style="font-size: 12px">FNPak 还没有访问口令，设置后才能进入</span>
      </div>
    </template>
    <el-alert
      v-if="!state.setup.setupWindowOpen"
      type="warning"
      show-icon
      :closable="false"
      title="首次设置窗口已关闭"
      description="请用命令行重置：删除数据目录下的 access-token 文件（若存在）后重启服务，即可重新进入设置向导。"
      style="margin-bottom: 12px"
    />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="口令">
        <el-input
          v-model="passcode"
          type="password"
          show-password
          placeholder="至少 12 个字符，只能用字母、数字和 - _ ."
          autofocus
          :disabled="!state.setup.setupWindowOpen"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-form-item label="确认口令">
        <el-input
          v-model="confirmPasscode"
          type="password"
          show-password
          placeholder="再输入一次"
          :disabled="!state.setup.setupWindowOpen"
          @keyup.enter="submit"
        />
      </el-form-item>
      <div class="muted" style="font-size: 12px; margin-bottom: 8px">
        规则：长度 ≥ 12，仅允许 <code>A-Z a-z 0-9 . _ -</code>；设置成功后直接建立会话并进入首页。
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <div class="toolbar" style="margin-top: 12px">
        <el-button type="primary" :loading="busy" :disabled="!state.setup.setupWindowOpen" @click="submit">
          设置并进入
        </el-button>
        <span class="hint">
          窗口默认 15 分钟，可用 <code>--setup-window-minutes</code> 调整（0 = 不限）
        </span>
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
    // 窗口关闭时同步刷新状态，让页面显示命令行重置提示
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
