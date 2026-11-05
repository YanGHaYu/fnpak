<template>
  <div class="space-y-3 md:space-y-4">
    <section class="card p-4">
      <header class="mb-3 flex flex-wrap items-center gap-2">
        <h2 class="text-base font-semibold">服务</h2>
        <span v-if="settings" class="rounded-full bg-surface2 px-2 py-0.5 text-xs text-muted">
          FNPak · 非官方 · 版本 {{ settings.version }}
        </span>
        <span class="flex-1" />
        <AppButton size="sm" variant="ghost" icon="refresh" :loading="loading" @click="loadAll">刷新</AppButton>
      </header>
      <dl v-if="settings" class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
        <dt class="text-muted">运行目录</dt>
        <dd class="truncate font-mono text-xs leading-6" :title="settings.home">{{ settings.home }}</dd>
        <dt class="text-muted">当前下载位置</dt>
        <dd class="truncate font-mono text-xs leading-6" :title="settings.downloadDir">{{ settings.downloadDir }}</dd>
        <dt class="text-muted">运行状态</dt>
        <dd>{{ state.health ? `正常 · 已运行 ${state.health.uptimeSeconds} 秒` : '状态未知' }}</dd>
      </dl>
      <p v-else class="text-sm text-muted">{{ loadError || '加载中…' }}</p>
    </section>

    <div class="grid gap-3 md:grid-cols-2 md:gap-4">
      <section class="card p-4">
        <h2 class="mb-3 text-base font-semibold">访问口令</h2>
        <dl v-if="settings" class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
          <dt class="text-muted">当前指纹</dt>
          <dd class="truncate font-mono text-xs leading-6">{{ settings.accessToken.fingerprint }}</dd>
          <dt class="text-muted">来源</dt>
          <dd>{{ settings.accessToken.source }}</dd>
          <dt class="text-muted">口令文件</dt>
          <dd class="truncate font-mono text-xs leading-6" :title="settings.accessToken.filePath">
            {{ settings.accessToken.filePath }}
          </dd>
        </dl>
        <AppAlert
          v-if="settings && settings.accessToken.managedExternally"
          class="mt-2"
          tone="warning"
          message="口令由启动参数 / 环境变量管理，页面不可改（409 token_managed_externally）"
        />
        <form class="mt-3 space-y-2" @submit.prevent="savePasscode">
          <AppField label="新口令（至少 12 字符，字母数字与 - _ .）">
            <AppInput
              v-model="newPasscode"
              type="password"
              :disabled="passcodeLocked"
              placeholder="12 位以上"
              autocomplete="new-password"
            />
          </AppField>
          <div class="flex flex-wrap items-center gap-2">
            <AppButton size="sm" variant="primary" :disabled="passcodeLocked" :loading="passcodeBusy" @click="savePasscode">
              更新口令
            </AppButton>
            <span class="text-xs text-muted">更新后其它会话全部失效，本页继续可用。</span>
          </div>
          <p v-if="passcodeStatus" class="text-xs" :class="passcodeError ? 'text-danger' : 'text-muted'">
            {{ passcodeStatus }}
          </p>
        </form>
      </section>

      <section class="card p-4">
        <header class="mb-3 flex items-center gap-2">
          <h2 class="text-base font-semibold">下载位置</h2>
          <span class="flex-1" />
          <AppButton size="sm" variant="ghost" :loading="sharesBusy" @click="loadShares">刷新目录</AppButton>
        </header>
        <div class="space-y-3">
          <AppField label="授权目录">
            <AppSelect v-model="shareRoot" :options="shareOptions" placeholder="选择一个可写目录" />
          </AppField>
          <AppField label="相对路径（可空，留空即目录本身）">
            <AppInput v-model="subPath" placeholder="例如 downloads / movies" />
          </AppField>
          <div class="flex flex-wrap gap-2">
            <AppButton size="sm" :loading="testBusy" @click="submitDir(true)">测试写入</AppButton>
            <AppButton size="sm" variant="primary" :loading="saveBusy2" @click="submitDir(false)">保存</AppButton>
          </div>
          <p v-if="dirStatus" class="text-xs" :class="dirError ? 'text-danger' : 'text-muted'">{{ dirStatus }}</p>
          <p v-if="sharesNote" class="text-xs text-muted">{{ sharesNote }}</p>
        </div>
      </section>
    </div>

    <section class="card p-4">
      <h2 class="mb-3 text-base font-semibold">账号</h2>
      <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
        <dt class="text-muted">当前账号</dt>
        <dd>{{ state.auth.authenticated ? (state.auth.displayName || state.auth.account || '已登录') : '未登录' }}</dd>
        <dt class="text-muted">登录状态</dt>
        <dd>{{ state.auth.authenticated ? '已登录' : '未登录' }}</dd>
      </dl>
      <div class="mt-3 flex flex-wrap items-center gap-2">
        <AppButton size="sm" variant="danger-soft" :loading="logoutBusy" @click="logout">退出登录</AppButton>
        <span class="text-xs text-muted">会清除本机登录态，云端文件不受影响</span>
      </div>
    </section>

    <section class="card p-4">
      <h2 class="mb-3 text-base font-semibold">下载并发</h2>
      <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_auto] lg:items-end">
        <AppField label="每任务分段数（1–32）">
          <AppInput v-model="segments" type="number" compact />
        </AppField>
        <AppField label="全局连接预算（4–64）">
          <AppInput v-model="maxConnections" type="number" compact />
        </AppField>
        <AppButton size="sm" variant="primary" :loading="tuningBusy" @click="saveTuning">保存</AppButton>
      </div>
      <p class="mt-2 text-xs text-muted">
        分段数决定单个文件并发几条 Range 连接；连接预算是所有任务的总上限（活跃任务 × 每任务段数不会再直接放大成连接数）。
        改完对**新任务**生效，已在跑的任务沿用启动时的段数。
      </p>
      <p v-if="tuningStatus" class="mt-1 text-xs" :class="tuningError ? 'text-danger' : 'text-muted'">
        {{ tuningStatus }}
      </p>
    </section>

    <section class="card p-4">
      <header class="mb-2 flex items-center gap-2">
        <h2 class="text-base font-semibold">日志与故障</h2>
        <span class="flex-1" />
        <AppButton size="sm" variant="ghost" @click="clearLogs">清空</AppButton>
        <AppButton size="sm" variant="ghost" icon="copy" @click="copyDiagnostics">复制诊断</AppButton>
      </header>
      <p class="mb-2 text-xs text-muted">
        服务端日志在 NAS 上：<span class="font-mono">$TRIM_PKGVAR/logs/</span>；这里只保留本页最近 40 条接口错误。
      </p>
      <div class="max-h-64 space-y-1 overflow-auto rounded-md bg-surface2 p-2 font-mono text-xs">
        <div v-for="(entry, index) in state.logs" :key="index" class="break-words">
          {{ entry.time }} · {{ entry.status }} {{ entry.code }} · {{ entry.path }} · {{ entry.message }}
        </div>
        <div v-if="!state.logs.length" class="text-muted">最近没有接口错误</div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppAlert from '../components/ui/AppAlert.vue'
