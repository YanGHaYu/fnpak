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
            <el-alert v-if="captcha.visible" :title="captcha.note || '需要验证码挑战'" type="info" :closable="false" show-icon>
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

    <div v-else>
      <!-- 首页只放目录 / 本机下载 / 离线任务；设置项在 /settings 子页面 -->
      <el-tabs v-model="tab" class="workspace-tabs">
        <el-tab-pane name="drive">
          <template #label><span class="tab-title">目录</span></template>
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

            <el-table
              ref="driveTable"
              :data="items"
              size="small"
              row-key="id"
              empty-text="这个目录是空的"
              @row-click="onRowClick"
              @selection-change="onDriveSelection"
            >
              <el-table-column type="selection" width="44" :selectable="selectableRow" />
              <el-table-column prop="name" label="名称" min-width="230">
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
              <el-table-column prop="mimeType" label="类型" width="130" show-overflow-tooltip />
              <el-table-column label="操作" width="220" align="right">
                <template #default="{ row }">
                  <div class="row-actions" @click.stop>
                    <el-button v-if="row.isVideo" size="small" @click="openPlayer(row)">播放</el-button>
                    <el-button v-if="row.isFolder" size="small" type="primary" plain @click="downloadFolder(row)">下载文件夹</el-button>
                    <el-button v-if="!row.isFolder" size="small" type="primary" plain @click="startDownload(row.id)">下载</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>

            <!-- 多选操作条：只针对文件，文件夹单独走「下载文件夹」 -->
            <div v-if="driveSelection.length" class="selection-bar">
              <span>
                已选 <b>{{ driveSelection.length }}</b> 项
                <template v-if="folderSelectionCount">（含 {{ folderSelectionCount }} 个文件夹）</template>
              </span>
              <span class="muted">总大小 {{ formatSize(selectedBytes) || '—' }}</span>
              <div class="spacer" />
              <el-button size="small" @click="clearDriveSelection">取消</el-button>
              <el-button size="small" type="danger" plain @click="deleteCloud">移入回收站</el-button>
              <el-button size="small" type="primary" :loading="batchBusy" @click="downloadSelected">下载</el-button>
            </div>
            <p class="hint" style="margin: 8px 0 0">
              {{ listHint }}
            </p>
          </el-card>

          <el-card shadow="never" class="card">
            <el-collapse>
              <el-collapse-item title="链接 / 磁力 / 分享（可选）" name="links">
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
              </el-collapse-item>
            </el-collapse>
          </el-card>
        </el-tab-pane>

        <el-tab-pane name="downloads">
          <template #label>
            <span class="tab-title">
              本机下载
              <el-badge v-if="activeGroupCount" :value="activeGroupCount" class="tab-badge" />
            </span>
          </template>
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card-head">
                <span class="title">本机下载</span>
                <span class="muted">{{ groups.length }} 个父任务 · {{ downloads.length }} 个子项</span>
                <div class="spacer" />
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :disabled="!groupSelection.length"
                  @click="deleteRecords(groupSelection)"
                >
                  删除选中 {{ groupSelection.length }} 项
                </el-button>
                <el-button size="small" @click="loadDownloads">刷新</el-button>
              </div>
            </template>

            <el-table
              ref="groupTable"
              :data="groups"
              size="small"
              row-key="id"
              empty-text="还没有下载任务"
              @selection-change="onGroupSelection"
            >
              <el-table-column type="selection" width="44" />
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="children" v-if="childrenOf(row).length">
                    <el-table :data="childrenOf(row)" size="small">
                      <el-table-column prop="fileName" label="子项（相对路径已还原）" min-width="260" show-overflow-tooltip />
                      <el-table-column label="进度" min-width="180">
                        <template #default="{ row: child }">
                          <el-progress :percentage="Number(child.percent) || 0" :status="progressStatus(child.status)" :stroke-width="8" />
                        </template>
                      </el-table-column>
                      <el-table-column label="大小" width="120" align="right">
                        <template #default="{ row: child }">
                          <span class="num">{{ formatSize(child.bytesDone) || '0' }} / {{ formatSize(child.bytesTotal) || '—' }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column prop="status" label="状态" width="120" />
                    </el-table>
                  </div>
                  <p v-else class="hint" style="padding: 6px 12px">子项还在扫描或入队中…</p>
                </template>
              </el-table-column>
              <el-table-column label="任务" min-width="230">
                <template #default="{ row }">
                  <span>{{ row.name }}</span>
                  <el-tag size="small" effect="plain" type="info" style="margin-left: 6px">{{ kindText(row.kind) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="进度（总字节汇总）" min-width="250">
                <template #default="{ row }">
                  <div class="progress-cell">
                    <el-progress
                      v-if="row.status !== 'SCANNING'"
                      :percentage="Number(row.percent) || 0"
                      :status="progressStatus(row.status)"
                      :stroke-width="10"
                    />
                    <span v-else class="hint">扫描中…</span>
                    <span class="hint mono">
                      <template v-if="row.status === 'SCANNING'">
                        已发现 {{ row.childCount }} 个文件 / {{ formatSize(row.bytesTotal) || '0' }}
                      </template>
                      <template v-else>
                        {{ row.completedCount }}/{{ row.childCount }} · {{ formatSize(row.bytesDone) || '0' }} / {{ formatSize(row.bytesTotal) || '—' }}
                      </template>
                    </span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="速率" width="100" align="right">
                <template #default="{ row }"><span class="num">{{ formatSpeed(row.speedBytesPerSec) }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }">
                  <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ groupStatusText(row.status) }}</el-tag>
                  <div v-if="row.error" class="error-line">{{ row.error }}</div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="220" align="right">
                <template #default="{ row }">
                  <div class="row-actions">
                    <el-button
                      v-if="isTerminal(row.status)"
                      size="small"
                      type="danger"
                      plain
                      @click="deleteRecords([row])"
                    >删除任务</el-button>
                    <template v-else>
                      <el-button size="small" :disabled="!canPause(row)" @click="groupAction(row, 'pause')">暂停</el-button>
                      <el-button
                        size="small"
                        type="primary"
                        plain
                        :disabled="!canResume(row)"
                        @click="groupAction(row, 'resume')"
                      >继续</el-button>
                      <el-button
                        size="small"
                        type="danger"
                        plain
                        :disabled="!canPause(row)"
                        @click="groupAction(row, 'cancel')"
                      >取消</el-button>
                    </template>
                  </div>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="groupSelection.length" class="selection-bar">
              <span>已选 <b>{{ groupSelection.length }}</b> 项</span>
              <div class="spacer" />
              <el-button size="small" @click="clearGroupSelection">取消</el-button>
              <el-button size="small" type="danger" @click="deleteRecords(groupSelection)">删除记录</el-button>
            </div>
            <p v-if="downloadHint" class="hint" style="margin: 8px 0 0">{{ downloadHint }}</p>
          </el-card>
        </el-tab-pane>

        <el-tab-pane name="offline">
          <template #label>
            <span class="tab-title">
              离线任务
              <el-badge v-if="unfinishedTaskCount" :value="unfinishedTaskCount" class="tab-badge" />
            </span>
          </template>
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card-head">
                <span class="title">离线任务</span>
                <span class="muted">{{ tasks.length }} 项</span>
                <div class="spacer" />
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :disabled="!offlineSelection.length"
                  :loading="deleteBusy"
                  @click="deleteOffline(offlineSelection.map((row) => row.id))"
                >
                  删除选中 {{ offlineSelection.length }} 项
                </el-button>
                <el-button size="small" @click="loadTasks">刷新</el-button>
              </div>
            </template>

            <el-table
              ref="tasksTable"
              :data="tasks"
              size="small"
              row-key="id"
              empty-text="没有离线任务"
              @selection-change="onOfflineSelection"
            >
              <el-table-column type="selection" width="44" />
              <el-table-column label="名称" min-width="260">
                <template #default="{ row }">
                  <el-tooltip :content="`名称来源：${sourceText(row.nameSource)}`" placement="top">
                    <span>{{ row.name || row.id }}</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="130">
                <template #default="{ row }">
                  <el-tag v-if="row.type === 'folder'" size="small" type="warning" effect="plain">
                    文件夹{{ row.folderChildCount ? ` · ${row.folderChildCount}${row.folderChildCountTruncated ? '+' : ''}` : '' }}
                  </el-tag>
                  <el-tag v-else-if="row.type === 'file'" size="small" type="info" effect="plain">文件</el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="相位" width="130">
                <template #default="{ row }">{{ phaseText(row.phase) }}</template>
              </el-table-column>
              <el-table-column label="进度" min-width="180">
                <template #default="{ row }">
                  <el-progress :percentage="Number(row.progress) || 0" :stroke-width="9" />
                </template>
              </el-table-column>
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }"><span class="num">{{ formatSize(row.fileSize) || '—' }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="90" align="right">
                <template #default="{ row }">
                  <el-button size="small" type="danger" plain :loading="deleteBusy" @click="deleteOffline([row.id])">删除</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="offlineSelection.length" class="selection-bar">
              <span>已选 <b>{{ offlineSelection.length }}</b> 项</span>
              <div class="spacer" />
              <el-button size="small" @click="clearOfflineSelection">取消</el-button>
              <el-button size="small" type="danger" :loading="deleteBusy" @click="deleteOffline(offlineSelection.map((row) => row.id))">
                删除
              </el-button>
            </div>
            <p v-if="offlineHint" class="hint" style="margin: 8px 0 0">{{ offlineHint }}</p>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </div>

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

