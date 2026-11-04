<template>
  <div>
    <!-- 未登录 PikPak：账号密码 / AccessToken 两种入口 -->
    <el-card v-if="!state.auth.authenticated" shadow="never" class="card">
      <template #header>
        <div class="card-head">
          <span class="title">连接 PikPak</span>
          <el-tag size="small" effect="plain">{{ state.auth.initializing ? '恢复登录态…' : '未登录' }}</el-tag>
        </div>
      </template>

      <el-tabs v-model="loginTab">
        <el-tab-pane label="账号密码" name="password">
          <el-form label-position="top" @submit.prevent="submitSignin">
            <el-form-item label="账号（手机号 / 邮箱）">
              <el-input v-model="signin.account" placeholder="账号" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="signin.password"
                type="password"
                show-password
                placeholder="密码"
                autocomplete="current-password"
                @keyup.enter="submitSignin"
              />
            </el-form-item>
            <el-alert
              v-if="captcha.visible"
              :title="captcha.note || '需要验证码挑战'"
              type="info"
              :closable="false"
              show-icon
            >
              <div class="toolbar" style="margin-top: 6px">
                <el-input v-model="captcha.answer" style="max-width: 220px" placeholder="验证码（没有图片可留空）" />
                <span class="hint">{{ captcha.ttl }}</span>
                <el-button size="small" :loading="captchaBusy" @click="requestCaptcha">刷新验证码</el-button>
              </div>
            </el-alert>
            <div class="toolbar" style="margin-top: 10px">
              <el-button type="primary" :loading="signinBusy" @click="submitSignin">登录</el-button>
            </div>
            <p v-if="signinError" class="error-line">{{ signinError }}</p>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="AccessToken" name="token">
          <el-form label-position="top" @submit.prevent="submitToken">
            <el-form-item label="账号">
              <el-input v-model="tokenForm.account" placeholder="账号" />
            </el-form-item>
            <el-form-item label="AccessToken">
              <el-input v-model="tokenForm.accessToken" type="password" show-password placeholder="accessToken" />
            </el-form-item>
            <el-form-item label="RefreshToken（可选）">
              <el-input v-model="tokenForm.refreshToken" type="password" show-password placeholder="refreshToken" />
            </el-form-item>
            <el-button type="primary" :loading="tokenBusy" @click="submitToken">导入</el-button>
            <p v-if="tokenError" class="error-line">{{ tokenError }}</p>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <template v-else>
      <!-- 目录浏览 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-head">
            <span class="title">目录</span>
            <el-tag size="small" effect="plain">{{ state.auth.displayName || state.auth.account }}</el-tag>
            <span class="muted">{{ listStatus }}</span>
            <div class="spacer" />
            <div class="toolbar">
              <el-button size="small" :disabled="crumbs.length === 1" @click="up">上一级</el-button>
              <el-button size="small" :loading="listBusy" @click="loadList">刷新</el-button>
              <el-button size="small" text @click="logout">退出登录</el-button>
            </div>
          </div>
        </template>

        <div class="crumbs" style="margin-bottom: 8px">
          <el-link
            v-for="(crumb, index) in crumbs"
            :key="crumb.id + index"
            type="primary"
            :underline="false"
            @click="jumpTo(index)"
          >
            {{ index === 0 ? '根目录' : crumb.name }}
            <span v-if="index < crumbs.length - 1" class="muted">&nbsp;/&nbsp;</span>
          </el-link>
        </div>

        <el-table :data="items" size="small" empty-text="这个目录是空的" @row-click="onRowClick">
          <el-table-column prop="name" label="名称" min-width="240">
            <template #default="{ row }">
              <span :class="{ mono: !row.isFolder }">{{ row.name || row.id }}</span>
              <el-tag v-if="row.isFolder" size="small" type="info" effect="plain" style="margin-left: 6px">目录</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="110" align="right">
            <template #default="{ row }">
              <span class="num">{{ row.isFolder ? '' : formatSize(row.sizeBytes) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="mimeType" label="类型" width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="170" align="right">
            <template #default="{ row }">
              <div class="row-actions" @click.stop>
                <el-button v-if="row.isVideo" size="small" @click="openPlayer(row)">播放</el-button>
                <el-button v-if="!row.isFolder" size="small" type="primary" plain @click="startDownload(row.id)">下载</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <p class="hint" style="margin: 8px 0 0">
          只展示第一页；点目录名进入下一级。下载走服务端分段直链，落盘到设置里的下载位置。
        </p>
      </el-card>

      <!-- 本机下载 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-head">
            <span class="title">本机下载</span>
            <span class="muted">{{ downloads.length }} 个任务</span>
            <div class="spacer" />
            <el-button size="small" @click="loadDownloads">刷新</el-button>
          </div>
        </template>
        <el-table :data="downloads" size="small" empty-text="还没有下载任务">
          <el-table-column prop="fileName" label="文件" min-width="220" show-overflow-tooltip />
          <el-table-column label="进度" min-width="200">
            <template #default="{ row }">
              <div class="progress-cell">
                <el-progress
                  :percentage="Number(row.percent) || 0"
                  :status="progressStatus(row.status)"
                  :stroke-width="10"
                />
                <span class="hint mono">{{ formatSize(row.bytesDone) }} / {{ formatSize(row.bytesTotal) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="速率" width="100" align="right">
            <template #default="{ row }">
              <span class="num">{{ formatSpeed(row.speedBytesPerSec) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
              <div v-if="row.error" class="error-line">{{ row.error }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" align="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button size="small" @click="downloadAction(row.id, 'pause')">暂停</el-button>
                <el-button size="small" type="primary" plain @click="downloadAction(row.id, 'resume')">继续</el-button>
                <el-button size="small" type="danger" plain @click="downloadAction(row.id, 'cancel')">取消</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="downloadHint" class="hint" style="margin: 8px 0 0">{{ downloadHint }}</p>
      </el-card>

      <!-- 离线任务 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-head">
            <span class="title">离线任务</span>
            <div class="spacer" />
            <el-button size="small" @click="loadTasks">刷新</el-button>
          </div>
        </template>
        <el-table :data="tasks" size="small" empty-text="没有离线任务">
          <el-table-column prop="id" label="任务" min-width="200" show-overflow-tooltip />
          <el-table-column label="进度" width="90" align="right">
            <template #default="{ row }"><span class="num">{{ row.progress }}%</span></template>
          </el-table-column>
          <el-table-column prop="phase" label="阶段" width="130" />
          <el-table-column prop="message" label="说明" min-width="200" show-overflow-tooltip />
        </el-table>
      </el-card>

      <!-- 链接下载 -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-head">
            <span class="title">链接 / 磁力 / 分享</span>
          </div>
        </template>
        <div class="toolbar">
          <el-input v-model="linkForm.link" placeholder="粘贴直链 / 磁力 / 分享链接" style="flex: 1; min-width: 260px" />
          <el-input v-model="linkForm.passCode" placeholder="提取码（可选）" style="width: 140px" />
          <el-button size="small" :loading="parseBusy" @click="parseLink">解析</el-button>
          <el-button size="small" type="primary" plain :disabled="!parsed" :loading="saveBusy" @click="saveLink">
            存到当前目录
          </el-button>
        </div>
        <p v-if="linkStatus" class="hint" style="margin: 8px 0 0">{{ linkStatus }}</p>
        <el-table v-if="linkFiles.length" :data="linkFiles" size="small" style="margin-top: 8px">
          <el-table-column prop="name" label="文件" min-width="220" show-overflow-tooltip />
          <el-table-column label="大小" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ formatSize(row.sizeBytes) }}</span></template>
          </el-table-column>
          <el-table-column label="方式" width="120">
            <template #default="{ row }">{{ row.instantReady ? '可秒传' : '需离线下载' }}</template>
          </el-table-column>
        </el-table>
        <p v-if="saveStatus" class="hint" style="margin: 8px 0 0">{{ saveStatus }}</p>
      </el-card>
    </template>

    <PlayerDialog ref="playerRef" v-model="playerOpen" :item="playingItem" />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import PlayerDialog from '../components/PlayerDialog.vue'
import { api, errorDetail, errorText, formatSize, formatSpeed } from '../api'
import { refreshAuth, state } from '../store'

const loginTab = ref('password')
const signin = reactive({ account: '', password: '' })
const signinError = ref('')
const signinBusy = ref(false)
const captcha = reactive({ visible: false, id: '', note: '', ttl: '', answer: '' })
const captchaBusy = ref(false)
const tokenForm = reactive({ account: '', accessToken: '', refreshToken: '' })
const tokenError = ref('')
const tokenBusy = ref(false)

const crumbs = ref([{ id: '', name: '根目录' }])
const items = ref([])
const listStatus = ref('')
const listBusy = ref(false)
const currentParentId = computed(() => crumbs.value[crumbs.value.length - 1].id)

const downloads = ref([])
const tasks = ref([])
const downloadHint = ref('')

const linkForm = reactive({ link: '', passCode: '' })
const parsed = ref(null)
const linkFiles = ref([])
const linkStatus = ref('')
const saveStatus = ref('')
const parseBusy = ref(false)
const saveBusy = ref(false)

const playerOpen = ref(false)
const playingItem = ref(null)
const playerRef = ref(null)

let events = null
let authTimer = null

const statusType = (status) =>
  status === 'FAILED' ? 'danger' : status === 'COMPLETED' ? 'success' : status === 'DOWNLOADING' ? 'primary' : 'info'
const progressStatus = (status) =>
  status === 'COMPLETED' ? 'success' : status === 'FAILED' ? 'exception' : undefined

function hideCaptcha() {
  captcha.visible = false
  captcha.id = ''
  captcha.answer = ''
  captcha.ttl = ''
}

function applyCaptcha(data) {
  captcha.visible = true
  captcha.id = data.challengeId || ''
  captcha.note = data.note || ''
  captcha.ttl = data.expiresInSec ? `有效期约 ${data.expiresInSec} 秒` : ''
}

async function requestCaptcha() {
  if (!signin.account.trim()) {
    signinError.value = '先填账号，再刷新验证码'
    return
  }
  captchaBusy.value = true
  const result = await api('/api/auth/captcha/init', {
    method: 'POST',
    body: JSON.stringify({ account: signin.account.trim() }),
  })
  captchaBusy.value = false
  if (!result.ok) {
    signinError.value = errorText(result)
    return
  }
  applyCaptcha(result.data || {})
  signinError.value = ''
}

async function submitSignin() {
  const account = signin.account.trim()
  const password = signin.password
  if (!account || !password) {
    signinError.value = '账号与密码都要填'
    return
  }
  signinBusy.value = true
  signinError.value = ''
  const body = { account, password }
  if (captcha.visible && captcha.id) {
    body.challengeId = captcha.id
    body.captchaAnswer = captcha.answer.trim()
  }
  const result = await api('/api/auth/login', { method: 'POST', body: JSON.stringify(body) })
  signinBusy.value = false
  if (result.ok) {
    signin.password = ''
    hideCaptcha()
    crumbs.value = [{ id: '', name: '根目录' }]
    await loadAuth()
    return
  }
  const code = result.data?.error?.code
  if (result.status === 401 && code === 'captcha_required' && result.data?.challengeId) {
    applyCaptcha(result.data)
    signinError.value = errorText(result)
    return
  }
  hideCaptcha()
  signinError.value = errorDetail(result)
}

async function submitToken() {
  const body = {
    account: tokenForm.account.trim(),
    accessToken: tokenForm.accessToken.trim(),
    refreshToken: tokenForm.refreshToken.trim(),
  }
  if (!body.account || !body.accessToken) {
    tokenError.value = '账号与 accessToken 都要填'
    return
  }
  tokenBusy.value = true
  tokenError.value = ''
  const result = await api('/api/auth/token', { method: 'POST', body: JSON.stringify(body) })
  tokenBusy.value = false
  if (!result.ok) {
    tokenError.value = errorDetail(result)
    return
  }
  tokenForm.account = ''
  tokenForm.accessToken = ''
  tokenForm.refreshToken = ''
  crumbs.value = [{ id: '', name: '根目录' }]
  await loadAuth()
}

async function logout() {
  await api('/api/auth/logout', { method: 'POST' })
  crumbs.value = [{ id: '', name: '根目录' }]
  await loadAuth()
}

async function loadAuth() {
  if (authTimer) {
    clearTimeout(authTimer)
    authTimer = null
  }
  const auth = await refreshAuth()
  if (auth.initializing) authTimer = setTimeout(loadAuth, 1000)
  if (auth.authenticated) {
    signin.account = auth.account || signin.account
    await loadList()
    await loadDownloads()
    await loadTasks()
    openEvents()
  } else {
    closeEvents()
  }
}

async function loadList() {
  listBusy.value = true
  const result = await api(`/api/drive/list?parentId=${encodeURIComponent(currentParentId.value)}`)
  listBusy.value = false
  if (!result.ok) {
    listStatus.value = errorText(result)
    items.value = []
    return
  }
  items.value = result.data?.items || []
  listStatus.value = result.data?.nextPageToken
    ? `${items.value.length} 项（还有下一页，页面暂不翻页）`
    : `${items.value.length} 项`
}

function enter(item) {
  crumbs.value = [...crumbs.value, { id: item.id, name: item.name }]
  loadList()
}

function up() {
  if (crumbs.value.length === 1) return
  crumbs.value = crumbs.value.slice(0, -1)
  loadList()
}

function jumpTo(index) {
  if (index >= crumbs.value.length - 1) return
  crumbs.value = crumbs.value.slice(0, index + 1)
  loadList()
}

function onRowClick(row) {
  if (row.isFolder) enter(row)
}

function upsert(list, item) {
  const index = list.findIndex((row) => row.id === item.id)
  if (index < 0) list.push(item)
  else list.splice(index, 1, item)
}

function upsertDownload(item) {
  upsert(downloads.value, item)
}

function upsertTask(item) {
  upsert(tasks.value, item)
}

async function loadDownloads() {
  const result = await api('/api/downloads')
  if (!result.ok) {
    downloadHint.value = errorText(result)
    return
  }
  downloads.value = result.data?.items || []
  downloadHint.value = ''
}

async function downloadAction(id, action) {
  const result = await api(`/api/downloads/${encodeURIComponent(id)}/${action}`, { method: 'POST' })
  if (!result.ok) {
    downloadHint.value = errorDetail(result)
    return
  }
  downloads.value = result.data?.items || downloads.value
  downloadHint.value = ''
}

async function startDownload(fileId) {
  const result = await api('/api/downloads', { method: 'POST', body: JSON.stringify({ fileId }) })
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  downloads.value = result.data?.items || downloads.value
}

async function loadTasks() {
  const result = await api('/api/tasks')
  if (result.ok) tasks.value = result.data?.items || []
}

async function parseLink() {
  const link = linkForm.link.trim()
  if (!link) {
    linkStatus.value = '先填链接'
    return
  }
  parseBusy.value = true
  linkStatus.value = '解析中…'
  parsed.value = null
  linkFiles.value = []
  const result = await api('/api/links/parse', {
    method: 'POST',
    body: JSON.stringify({ link, passCode: linkForm.passCode.trim() }),
  })
  parseBusy.value = false
  if (!result.ok) {
    linkStatus.value = errorText(result)
    return
  }
  parsed.value = { link, kind: result.data.kind }
  linkFiles.value = result.data.files || []
  const kindText = result.data.kind === 'magnet' ? '磁力' : result.data.kind === 'share' ? '分享' : '直链'
  linkStatus.value = `${kindText} · ${result.data.name || '未命名'} · ${linkFiles.value.length} 个文件` +
    (result.data.truncated ? '（目录更深，列表不完整）' : '')
}

async function saveLink() {
  if (!parsed.value) return
  saveBusy.value = true
  saveStatus.value = '提交中…'
  const result = await api('/api/links/save', {
    method: 'POST',
    body: JSON.stringify({ link: parsed.value.link, parentId: currentParentId.value }),
  })
  saveBusy.value = false
  if (!result.ok) {
    saveStatus.value = errorText(result)
    return
  }
  saveStatus.value = result.data.instantComplete
    ? `已秒传，文件 ID：${result.data.fileId || '（未知）'}`
    : `已提交离线任务：${result.data.taskId || '（无任务 ID）'}`
  await loadTasks()
}

function openPlayer(item) {
  playingItem.value = item
  playerOpen.value = true
  nextTick(() => playerRef.value?.start())
}

function openEvents() {
  if (events) return
  events = new EventSource('/api/events')
  events.addEventListener('download.progress', (event) => {
    try {
      upsertDownload(JSON.parse(event.data))
    } catch (_) {
      /* 坏帧忽略 */
    }
  })
  events.addEventListener('task.progress', (event) => {
    try {
      upsertTask(JSON.parse(event.data))
    } catch (_) {
      /* 坏帧忽略 */
    }
  })
}

function closeEvents() {
  if (events) {
    events.close()
    events = null
  }
}

watch(
  () => state.auth.authenticated,
  (authenticated) => {
    if (!authenticated) closeEvents()
  },
)

onMounted(loadAuth)
onUnmounted(() => {
  closeEvents()
  if (authTimer) clearTimeout(authTimer)
})
</script>