import AppButton from '../components/ui/AppButton.vue'
import AppField from '../components/ui/AppField.vue'
import AppInput from '../components/ui/AppInput.vue'
import AppSelect from '../components/ui/AppSelect.vue'
import { toast } from '../components/ui/toast'
import { api, errorDetail } from '../api'
import { refreshAuth, state } from '../store'

const router = useRouter()
const settings = ref(null)
const loading = ref(false)
const loadError = ref('')
const logoutBusy = ref(false)

const newPasscode = ref('')
const passcodeBusy = ref(false)
const passcodeStatus = ref('')
const passcodeError = ref(false)
const passcodeLocked = computed(() => !!settings.value?.accessToken?.managedExternally)

const shares = ref([])
const sharesNote = ref('')
const sharesBusy = ref(false)
const shareRoot = ref('')
const subPath = ref('')
const testBusy = ref(false)
const saveBusy2 = ref(false)
const dirStatus = ref('')
const dirError = ref(false)

const segments = ref('8')
const maxConnections = ref('32')
const tuningBusy = ref(false)
const tuningStatus = ref('')
const tuningError = ref(false)

const shareOptions = computed(() =>
  shares.value.map((share) => ({
    value: share.path,
    label: shareLabel(share),
    disabled: !share.writable,
  })),
)
const shareLabel = (share) =>
  share.writable ? share.path : `${share.path}（只读：${share.reason || '不可写'}）`