const tab = ref('drive')
const crumbs = ref([{ id: '', name: '根目录' }])
const items = ref([])
const listStatus = ref('')
const listHint = ref('只展示第一页；勾选文件可多选下载，文件夹请点「下载文件夹」。')
const listBusy = ref(false)
const driveTable = ref(null)
const driveSelection = ref([])
const batchBusy = ref(false)
const currentParentId = computed(() => crumbs.value[crumbs.value.length - 1].id)
const selectedBytes = computed(() => driveSelection.value.reduce((sum, row) => sum + (row.sizeBytes || 0), 0))
const folderSelectionCount = computed(() => driveSelection.value.filter((row) => row.isFolder).length)

const downloads = ref([])
const groups = ref([])
const groupTable = ref(null)
const groupSelection = ref([])
const downloadHint = ref('')

const tasks = ref([])
const tasksTable = ref(null)
const offlineSelection = ref([])
const offlineHint = ref('')
const deleteBusy = ref(false)

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

const activeGroupCount = computed(() =>
  groups.value.filter((group) => ['SCANNING', 'QUEUED', 'DOWNLOADING'].includes(group.status)).length,
)
const unfinishedTaskCount = computed(() =>
  tasks.value.filter((task) => !['PHASE_TYPE_COMPLETE', 'PHASE_TYPE_ERROR'].includes(task.phase)).length,
)

