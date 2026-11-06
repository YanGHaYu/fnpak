<template>
  <AppDialog
    :open="visible"
    :title="item ? `在线解压：${item.name}` : '在线解压'"
    description="由 PikPak 服务端解压到压缩包所在目录，本机不下载内容"
    mobile-full
    @update:open="(value) => visible = value"
  >
    <div class="space-y-3">
      <AppAlert
        v-if="phase === 'checking'"
        :title="checkingTitle"
        message="正在向云端确认压缩包是否需要密码，稍等片刻。"
      />

      <template v-else-if="phase === 'password'">
        <AppAlert
          tone="warning"
          title="这个压缩包需要密码"
          message="输入解压密码后会先校验一次；密码只在本次请求体里传递。"
        />
        <AppField label="解压密码">
          <AppInput
            v-model="password"
            type="password"
            placeholder="请输入解压密码"
            autocomplete="off"
            maxlength="128"
            @submit="submitPassword"
          />
        </AppField>
        <p v-if="passwordError" class="text-xs text-danger">{{ passwordError }}</p>
      </template>

      <template v-else-if="phase === 'running'">
        <div class="space-y-2">
          <div class="flex items-center justify-between text-sm">
            <span>正在解压…</span>
            <span class="tabular-nums text-muted">{{ progress }}%</span>
          </div>
          <AppProgress :value="progress" />
          <p class="text-xs text-muted">可以关闭窗口；关闭不会取消服务端任务，完成后刷新目录即可看到产物。</p>
        </div>
      </template>

      <template v-else-if="phase === 'done'">
        <AppAlert
          tone="success"
          title="解压完成"
          :message="outputName ? `已解压到当前目录：${outputName}` : '产物已出现在当前目录，列表已自动刷新。'"
        />
        <p v-if="outputFileId" class="break-all text-xs text-muted">产物 ID：{{ outputFileId }}</p>
      </template>

      <template v-else-if="phase === 'failed'">
        <AppAlert tone="error" :title="failureTitle" :message="failureMessage" />
      </template>
    </div>

    <template #footer>
      <div class="flex flex-wrap justify-end gap-2">
        <AppButton v-if="phase === 'password'" @click="visible = false">取消</AppButton>
        <AppButton v-else-if="phase === 'failed'" @click="restart">重试</AppButton>
        <AppButton v-else @click="visible = false">关闭</AppButton>
        <AppButton
          v-if="phase === 'password'"
          variant="primary"
          :disabled="!password.trim() || submitting"
          @click="submitPassword"
        >
          {{ submitting ? '校验中…' : '确认解压' }}
        </AppButton>
      </div>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import AppAlert from './ui/AppAlert.vue'
import AppButton from './ui/AppButton.vue'
import AppDialog from './ui/AppDialog.vue'
import AppField from './ui/AppField.vue'
import AppInput from './ui/AppInput.vue'
import AppProgress from './ui/AppProgress.vue'
import { archiveErrorCode, archiveErrorText, archiveExtract, archiveList, archiveTask } from '../archive'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  item: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'done'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const phase = ref('checking')
const checkingTitle = ref('正在准备解压…')
const password = ref('')
const passwordError = ref('')
const submitting = ref(false)
const progress = ref(0)
const outputFileId = ref('')
const outputName = ref('')
const failureTitle = ref('解压失败')
const failureMessage = ref('')

let timer = null
let pollAttempts = 0
let runId = 0

const isPasswordError = (result) => {
  const code = archiveErrorCode(result)
  return code === 'unzip_password_incorrect' || code === 'unzip_password_required'
}

function stopTimer() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function reset() {
  runId += 1
  stopTimer()
  phase.value = 'checking'
  checkingTitle.value = '正在准备解压…'
  password.value = ''
  passwordError.value = ''
  submitting.value = false
  progress.value = 0
  outputFileId.value = ''
  outputName.value = ''
  failureTitle.value = '解压失败'
  failureMessage.value = ''
  pollAttempts = 0
}

function fail(code, message) {
  phase.value = 'failed'
  failureTitle.value = code === 'unsupported_format' ? '无法解压' : '解压失败'
  failureMessage.value = message
}

async function start() {
  reset()
  if (!props.modelValue || !props.item) return
  const currentRun = runId
  const checking = await archiveList(props.item.id)
  if (currentRun !== runId || !props.modelValue) return
  if (checking.ok) return beginExtract('', currentRun)
  if (isPasswordError(checking)) {
    phase.value = 'password'
    return
  }
  fail(archiveErrorCode(checking), archiveErrorText(checking))
}

async function submitPassword() {
  if (!props.item || submitting.value) return
  const value = password.value.trim()
  if (!value) {
    passwordError.value = '请输入密码'
    return
  }
  submitting.value = true
  passwordError.value = ''
  const currentRun = runId
  const result = await archiveList(props.item.id, value)
  if (currentRun !== runId) return
  submitting.value = false
  if (result.ok) return beginExtract(value, currentRun)
  if (archiveErrorCode(result) === 'unzip_password_incorrect') {
    passwordError.value = '密码不正确，请重新输入'
    return
  }
  if (archiveErrorCode(result) === 'unzip_password_required') {
    passwordError.value = '请输入解压密码'
    return
  }
  fail(archiveErrorCode(result), archiveErrorText(result))
}

async function beginExtract(secret, currentRun) {
  phase.value = 'checking'
  checkingTitle.value = '正在提交解压任务…'
  const result = await archiveExtract(props.item.id, secret)
  if (currentRun !== runId || !props.modelValue) return
  if (!result.ok) {
    if (isPasswordError(result)) {
      phase.value = 'password'
      passwordError.value = archiveErrorCode(result) === 'unzip_password_incorrect' ? '密码不正确，请重新输入' : ''
      return
    }
    return fail(archiveErrorCode(result), archiveErrorText(result))
  }
  progress.value = 0
  pollAttempts = 0
  phase.value = 'running'
  schedulePoll(result.data?.taskId || '', currentRun)
}

function schedulePoll(taskId, currentRun) {
  stopTimer()
  timer = setTimeout(async () => {
    timer = null
    if (currentRun !== runId || !props.modelValue) return
    if (!taskId) return fail('archive_task_missing', '服务端没有返回解压任务 ID')
    pollAttempts += 1
    if (pollAttempts > 130) return fail('extract_timeout', '解压超时，请稍后重试')
    const result = await archiveTask(taskId)
    if (currentRun !== runId || !props.modelValue) return
    if (!result.ok) return fail(archiveErrorCode(result), archiveErrorText(result))
    const state = result.data || {}
    progress.value = Math.max(0, Math.min(100, Number(state.progress) || 0))
    if (state.status === 'done') {
      phase.value = 'done'
      progress.value = 100
      outputFileId.value = state.outputFileId || ''
      outputName.value = state.outputName || ''
      emit('done')
      return
    }
    if (state.status === 'failed') {
      if (state.errorCode === 'unzip_password_incorrect' || state.errorCode === 'unzip_password_required') {
        phase.value = 'password'
        passwordError.value = '密码不正确，请重新输入'
        return
      }
      return fail(state.errorCode || 'archive_extract_failed', state.message || '解压失败，请稍后重试')
    }
    schedulePoll(taskId, currentRun)
  }, 500)
}

function restart() {
  if (props.modelValue) start()
}

watch(() => props.modelValue, (open) => {
  if (open) start()
  else {
    runId += 1
    stopTimer()
  }
})
watch(() => props.item?.id, () => {
  if (props.modelValue) start()
})
onUnmounted(() => {
  runId += 1
  stopTimer()
})
</script>