async function loadAll() {
  loading.value = true
  loadError.value = ''
  const result = await api('/api/settings')
  loading.value = false
  if (!result.ok) {
    loadError.value = errorDetail(result)
    return
  }
  settings.value = result.data
  segments.value = String(result.data.segmentCount ?? 8)
  maxConnections.value = String(result.data.maxConnections ?? 32)
  await loadShares()
}

async function loadShares() {
  sharesBusy.value = true
  const result = await api('/api/settings/shares')
  sharesBusy.value = false
  if (!result.ok) {
    sharesNote.value = errorDetail(result)
    return
  }
  shares.value = result.data?.shares || []
  sharesNote.value = result.data?.note || ''
  if (!shares.value.some((share) => share.path === shareRoot.value && share.writable)) {
    shareRoot.value = shares.value.find((share) => share.writable)?.path || ''
  }
}

async function savePasscode() {
  passcodeBusy.value = true
  passcodeStatus.value = ''
  passcodeError.value = false
  const result = await api('/api/settings/access-token', {
    method: 'POST',
    body: JSON.stringify({ newPasscode: newPasscode.value.trim() }),
  })
  passcodeBusy.value = false
  if (!result.ok) {
    passcodeStatus.value = errorDetail(result)
    passcodeError.value = true
    return
  }
  newPasscode.value = ''
  passcodeStatus.value = '口令已更新：其它会话全部失效，本页继续可用。'
  await loadAll()
}

async function submitDir(dryRun) {
  if (!shareRoot.value) {
    dirStatus.value = '请先选择一个授权目录'
    dirError.value = true
    return
  }
  if (dryRun) testBusy.value = true
  else saveBusy2.value = true
  dirStatus.value = dryRun ? '测试中…' : '保存中…'
  dirError.value = false
  const result = await api('/api/settings/download-dir', {
    method: 'POST',
    body: JSON.stringify({ shareRoot: shareRoot.value, subPath: subPath.value.trim(), dryRun }),
  })
  if (dryRun) testBusy.value = false
  else saveBusy2.value = false
  if (!result.ok) {
    dirStatus.value = errorDetail(result)
    dirError.value = true
    return
  }
  if (dryRun) {
    dirStatus.value = `可写：${result.data.downloadDir}（尚未保存）`
    return
  }
  dirStatus.value = `已保存：${result.data.downloadDir}。新任务立即生效，进行中的任务继续用旧目录。`
  await loadAll()
}

async function saveTuning() {
  tuningBusy.value = true
  tuningStatus.value = ''
  tuningError.value = false
  const result = await api('/api/settings/download-tuning', {
    method: 'POST',
    body: JSON.stringify({
      segments: Number(segments.value),
      maxConnections: Number(maxConnections.value),
    }),
  })
  tuningBusy.value = false
  if (!result.ok) {
    tuningStatus.value = errorDetail(result)
    tuningError.value = true
    return
  }
  tuningStatus.value = `已保存：每任务 ${result.data.segments} 段，全局连接上限 ${result.data.maxConnections}（新任务生效）`
  await loadAll()
}

function clearLogs() {
  state.logs.splice(0, state.logs.length)
}

async function copyDiagnostics() {
  const text = JSON.stringify(
    { version: settings.value?.version, downloadDir: settings.value?.downloadDir, health: state.health, logs: state.logs },
    null,
    2,
  )
  try {
    await navigator.clipboard.writeText(text)
    toast.success('诊断信息已复制')
  } catch (_) {
    toast.warning('浏览器不允许访问剪贴板')
  }
}

/**
 * 退出登录：只清本机 PikPak 会话（access/refresh token），不动云端文件。
 * 清完后回到首页的账号/口令入口，由用户重新登录。
 */
async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true
  await api('/api/auth/logout', { method: 'POST' })
  logoutBusy.value = false
  await refreshAuth()
  router.push('/')
}

onMounted(async () => {
  await refreshAuth()
  await loadAll()
})
</script>