let events = null
let authTimer = null
let tasksRefreshTimer = null
const scrollByTab = {}

const statusType = (status) =>
  status === 'FAILED' ? 'danger' : status === 'COMPLETED' ? 'success' : status === 'DOWNLOADING' ? 'primary' : 'info'
const progressStatus = (status) =>
  status === 'COMPLETED' ? 'success' : status === 'FAILED' ? 'exception' : undefined
const isTerminal = (status) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status)
const canPause = (group) => ['SCANNING', 'QUEUED', 'DOWNLOADING'].includes(group.status)
const canResume = (group) => ['PAUSED', 'CANCELLED', 'FAILED'].includes(group.status)
const kindText = (kind) => ({ folder: '文件夹', files: '多选批次', single: '单文件' }[kind] || kind)
const groupStatusText = (status) =>
  ({
    SCANNING: '扫描中',
    QUEUED: '排队中',
    DOWNLOADING: '下载中',
    PAUSED: '已暂停',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  }[status] || status)
const phaseText = (phase) =>
  ({
    PHASE_TYPE_PENDING: '等待中',
    PHASE_TYPE_RUNNING: '进行中',
    PHASE_TYPE_COMPLETE: '已完成',
    PHASE_TYPE_ERROR: '失败',
  }[phase] || phase)
