<template>
  <div>
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-head">
          <span class="title">服务</span>
          <el-tag v-if="settings" size="small" effect="plain">版本 {{ settings.version }}</el-tag>
          <div class="spacer" />
          <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </template>
      <dl v-if="settings" class="kv">
        <dt>运行目录</dt>
        <dd class="mono">{{ settings.home }}</dd>
        <dt>当前下载位置</dt>
        <dd class="mono">{{ settings.downloadDir }}</dd>
        <dt>运行状态</dt>
        <dd>{{ state.health ? `正常 · 已运行 ${state.health.uptimeSeconds} 秒` : '状态未知' }}</dd>
      </dl>
      <p v-else class="hint">{{ loadError || '加载中…' }}</p>
    </el-card>

    <div class="settings-grid" style="margin-top: 12px">
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-head"><span class="title">访问口令</span></div>
        </template>
        <dl v-if="settings" class="kv">
          <dt>当前指纹</dt>
          <dd class="mono">{{ settings.accessToken.fingerprint }}</dd>
          <dt>来源</dt>
          <dd>{{ settings.accessToken.source }}</dd>
          <dt>口令文件</dt>
          <dd class="mono">{{ settings.accessToken.filePath }}</dd>
        </dl>
        <el-alert
          v-if="settings && settings.accessToken.managedExternally"
          type="warning"
          :closable="false"
          show-icon
          title="口令由启动参数 / 环境变量管理，页面不可改（409 token_managed_externally）"
          style="margin-top: 8px"
        />
        <el-form label-position="top" style="margin-top: 10px" @submit.prevent="savePasscode">
          <el-form-item label="新口令（至少 12 字符，字母数字与 - _ .）">
            <el-input
              v-model="newPasscode"
              type="password"
              show-password
              :disabled="passcodeLocked"
              placeholder="12 位以上"
            />
          </el-form-item>
          <el-button type="primary" size="small" :disabled="passcodeLocked" :loading="passcodeBusy" @click="savePasscode">
            更新口令
          </el-button>
          <span class="hint" style="margin-left: 8px">更新后其它会话全部失效，本页继续可用。</span>
          <p v-if="passcodeStatus" :class="passcodeError ? 'error-line' : 'hint'">{{ passcodeStatus }}</p>
        </el-form>
      </el-card>

      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-head">
            <span class="title">下载位置</span>
            <div class="spacer" />
            <el-button size="small" :loading="sharesBusy" @click="loadShares">刷新目录</el-button>
          </div>
        </template>
        <el-form label-position="top">
          <el-form-item label="授权目录">
            <el-select v-model="shareRoot" style="width: 100%" placeholder="选择一个可写目录">
              <el-option
                v-for="share in shares"
                :key="share.path"
                :value="share.path"
                :label="shareLabel(share)"
                :disabled="!share.writable"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="相对路径（可空，留空即目录本身）">
            <el-input v-model="subPath" placeholder="例如 downloads / movies" />
          </el-form-item>
          <div class="toolbar">
            <el-button size="small" :loading="testBusy" @click="submitDir(true)">测试写入</el-button>
            <el-button size="small" type="primary" :loading="saveBusy2" @click="submitDir(false)">保存</el-button>
          </div>
          <p v-if="dirStatus" :class="dirError ? 'error-line' : 'hint'" style="margin-top: 8px">{{ dirStatus }}</p>
          <p v-if="sharesNote" class="hint" style="margin-top: 4px">{{ sharesNote }}</p>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="never" class="card" style="margin-top: 12px">
      <template #header>
        <div class="card-head"><span class="title">下载并发</span></div>
      </template>
      <el-form label-position="top" :inline="true">
        <el-form-item label="每任务分段数（1–32）">
          <el-input-number v-model="segments" :min="1" :max="32" size="small" />
        </el-form-item>
        <el-form-item label="全局连接预算（4–64）">
          <el-input-number v-model="maxConnections" :min="4" :max="64" size="small" />
        </el-form-item>
        <el-form-item>
          <el-button size="small" type="primary" :loading="tuningBusy" @click="saveTuning">保存</el-button>
        </el-form-item>
      </el-form>
      <p class="hint">
        分段数决定单个文件并发几条 Range 连接；连接预算是所有任务的总上限（活跃任务 × 每任务段数不会再直接放大成连接数）。
        改完对**新任务**生效，已在跑的任务沿用启动时的段数。
      </p>
      <p v-if="tuningStatus" :class="tuningError ? 'error-line' : 'hint'" style="margin-top: 4px">
        {{ tuningStatus }}
      </p>
    </el-card>

    <el-card shadow="never" class="card" style="margin-top: 12px">
      <template #header>
        <div class="card-head">
          <span class="title">日志与故障</span>
          <div class="spacer" />
          <el-button size="small" @click="clearLogs">清空</el-button>
          <el-button size="small" @click="copyDiagnostics">复制诊断</el-button>
        </div>
      </template>
      <p class="hint">
        服务端日志在 NAS 上：<span class="mono">$TRIM_PKGVAR/logs/</span>；这里只保留本页最近 40 条接口错误。
      </p>
      <div class="log-list">
        <div v-for="(entry, index) in state.logs" :key="index">
          {{ entry.time }} · {{ entry.status }} {{ entry.code }} · {{ entry.path }} · {{ entry.message }}
        </div>
        <div v-if="!state.logs.length" class="muted">最近没有接口错误</div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, errorDetail } from '../api'
import { state } from '../store'

const settings = ref(null)
const loading = ref(false)
const loadError = ref('')

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

const segments = ref(8)
const maxConnections = ref(32)
const tuningBusy = ref(false)
const tuningStatus = ref('')
const tuningError = ref(false)

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
  segments.value = result.data.segmentCount ?? 8
  maxConnections.value = result.data.maxConnections ?? 32
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
    body: JSON.stringify({ segments: segments.value, maxConnections: maxConnections.value }),
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
    ElMessage.success('诊断信息已复制')
  } catch (_) {
    ElMessage.warning('浏览器不允许访问剪贴板')
  }
}

onMounted(loadAll)
</script>