const sourceText = (source) =>
  ({
    file_name: '任务自带 file_name',
    file_detail: '按 fileId 查详情',
    display_name: '磁力 display_name',
    magnet_dn: '磁力链接 dn 参数',
    fallback: '兜底 MAGNET+hash',
  }[source] || source)

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
  clearDriveSelection()
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
  clearDriveSelection()
  if (!result.ok) {
    listStatus.value = errorText(result)
    items.value = []
    return
  }
  items.value = result.data?.items || []
  listStatus.value = result.data?.nextPageToken
    ? `${items.value.length} 项（还有下一页，页面暂不翻页；全选只作用于当前页）`
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

// 目录勾选：文件与文件夹都可选，删除时一起处理；下载只取文件，文件夹走行内按钮
function selectableRow() {
  return true
}

function onDriveSelection(rows) {
  driveSelection.value = rows
}

function clearDriveSelection() {
  driveTable.value?.clearSelection()
  driveSelection.value = []
}

function upsert(list, row) {
  const index = list.findIndex((item) => item.id === row.id)
  if (index < 0) list.push(row)
  else list.splice(index, 1, row)
}

function upsertGroup(group) {
  upsert(groups.value, group)
}

function applyItemToGroup(item) {
  const group = groups.value.find((row) => row.id === item.groupId)
  if (!group || group.status === 'SCANNING') return
  const children = downloads.value.filter((row) => row.groupId === group.id)
  const total = children.reduce((sum, row) => sum + (row.bytesTotal || 0), 0)
  const done = children.reduce((sum, row) => sum + (row.bytesDone || 0), 0)
  group.childCount = children.length
  group.completedCount = children.filter((row) => row.status === 'COMPLETED').length
  group.bytesTotal = total
  group.bytesDone = done
  group.percent = total > 0 ? Math.round((done / total) * 1000) / 10 : 0
  group.speedBytesPerSec = children.reduce((sum, row) => sum + (row.speedBytesPerSec || 0), 0)
}

function childrenOf(group) {
  return downloads.value.filter((row) => row.groupId === group.id)
}

async function loadDownloads() {
  const result = await api('/api/downloads')
  if (!result.ok) {
    downloadHint.value = errorText(result)
    return
  }
  downloads.value = result.data?.items || []
  groups.value = result.data?.groups || []
  downloadHint.value = ''
}

async function downloadSelected() {
  const files = driveSelection.value.filter((row) => !row.isFolder)
  const folders = driveSelection.value.length - files.length
  if (!files.length) {
    listStatus.value = '选中的都是文件夹，请点行内「下载文件夹」'
    return
  }
  batchBusy.value = true
  const result = await api('/api/downloads/batch', {
    method: 'POST',
    body: JSON.stringify({ fileIds: files.map((row) => row.id) }),
  })
  batchBusy.value = false
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  const data = result.data || {}
  if (!data.childCount) {
    const reasons = (data.skipped || []).map((row) => row.reason).join('；')
    listStatus.value = reasons ? `没有可下载的文件：${reasons}` : '没有可下载的文件'
    return
  }
  clearDriveSelection()
  tab.value = 'downloads'
  await loadDownloads()
  downloadHint.value = data.groupId
    ? `已加入同一批次（父任务 ${data.groupId}），进度合并显示`
    : '已加入下载'
  if (folders > 0 && data.skipped?.length) {
    listStatus.value = `${folders} 个文件夹未加入下载（请单独点「下载文件夹」）`
  }
}

async function downloadFolder(row) {
  const result = await api('/api/downloads/folder', {
    method: 'POST',
    body: JSON.stringify({ folderId: row.id }),
  })
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  tab.value = 'downloads'
  await loadDownloads()
  const data = result.data || {}
  downloadHint.value = `正在递归扫描「${row.name}」：已发现 ${data.childCount || 0} 个文件，扫描完成后统一入队`
}

async function startDownload(fileId) {
  const result = await api('/api/downloads', { method: 'POST', body: JSON.stringify({ fileId }) })
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  await loadDownloads()
}

async function groupAction(group, action) {
  if (action === 'cancel') {
    try {
      await ElMessageBox.confirm(
        '停止这个父任务的全部下载。已经下载完成的文件会保留在本地，不会被删除。',
        '取消下载',
        { type: 'warning', confirmButtonText: '停止', cancelButtonText: '继续下载' },
      )
    } catch (_) {
      return
    }
  }
  const result = await api(`/api/downloads/groups/${encodeURIComponent(group.id)}/${action}`, { method: 'POST' })
  if (!result.ok) {
    downloadHint.value = errorDetail(result)
    return
  }
  if (result.data?.items) downloads.value = result.data.items
  if (result.data?.groups) groups.value = result.data.groups
  downloadHint.value = action === 'cancel' ? '已停止；已下载的文件保留' : ''
}

async function deleteRecords(rows) {
  const ids = rows.map((row) => row.id)
  if (!ids.length) return
  const paths = rows.map((row) => row.path).filter(Boolean)
  const detail = paths.length ? `\n本地文件将保留在：${paths.slice(0, 3).join('、')}${paths.length > 3 ? ' 等' : ''}` : ''
  try {
    await ElMessageBox.confirm(
      `将删除 ${ids.length} 个任务记录；只删任务记录与进度，不动磁盘上的文件。` +
        '正在下载的任务会先停止，已写入的分块/临时文件也会保留。' +
        detail,
      '删除下载任务记录',
      { type: 'warning', confirmButtonText: '删除记录', cancelButtonText: '取消' },
    )
  } catch (_) {
    return
  }
  const result = await api('/api/downloads/records/delete', {
    method: 'POST',
    body: JSON.stringify({ ids }),
  })
  if (!result.ok) {
    downloadHint.value = errorDetail(result)
    return
  }
  const deleted = result.data?.deleted || []
  const failed = result.data?.failed || []
  groups.value = groups.value.filter((row) => !deleted.includes(row.id))
  const deletedSet = new Set(deleted)
  downloads.value = downloads.value.filter((row) => !deletedSet.has(row.groupId))
  clearGroupSelection()
  downloadHint.value = failed.length
    ? `已删除 ${deleted.length} 项；失败 ${failed.length} 项：${failed.map((row) => row.message).join('；')}`
    : `已删除 ${result.data?.removedTasks || 0} 条任务记录，磁盘文件保留`
}

function onGroupSelection(rows) {
  groupSelection.value = rows
}

function clearGroupSelection() {
  groupTable.value?.clearSelection()
  groupSelection.value = []
}

async function loadTasks() {
  const result = await api('/api/tasks')
  if (result.ok) tasks.value = result.data?.items || []
}

function onOfflineSelection(rows) {
  offlineSelection.value = rows
}

function clearOfflineSelection() {
  tasksTable.value?.clearSelection()
  offlineSelection.value = []
}

async function deleteOffline(ids) {
  if (!ids.length || deleteBusy.value) return
  try {
    await ElMessageBox.confirm(
      `将删除 ${ids.length} 个离线任务记录；仅删除任务，不会删除云端已完成的文件。`,
      '删除离线任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch (_) {
    return
  }
  deleteBusy.value = true
  const result = await api('/api/tasks/delete', {
    method: 'POST',
    body: JSON.stringify({ ids }),
  })
  deleteBusy.value = false
  if (!result.ok) {
    offlineHint.value = errorDetail(result)
    return
  }
  const deleted = result.data?.deleted || []
  const failed = result.data?.failed || []
  tasks.value = tasks.value.filter((task) => !deleted.includes(task.id))
  clearOfflineSelection()
  offlineHint.value = failed.length
    ? `已删除 ${deleted.length} 项；失败 ${failed.length} 项：${failed.map((row) => row.message).join('；')}`
    : `已删除 ${deleted.length} 个任务；云端文件未删除`
}

async function deleteCloud() {
  const rows = driveSelection.value.slice()
  if (!rows.length) return
  const folders = rows.filter((row) => row.isFolder).length
  try {
    await ElMessageBox.confirm(
      `将删除 ${rows.length} 项${folders ? `（含 ${folders} 个文件夹）` : ''}。` +
        '文件夹会连同其中内容一起移入回收站；可在回收站恢复，不是永久删除。',
      '删除云端内容',
      { type: 'warning', confirmButtonText: '移入回收站', cancelButtonText: '取消' },
    )
  } catch (_) {
    return
  }
  const result = await api('/api/drive/trash', {
    method: 'POST',
    body: JSON.stringify({ ids: rows.map((row) => row.id) }),
  })
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  const trashed = result.data?.trashed || []
  const failed = result.data?.failed || []
  await loadList()
  const failedRows = items.value.filter((row) => failed.some((item) => item.id === row.id))
  await nextTick()
  failedRows.forEach((row) => driveTable.value?.toggleRowSelection(row, true))
  listStatus.value = failed.length
    ? `已移入回收站 ${trashed.length} 项；失败 ${failed.length} 项：${failed.map((row) => row.message).join('；')}`
    : `已移入回收站 ${trashed.length} 项（可在回收站恢复）`
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
  const kind = result.data.kind === 'magnet' ? '磁力' : result.data.kind === 'share' ? '分享' : '直链'
  linkStatus.value = `${kind} · ${result.data.name || '未命名'} · ${linkFiles.value.length} 个文件` +
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

function scheduleTasksRefresh() {
  if (tasksRefreshTimer) clearTimeout(tasksRefreshTimer)
  tasksRefreshTimer = setTimeout(() => {
    tasksRefreshTimer = null
    loadTasks()
  }, 1200)
}

function openEvents() {
  if (events) return
  events = new EventSource('/api/events')
  events.addEventListener('download.progress', (event) => {
    try {
      const item = JSON.parse(event.data)
      upsert(downloads.value, item)
      applyItemToGroup(item)
    } catch (_) {
      /* 坏帧忽略 */
    }
  })
  events.addEventListener('download.group', (event) => {
    try {
      upsertGroup(JSON.parse(event.data))
    } catch (_) {
      /* 坏帧忽略 */
    }
  })
  events.addEventListener('download.group.removed', (event) => {
    try {
      const id = JSON.parse(event.data).id
      groups.value = groups.value.filter((row) => row.id !== id)
      downloads.value = downloads.value.filter((row) => row.groupId !== id)
    } catch (_) {
      /* 坏帧忽略 */
    }
  })
  events.addEventListener('task.progress', (event) => {
    try {
      const task = JSON.parse(event.data)
      const previous = tasks.value.find((row) => row.id === task.id)
      upsert(tasks.value, { ...(previous || {}), ...task })
      if (task.phase === 'PHASE_TYPE_COMPLETE') scheduleTasksRefresh()
    } catch (_) {
      /* 坏帧忽略 */
    }
  })
  events.addEventListener('task.removed', (event) => {
    try {
      const id = JSON.parse(event.data).id
      tasks.value = tasks.value.filter((row) => row.id !== id)
      offlineSelection.value = offlineSelection.value.filter((row) => row.id !== id)
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

// 三个 Tab 各自记住滚动位置；切换只改显示，不重建组件
watch(tab, (next, previous) => {
  if (previous) scrollByTab[previous] = window.scrollY
  nextTick(() => {
    window.scrollTo({ top: scrollByTab[next] || 0 })
  })
})

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
  if (tasksRefreshTimer) clearTimeout(tasksRefreshTimer)
})
</script>
