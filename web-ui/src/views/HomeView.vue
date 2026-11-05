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
              class="desktop-table"
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
                  <div class="name-cell">
                    <span class="name-text" :class="{ mono: !row.isFolder }">{{ row.name || row.id }}</span>
                    <el-tag v-if="row.isFolder" size="small" type="info" effect="plain">目录</el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }">
                  <span class="num">{{ row.isFolder ? '' : formatSize(row.sizeBytes) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="mimeType" label="类型" width="130" show-overflow-tooltip />
              <el-table-column label="操作" width="64" align="center" class-name="action-col">
                <template #default="{ row }">
                  <RowActionMenu
                    trigger="hover"
                    :label="row.name || row.id"
                    :actions="driveActions(row)"
                    @select="(key) => onDriveAction(row, key)"
                  />
                </template>
              </el-table-column>
            </el-table>

            <!-- 窄屏降级：同一份数据渲染成卡片列表，勾选仍写回 el-table 的选中态 -->
            <div class="mobile-only mobile-toolbar">
              <el-button size="small" @click="toggleAllDrive">{{ allDriveSelected ? '取消全选' : '全选' }}</el-button>
            </div>
            <div class="mobile-only card-list">
              <el-card v-for="row in items" :key="row.id" shadow="never" class="row-card">
                <div class="row-card-action">
                  <RowActionMenu
                    trigger="click"
                    placement="bottom-end"
                    :label="row.name || row.id"
                    :actions="driveActions(row)"
                    @select="(key) => onDriveAction(row, key)"
                  />
                </div>
                <div class="row-card-main">
                  <el-checkbox
                    class="row-check"
                    :model-value="isDriveSelected(row)"
                    :disabled="!selectableRow(row)"
                    @change="(value) => toggleDriveRow(row, value)"
                  />
                  <div class="row-card-body" @click="onRowClick(row)">
                    <div class="row-card-title">
                      <span :class="{ mono: !row.isFolder }">{{ row.name || row.id }}</span>
                      <el-tag v-if="row.isFolder" size="small" type="info" effect="plain">目录</el-tag>
                    </div>
                    <div class="row-card-meta">
                      <span v-if="!row.isFolder" class="num">{{ formatSize(row.sizeBytes) }}</span>
                      <span>{{ row.isFolder ? '文件夹' : (row.mimeType || '—') }}</span>
                    </div>
                  </div>
                </div>
              </el-card>
              <p v-if="!items.length" class="hint">这个目录是空的</p>
            </div>

            <!-- 多选操作条：文案一行、按钮一行；行内操作只作用于当前行，批量只作用于选中集合 -->
            <div v-if="driveSelection.length" class="selection-bar">
              <div class="selection-info">
                <span>
                  已选 <b>{{ driveSelection.length }}</b> 项，其中
                  <b>{{ folderSelectionCount }}</b> 个文件夹、<b>{{ fileSelectionCount }}</b> 个文件
                </span>
                <span class="muted">
                  文件 {{ formatSize(selectedBytes) || '0 B' }}
                  <template v-if="folderSelectionCount">；文件夹大小待扫描</template>
                </span>
              </div>
              <div class="selection-actions">
                <el-button size="small" type="primary" :loading="batchBusy" @click="downloadSelected">下载</el-button>
                <el-button size="small" type="danger" plain @click="deleteCloud(driveSelection)">移入回收站</el-button>
                <el-button size="small" @click="clearDriveSelection">取消选择</el-button>
                <el-popover
                  v-model:visible="batchMoreOpen"
                  trigger="click"
                  placement="top-end"
                  :width="196"
                  :teleported="true"
                  :persistent="false"
                  popper-class="row-action-popper"
                >
                  <template #reference>
                    <el-button size="small">更多</el-button>
                  </template>
                  <div class="row-menu" role="menu">
                    <button type="button" role="menuitem" class="row-menu-item" @click="batchMoveCopy('move')">
                      <el-icon class="row-menu-icon"><Rank /></el-icon>
                      <span>移动到</span>
                    </button>
                    <button type="button" role="menuitem" class="row-menu-item" @click="batchMoveCopy('copy')">
                      <el-icon class="row-menu-icon"><CopyDocument /></el-icon>
                      <span>复制到</span>
                    </button>
                  </div>
                </el-popover>
              </div>
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
                <el-button size="small" @click="loadDownloads">刷新</el-button>
              </div>
            </template>

            <el-table
              ref="groupTable"
              class="desktop-table"
              :data="groups"
              size="small"
              row-key="id"
              empty-text="还没有下载任务"
              @selection-change="onGroupSelection"
              @expand-change="onGroupExpand"
            >
              <el-table-column type="selection" width="44" />
              <el-table-column type="expand">
                <template #default="{ row }">
                  <!-- 子项数以「GET /api/downloads 的 items 全量」为准；SSE 只更新单条，不当全量用 -->
                  <div class="children" v-if="childrenOf(row).length">
                    <div class="children-head">
                      <span>共 {{ childTotal(row) }} 项，显示 {{ childrenOf(row).length }} 项</span>
                      <span v-if="childTotal(row) > childrenOf(row).length" class="muted">正在补全子项…</span>
                    </div>
                    <el-table :data="childrenOf(row)" size="small" max-height="420">
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
                      <el-table-column label="状态" width="150">
                        <template #default="{ row: child }">
                          <el-tag size="small" :type="statusType(child.status)" effect="plain">
                            {{ childStatusText(child.status) }} · {{ Number(child.percent) || 0 }}%
                          </el-tag>
                          <div v-if="child.error" class="error-line">{{ child.error }}</div>
                        </template>
                      </el-table-column>
                      <el-table-column label="操作" width="64" align="center" class-name="action-col">
                        <template #default="{ row: child }">
                          <!-- 行内操作只作用于当前点击的这一个文件；已完成项没有可做的操作，不显示入口 -->
                          <RowActionMenu
                            v-if="childActions(child).length"
                            trigger="hover"
                            :label="child.fileName"
                            :actions="childActions(child)"
                            @select="(key) => onChildAction(child, key)"
                          />
                        </template>
                      </el-table-column>
                    </el-table>
                  </div>
                  <p v-else class="hint" style="padding: 6px 12px">子项还在扫描或入队中…</p>
                </template>
              </el-table-column>
              <el-table-column label="任务" min-width="230">
                <template #default="{ row }">
                  <div class="name-cell">
                    <span class="name-text">{{ row.name }}</span>
                    <el-tag size="small" effect="plain" type="info">{{ kindText(row.kind) }}</el-tag>
                  </div>
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
              <el-table-column label="操作" width="64" align="center" class-name="action-col">
                <template #default="{ row }">
                  <RowActionMenu
                    trigger="hover"
                    :label="row.name"
                    :actions="groupActions(row)"
                    @select="(key) => onGroupAction(row, key)"
                  />
                </template>
              </el-table-column>
            </el-table>

            <div class="mobile-only mobile-toolbar">
              <el-button size="small" @click="toggleAllGroups">{{ allGroupsSelected ? '取消全选' : '全选' }}</el-button>
            </div>
            <div class="mobile-only card-list">
              <el-card v-for="row in groups" :key="row.id" shadow="never" class="row-card">
                <div class="row-card-action">
                  <RowActionMenu
                    trigger="click"
                    placement="bottom-end"
                    :label="row.name"
                    :actions="groupActions(row)"
                    @select="(key) => onGroupAction(row, key)"
                  />
                </div>
                <div class="row-card-main">
                  <el-checkbox
                    class="row-check"
                    :model-value="isGroupSelected(row)"
                    @change="(value) => toggleGroupRow(row, value)"
                  />
                  <div class="row-card-body">
                    <div class="row-card-title">
                      <span>{{ row.name }}</span>
                      <el-tag size="small" effect="plain" type="info">{{ kindText(row.kind) }}</el-tag>
                    </div>
                    <div class="row-card-meta">
                      <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ groupStatusText(row.status) }}</el-tag>
                      <span class="num">{{ formatSpeed(row.speedBytesPerSec) }}</span>
                    </div>
                    <div class="progress-cell" style="margin-top: 6px">
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
                          {{ row.completedCount }}/{{ row.childCount }} · {{ formatSize(row.bytesDone) || '0' }} /
                          {{ formatSize(row.bytesTotal) || '—' }}
                        </template>
                      </span>
                    </div>
                    <div v-if="row.error" class="error-line">{{ row.error }}</div>
                  </div>
                </div>
                <div v-if="childrenOf(row).length" class="children-card">
                  <div class="children-head">
                    <span>共 {{ childTotal(row) }} 项，显示 {{ childrenOf(row).length }} 项</span>
                  </div>
                  <div class="children-scroll">
                    <div v-for="child in childrenOf(row)" :key="child.id" class="child-line">
                      <span class="mono child-name">{{ child.fileName }}</span>
                      <el-tag size="small" :type="statusType(child.status)" effect="plain">
                        {{ childStatusText(child.status) }} · {{ Number(child.percent) || 0 }}%
                      </el-tag>
                      <span class="num">{{ formatSize(child.bytesDone) || '0' }} / {{ formatSize(child.bytesTotal) || '—' }}</span>
                      <RowActionMenu
                        v-if="childActions(child).length"
                        trigger="click"
                        placement="bottom-end"
                        :label="child.fileName"
                        :actions="childActions(child)"
                        @select="(key) => onChildAction(child, key)"
                      />
                      <div v-if="child.error" class="error-line child-error">{{ child.error }}</div>
                    </div>
                  </div>
                </div>
                <p v-else-if="row.status === 'SCANNING'" class="hint" style="margin: 6px 0 0">子项还在扫描或入队中…</p>
              </el-card>
              <p v-if="!groups.length" class="hint">还没有下载任务</p>
            </div>

            <div v-if="groupSelection.length" class="selection-bar">
              <div class="selection-info">
                <span>已选 <b>{{ groupSelection.length }}</b> 项</span>
              </div>
              <div class="selection-actions">
                <!-- 只重试选中组里的失败子项；已完成/下载中/排队中的不会被动到 -->
                <el-button
                  v-if="failedSelectedCount"
                  size="small"
                  type="warning"
                  :loading="retryBusy"
                  @click="retrySelectedFailed"
                >
                  重试失败项 ({{ failedSelectedCount }})
                </el-button>
                <el-button size="small" type="danger" @click="deleteRecords(groupSelection)">删除记录</el-button>
                <el-button size="small" @click="clearGroupSelection">取消选择</el-button>
              </div>
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
                <el-button size="small" @click="loadTasks">刷新</el-button>
              </div>
            </template>

            <el-table
              ref="tasksTable"
              class="desktop-table"
              :data="tasks"
              size="small"
              row-key="id"
              empty-text="没有离线任务"
              @selection-change="onOfflineSelection"
            >
              <el-table-column type="selection" width="44" />
              <el-table-column label="名称" min-width="260">
                <template #default="{ row }">
                  <div class="name-cell">
                    <el-tooltip :content="`名称来源：${sourceText(row.nameSource)}`" placement="top">
                      <span class="name-text">{{ row.name || row.id }}</span>
                    </el-tooltip>
                  </div>
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
              <el-table-column label="操作" width="64" align="center" class-name="action-col">
                <template #default="{ row }">
                  <RowActionMenu
                    trigger="hover"
                    :label="row.name || row.id"
                    :actions="offlineActions()"
                    @select="() => deleteOffline([row])"
                  />
                </template>
              </el-table-column>
            </el-table>

            <div class="mobile-only mobile-toolbar">
              <el-button size="small" @click="toggleAllOffline">{{ allOfflineSelected ? '取消全选' : '全选' }}</el-button>
            </div>
            <div class="mobile-only card-list">
              <el-card v-for="row in tasks" :key="row.id" shadow="never" class="row-card">
                <div class="row-card-action">
                  <RowActionMenu
                    trigger="click"
                    placement="bottom-end"
                    :label="row.name || row.id"
                    :actions="offlineActions()"
                    @select="() => deleteOffline([row])"
                  />
                </div>
                <div class="row-card-main">
                  <el-checkbox
                    class="row-check"
                    :model-value="isOfflineSelected(row)"
                    @change="(value) => toggleOfflineRow(row, value)"
                  />
                  <div class="row-card-body">
                    <div class="row-card-title">
                      <span>{{ row.name || row.id }}</span>
                    </div>
                    <div class="row-card-meta">
                      <el-tag v-if="row.type === 'folder'" size="small" type="warning" effect="plain">
                        文件夹{{ row.folderChildCount ? ` · ${row.folderChildCount}${row.folderChildCountTruncated ? '+' : ''}` : '' }}
                      </el-tag>
                      <el-tag v-else-if="row.type === 'file'" size="small" type="info" effect="plain">文件</el-tag>
                      <span>{{ phaseText(row.phase) }}</span>
                      <span class="num">{{ formatSize(row.fileSize) || '—' }}</span>
                    </div>
                    <div class="progress-cell" style="margin-top: 6px">
                      <el-progress :percentage="Number(row.progress) || 0" :stroke-width="9" />
                    </div>
                  </div>
                </div>
              </el-card>
              <p v-if="!tasks.length" class="hint">没有离线任务</p>
            </div>

            <div v-if="offlineSelection.length" class="selection-bar">
              <div class="selection-info">
                <span>已选 <b>{{ offlineSelection.length }}</b> 项</span>
              </div>
              <div class="selection-actions">
                <el-button size="small" type="danger" :loading="deleteBusy" @click="deleteOffline(offlineSelection)">
                  删除
                </el-button>
                <el-button size="small" @click="clearOfflineSelection">取消选择</el-button>
              </div>
            </div>
            <p v-if="offlineHint" class="hint" style="margin: 8px 0 0">{{ offlineHint }}</p>
          </el-card>
        </el-tab-pane>

        <el-tab-pane name="trash">
          <template #label>
            <span class="tab-title">
              回收站
              <el-badge v-if="trashCount" :value="trashCount" class="tab-badge" />
            </span>
          </template>
          <TrashView ref="trashRef" @count="trashCount = $event" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 重命名：单输入框，预填原名，实时校验与服务端相同的规则 -->
    <el-dialog v-model="renameDialog.open" title="重命名" width="420px" class="rename-dialog">
      <el-form label-position="top" @submit.prevent="submitRename">
        <el-form-item label="新名称">
          <el-input v-model="renameDialog.name" maxlength="255" show-word-limit @keyup.enter="submitRename" />
        </el-form-item>
        <p v-if="renameError" class="error-line">{{ renameError }}</p>
        <p v-else class="hint">原名：{{ renameDialog.original }}</p>
      </el-form>
      <template #footer>
        <el-button @click="renameDialog.open = false">取消</el-button>
        <el-button type="primary" :disabled="!!renameError" :loading="renameDialog.busy" @click="submitRename">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 移动 / 复制：目录树懒加载，点目录名选中，底部确认 -->
    <el-dialog
      v-model="targetDialog.open"
      :title="targetDialog.mode === 'move' ? '移动到…' : '复制到…'"
      width="480px"
      class="target-dialog"
    >
      <p class="hint" style="margin: 0 0 6px">目标：{{ targetDialog.pathLabel }}</p>
      <div class="dir-tree">
        <DirTreePicker ref="treeRef" :node="targetTree" :selected-id="targetDialog.targetId" @select="onSelectTarget" />
      </div>
      <p class="hint" style="margin: 8px 0 0">
        本次目标：{{ targetDialog.label }}（{{ targetDialog.ids.length }} 项）；不能选择所选文件夹自身或其子目录。
        {{ targetDialog.mode === 'copy' ? '复制由 PikPak 服务端按任务执行，源文件保留。' : '' }}
      </p>
      <template #footer>
        <el-button @click="targetDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="targetDialog.busy" @click="submitMoveCopy">
          在此处{{ targetDialog.mode === 'move' ? '移动' : '复制' }}
        </el-button>
      </template>
    </el-dialog>

    <PlayerDialog ref="playerRef" v-model="playerOpen" :item="playingItem" />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DirTreePicker from '../components/DirTreePicker.vue'
import PlayerDialog from '../components/PlayerDialog.vue'
import RowActionMenu from '../components/RowActionMenu.vue'
import TrashView from './TrashView.vue'
import { CopyDocument, Rank } from '@element-plus/icons-vue'
import { api, errorDetail, errorText, formatSize, formatSpeed } from '../api'
import { refreshAuth, state } from '../store'

const route = useRoute()
const router = useRouter()

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
const TRASH_TAB = 'trash'
const VALID_TABS = ['drive', 'downloads', 'offline', TRASH_TAB]
const trashCount = ref(0)
const trashRef = ref(null)
const crumbs = ref([{ id: '', name: '根目录' }])
const items = ref([])
const listStatus = ref('')
const listHint = ref('只展示第一页；文件与文件夹都能勾选，混合下载会合并成一个批次（文件夹先递归扫描）。')
const listBusy = ref(false)
const driveTable = ref(null)
const driveSelection = ref([])
const batchBusy = ref(false)
const batchMoreOpen = ref(false)
const currentParentId = computed(() => crumbs.value[crumbs.value.length - 1].id)
const selectedBytes = computed(() =>
  driveSelection.value.filter((row) => !row.isFolder).reduce((sum, row) => sum + (row.sizeBytes || 0), 0),
)
const folderSelectionCount = computed(() => driveSelection.value.filter((row) => row.isFolder).length)
const fileSelectionCount = computed(() => driveSelection.value.filter((row) => !row.isFolder).length)

const downloads = ref([])
const groups = ref([])
const groupTable = ref(null)
const groupSelection = ref([])
const downloadHint = ref('')
const retryBusy = ref(false)
// 展开中的批次：全量刷新后按 row-key 重新展开，切 Tab 不丢
const expandedGroupIds = new Set()
let downloadsSyncTimer = null

const tasks = ref([])
const tasksTable = ref(null)
const offlineSelection = ref([])
const offlineHint = ref('')
const deleteBusy = ref(false)

// 重命名 / 移动 / 复制
const renameDialog = reactive({ open: false, id: '', original: '', name: '', busy: false })
const renameError = computed(() => {
  const name = renameDialog.name.trim()
  if (!name) return '名称不能为空'
  if (name.length > 255) return `名称不能超过 255 个字符（当前 ${name.length}）`
  if (name.includes('/') || name.includes('\\')) return '名称不能包含 / 或 \\'
  return ''
})
const targetDialog = reactive({ open: false, mode: 'move', targetId: '', pathLabel: '根目录', busy: false, ids: [], label: '' })
const targetTree = reactive({
  id: '',
  name: '根目录',
  path: [],
  isFolder: true,
  children: [],
  loaded: false,
  loading: false,
  expanded: false,
  error: '',
})
const treeRef = ref(null)

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
// 批量条只统计选中组里的失败子项：已完成、下载中、排队中都不算
const failedSelectedCount = computed(() =>
  groupSelection.value.reduce(
    (sum, group) => sum + downloads.value.filter((row) => row.groupId === group.id && row.status === 'FAILED').length,
    0,
  ),
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

// 窄屏卡片列表与桌面表格共用一份选中态：卡片勾选写回 el-table
const allDriveSelected = computed(
  () => items.value.length > 0 && driveSelection.value.length === items.value.length,
)
const isDriveSelected = (row) => driveSelection.value.some((item) => item.id === row.id)
function toggleDriveRow(row, value) {
  driveTable.value?.toggleRowSelection(row, value)
}
function toggleAllDrive() {
  driveTable.value?.toggleAllSelection()
}

const allGroupsSelected = computed(
  () => groups.value.length > 0 && groupSelection.value.length === groups.value.length,
)
const isGroupSelected = (row) => groupSelection.value.some((item) => item.id === row.id)
function toggleGroupRow(row, value) {
  groupTable.value?.toggleRowSelection(row, value)
}
function toggleAllGroups() {
  groupTable.value?.toggleAllSelection()
}

const allOfflineSelected = computed(
  () => tasks.value.length > 0 && offlineSelection.value.length === tasks.value.length,
)
const isOfflineSelected = (row) => offlineSelection.value.some((item) => item.id === row.id)
function toggleOfflineRow(row, value) {
  tasksTable.value?.toggleRowSelection(row, value)
}
function toggleAllOffline() {
  tasksTable.value?.toggleAllSelection()
}

function upsert(list, row) {
  const index = list.findIndex((item) => item.id === row.id)
  // SSE 增量只补齐变化字段，不能用事件 payload 整个替换：
  // 事件里没有 groupId 等归属字段时，整替换会把已有快照的 groupId 抹掉，子项就从批次里“消失”。
  if (index < 0) list.push({ ...row })
  else list.splice(index, 1, { ...list[index], ...row })
}

function upsertGroup(group) {
  const index = groups.value.findIndex((item) => item.id === group.id)
  if (index < 0) groups.value.push({ ...group })
  else groups.value.splice(index, 1, { ...groups.value[index], ...group })
  // 汇总里的 childCount 比当前已归组的子项多，说明本地快照落后：拉一次全量，SSE 不当全量集合
  if (number(group.childCount) > childrenOf(group).length) scheduleDownloadsSync()
}

function number(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function applyItemToGroup(item) {
  const group = groups.value.find((row) => row.id === item.groupId)
  if (!group || group.status === 'SCANNING') return
  const children = downloads.value.filter((row) => row.groupId === group.id)
  const total = children.reduce((sum, row) => sum + (row.bytesTotal || 0), 0)
  const done = children.reduce((sum, row) => sum + (row.bytesDone || 0), 0)
  group.childCount = Math.max(number(group.childCount), children.length)
  group.completedCount = children.filter((row) => row.status === 'COMPLETED').length
  group.bytesTotal = total
  group.bytesDone = done
  group.percent = total > 0 ? Math.round((done / total) * 1000) / 10 : 0
  group.speedBytesPerSec = children.reduce((sum, row) => sum + (row.speedBytesPerSec || 0), 0)
}

/**
 * 子项唯一来源是 `GET /api/downloads` 的 `items`：按 groupId 归组。
 * SSE `download.progress` 只按 id 合并单条，缺失分组字段时回退 parentTaskId。
 */
function childrenOf(group) {
  return downloads.value.filter((row) =>
    row.groupId ? row.groupId === group.id : row.parentTaskId === group.id,
  )
}

function childTotal(group) {
  return Math.max(number(group.childCount), childrenOf(group).length)
}

function childStatusText(status) {
  return (
    {
      PENDING: '等待中',
      QUEUED: '排队中',
      DOWNLOADING: '下载中',
      COMPLETED: '已完成',
      PAUSED: '已暂停',
      FAILED: '失败',
      CANCELLED: '已取消',
    }[status] || status || '—'
  )
}

/** 展开时兜底拉一次全量；刷新后按 id 恢复展开状态，避免“展开 → 重新拉 → 自动收起”。 */
function onGroupExpand(row, expandedRows) {
  const expanded = Array.isArray(expandedRows)
    ? expandedRows.some((item) => item.id === row.id)
    : Boolean(expandedRows)
  if (expanded) expandedGroupIds.add(row.id)
  else expandedGroupIds.delete(row.id)
  if (expanded && childrenOf(row).length < number(row.childCount)) scheduleDownloadsSync(300)
}

function scheduleDownloadsSync(delay = 1500) {
  if (downloadsSyncTimer) return
  downloadsSyncTimer = setTimeout(async () => {
    downloadsSyncTimer = null
    if (state.auth.authenticated === true) await loadDownloads()
  }, delay)
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
  // 保留展开状态
  if (expandedGroupIds.size) {
    await nextTick()
    for (const id of expandedGroupIds) {
      const target = groups.value.find((row) => row.id === id)
      if (!target) continue
      groupTable.value?.toggleRowExpansion(target, true)
    }
  }
}

async function downloadSelected() {
  const rows = driveSelection.value
  if (!rows.length) return
  batchBusy.value = true
  const result = await api('/api/downloads/mixed', {
    method: 'POST',
    body: JSON.stringify({ ids: rows.map((row) => row.id) }),
  })
  batchBusy.value = false
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  const data = result.data || {}
  clearDriveSelection()
  tab.value = 'downloads'
  await loadDownloads()
  const folders = data.folderCount || 0
  downloadHint.value = folders
    ? `已创建批次 ${data.groupId}：${folders} 个文件夹正在扫描，文件已入队；进度会合并显示`
    : `已加入同一批次（${data.groupId}），进度合并显示`
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
        `停止「${group.name || group.id}」这个父任务的全部下载。已经下载完成的文件会保留在本地，不会被删除。`,
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
  const title = rows.length === 1
    ? `删除任务「${rows[0].name || rows[0].id}」的记录？`
    : `删除这 ${ids.length} 个任务的记录？`
  try {
    await ElMessageBox.confirm(
      `${title}\n只删任务记录与进度，不动磁盘上的文件。` +
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

async function deleteOffline(rows) {
  const ids = rows.map((row) => row.id)
  if (!ids.length || deleteBusy.value) return
  const title = rows.length === 1
    ? `删除离线任务「${rows[0].name || rows[0].id}」？`
    : `删除这 ${ids.length} 个离线任务？`
  try {
    await ElMessageBox.confirm(
      `${title}\n仅删除任务，不会删除云端已完成的文件。`,
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

/**
 * 行内操作只作用于当前这一行：所有 handler 都接收 row，绝不读取选中集合。
 * label 里带名称，浮层标题与触发按钮 title 都能看出操作对象。
 */
function driveActions(row) {
  const actions = []
  if (row.isVideo) actions.push({ key: 'play', label: '播放', icon: 'play', hint: `播放「${row.name || row.id}」` })
  if (row.isFolder) {
    actions.push({ key: 'download-folder', label: '下载文件夹', icon: 'folder', hint: '递归下载整个文件夹' })
  } else {
    actions.push({ key: 'download', label: '下载', icon: 'download', hint: `下载「${row.name || row.id}」到本机` })
  }
  actions.push({ key: 'rename', label: '重命名', icon: 'rename', hint: '只重命名这一项' })
  actions.push({ key: 'move', label: '移动', icon: 'move', hint: '把这一项移到其他目录' })
  actions.push({ key: 'copy', label: '复制', icon: 'copy', hint: '把这一项复制到其他目录，源文件保留' })
  actions.push({ key: 'delete', label: '删除', icon: 'delete', danger: true, hint: '移入回收站，可恢复' })
  return actions
}

async function onDriveAction(row, key) {
  if (key === 'play') return openPlayer(row)
  if (key === 'download-folder') return downloadFolder(row)
  if (key === 'download') return startDownload(row.id)
  if (key === 'rename') return openRename(row)
  if (key === 'move' || key === 'copy') return openTargetDialog(key, [row])
  if (key === 'delete') return deleteCloud([row])
}

/** 这一组里处于失败状态的子项数；批量条与组菜单都以它为准。 */
function failedChildCount(group) {
  return downloads.value.filter((row) => row.groupId === group.id && row.status === 'FAILED').length
}

/**
 * 父任务行的菜单。「重试失败项」只在整组失败时出现（名称里的数字来自真实失败子项），
 * 已完成/取消的终态组仍只有删除记录。
 */
function groupActions(row) {
  const failed = row.status === 'FAILED' ? failedChildCount(row) : 0
  const retry = failed
    ? [{ key: 'retry-failed', label: `重试失败项 (${failed})`, icon: 'retry', hint: '只重跑失败/中断的子项，已完成的不动，断点续传' }]
    : []
  if (isTerminal(row.status)) {
    return [...retry, { key: 'delete-record', label: '删除任务', icon: 'delete', danger: true, hint: '只删任务记录，本地文件保留' }]
  }
  return [
    ...retry,
    { key: 'pause', label: '暂停', icon: 'pause', disabled: !canPause(row), hint: '暂停这个父任务的全部子项' },
    { key: 'resume', label: '继续', icon: 'resume', disabled: !canResume(row), hint: '继续下载这个父任务' },
    { key: 'cancel', label: '取消', icon: 'cancel', danger: true, disabled: !canPause(row), hint: '停止这个父任务，已下载文件保留' },
    { key: 'delete-record', label: '删除任务', icon: 'delete', danger: true, hint: '停止并删除任务记录，本地文件保留' },
  ]
}

/**
 * 单个文件（子项）的菜单：只作用于当前点击的这一行。
 * 失败才有「重试」；已完成项没有任何暂停/继续/取消，因此不显示入口。
 */
function childActions(child) {
  const actions = []
  if (child.status === 'FAILED') {
    actions.push({ key: 'retry', label: '重试', icon: 'retry', hint: '只重跑这个文件，从已下载的断点续传' })
  }
  if (['PENDING', 'DOWNLOADING'].includes(child.status)) {
    actions.push({ key: 'pause', label: '暂停', icon: 'pause', hint: '暂停这个文件' })
  }
  if (child.status === 'PAUSED') {
    actions.push({ key: 'resume', label: '继续', icon: 'resume', hint: '从这个文件已下载的位置继续' })
  }
  if (!['COMPLETED', 'CANCELLED'].includes(child.status)) {
    actions.push({ key: 'cancel', label: '取消', icon: 'cancel', danger: true, hint: '停止这个文件，已写入的分块保留' })
  }
  return actions
}

async function onChildAction(child, key) {
  if (key === 'retry') {
    const result = await api(`/api/downloads/${encodeURIComponent(child.id)}/retry`, { method: 'POST' })
    if (!result.ok) {
      downloadHint.value = errorDetail(result)
      return
    }
    if (result.data?.items) downloads.value = result.data.items
    if (result.data?.groups) groups.value = result.data.groups
    downloadHint.value = result.data?.message || '已重新开始，只补齐未完成的区间'
    return
  }
  const result = await api(`/api/downloads/${encodeURIComponent(child.id)}/${key}`, { method: 'POST' })
  if (!result.ok) {
    downloadHint.value = errorDetail(result)
    return
  }
  if (result.data?.items) downloads.value = result.data.items
  if (result.data?.groups) groups.value = result.data.groups
  downloadHint.value = key === 'cancel' ? '已停止该文件；已写入的分块保留' : ''
}

async function retrySelectedFailed() {
  const targets = groupSelection.value.filter((group) =>
    downloads.value.some((row) => row.groupId === group.id && row.status === 'FAILED'),
  )
  if (!targets.length) return
  retryBusy.value = true
  let retried = 0
  const rejected = []
  for (const group of targets) {
    const result = await api(`/api/downloads/groups/${encodeURIComponent(group.id)}/retry`, { method: 'POST' })
    if (!result.ok) {
      rejected.push(`${group.name || group.id}：${errorText(result)}`)
      continue
    }
    retried += result.data?.retried?.length || 0
    if (result.data?.items) downloads.value = result.data.items
    if (result.data?.groups) groups.value = result.data.groups
  }
  retryBusy.value = false
  await loadDownloads()
  downloadHint.value = rejected.length
    ? `已重试 ${retried} 个失败项；${rejected.length} 组被拒绝：${rejected.join('；')}`
    : `已重试 ${retried} 个失败项，只补齐未完成的区间`
}

function onGroupAction(row, key) {
  if (key === 'delete-record') return deleteRecords([row])
  if (key === 'retry-failed') return retryGroupFailed(row)
  return groupAction(row, key)
}

async function retryGroupFailed(group) {
  const result = await api(`/api/downloads/groups/${encodeURIComponent(group.id)}/retry`, { method: 'POST' })
  if (!result.ok) {
    downloadHint.value = errorDetail(result)
    return
  }
  if (result.data?.items) downloads.value = result.data.items
  if (result.data?.groups) groups.value = result.data.groups
  await loadDownloads()
  downloadHint.value = result.data?.message || '已重试失败项，只补齐未完成的区间'
}

function offlineActions() {
  return [{ key: 'delete-task', label: '删除任务', icon: 'delete', danger: true, hint: '只删离线任务，不动云端文件' }]
}

async function deleteCloud(rows = driveSelection.value) {
  const targets = rows.slice()
  if (!targets.length) return
  const folders = targets.filter((row) => row.isFolder).length
  const names = targets.map((row) => `「${row.name || row.id}」`)
  const title = targets.length === 1 ? `将${names[0]}移入回收站？` : `将这 ${targets.length} 项移入回收站？`
  const listed = targets.length > 1
    ? `\n${names.slice(0, 3).join('、')}${names.length > 3 ? ' 等' : ''}`
    : ''
  try {
    await ElMessageBox.confirm(
      title + listed +
        `\n${folders ? `其中 ${folders} 个文件夹会连同内容一起移入回收站；` : ''}` +
        '可在回收站恢复，不是永久删除。',
      '删除云端内容',
      { type: 'warning', confirmButtonText: '移入回收站', cancelButtonText: '取消' },
    )
  } catch (_) {
    return
  }
  const result = await api('/api/drive/trash', {
    method: 'POST',
    body: JSON.stringify({ ids: targets.map((row) => row.id) }),
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

function openRename(row) {
  if (!row) return
  renameDialog.id = row.id
  renameDialog.original = row.name || row.id
  renameDialog.name = renameDialog.original
  renameDialog.busy = false
  renameDialog.open = true
}

async function submitRename() {
  if (renameDialog.busy || renameError.value) return
  renameDialog.busy = true
  const name = renameDialog.name.trim()
  const result = await api(`/api/drive/files/${encodeURIComponent(renameDialog.id)}/rename`, {
    method: 'POST',
    body: JSON.stringify({ name }),
  })
  renameDialog.busy = false
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  renameDialog.open = false
  await loadList()
  listStatus.value = `已重命名为「${result.data?.name || name}」`
}

async function openTargetDialog(mode, rows = driveSelection.value) {
  if (!rows.length) return
  targetDialog.mode = mode
  targetDialog.ids = rows.map((row) => row.id)
  targetDialog.label = rows.length === 1 ? rows[0].name || rows[0].id : `已选 ${rows.length} 项`
  targetDialog.targetId = ''
  targetDialog.pathLabel = '根目录'
  targetDialog.busy = false
  targetDialog.open = true
  Object.assign(targetTree, {
    id: '',
    name: '根目录',
    path: [],
    isFolder: true,
    children: [],
    loaded: false,
    loading: false,
    expanded: false,
    error: '',
  })
  await nextTick()
  treeRef.value?.load()
}

function onSelectTarget(node) {
  targetDialog.targetId = node.id
  targetDialog.pathLabel = node.path.length ? node.path.join(' / ') : '根目录'
}

/** 批量「更多」菜单：多选下不提供重命名，只放移动/复制这类次要操作。 */
function batchMoveCopy(mode) {
  batchMoreOpen.value = false
  openTargetDialog(mode, driveSelection.value)
}

async function submitMoveCopy() {
  if (targetDialog.busy) return
  const ids = targetDialog.ids.slice()
  if (!ids.length) return
  targetDialog.busy = true
  const result = await api(`/api/drive/${targetDialog.mode}`, {
    method: 'POST',
    body: JSON.stringify({ ids, targetParentId: targetDialog.targetId }),
  })
  targetDialog.busy = false
  if (!result.ok) {
    listStatus.value = errorDetail(result)
    return
  }
  const data = result.data || {}
  const itemsResult = data.items || []
  const succeeded = itemsResult.filter((row) => row.ok)
  const failed = itemsResult.filter((row) => !row.ok)
  const where = targetDialog.pathLabel
  targetDialog.open = false
  await loadList()
  const failedRows = items.value.filter((row) => failed.some((item) => item.id === row.id))
  await nextTick()
  failedRows.forEach((row) => driveTable.value?.toggleRowSelection(row, true))
  const failedText = failed.length
    ? `；失败 ${failed.length} 项：${failed.map((row) => row.message || '未知原因').join('；')}`
    : ''
  if (targetDialog.mode === 'move') {
    listStatus.value = `已移动 ${succeeded.length} 项到「${where}」${failedText}`
  } else {
    const tasks = succeeded.map((row) => row.taskId).filter(Boolean).join(', ')
    listStatus.value = `已提交复制 ${succeeded.length} 项到「${where}」，源文件保留` +
      `${tasks ? `；任务 ${tasks}（服务端异步执行，可稍后刷新目标目录）` : ''}${failedText}`
  }
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

// Tab 与地址栏双向同步：`/trash` 深链直接进回收站；其它 Tab 用 `?tab=` 持久化，刷新后停在原 Tab
function tabFromRoute() {
  if (route.path === '/trash') return TRASH_TAB
  const query = String(route.query.tab || '')
  return VALID_TABS.includes(query) ? query : 'drive'
}

function syncRouteForTab(next) {
  if (next === TRASH_TAB) {
    if (route.path !== '/trash') router.replace({ path: '/trash' })
    return
  }
  const targetQuery = next === 'drive' ? '' : next
  const currentQuery = route.path === '/' ? String(route.query.tab || '') : ''
  if (route.path === '/' && currentQuery === targetQuery) return
  router.replace({ path: '/', query: next === 'drive' ? {} : { tab: next } })
}

// 四个 Tab 各自记住滚动位置；切换只改显示，不重建组件
watch(tab, (next, previous) => {
  if (previous) scrollByTab[previous] = window.scrollY
  syncRouteForTab(next)
  if (next === 'downloads') loadDownloads()
  if (next === TRASH_TAB) trashRef.value?.load?.()
  nextTick(() => {
    window.scrollTo({ top: scrollByTab[next] || 0 })
  })
})

watch(
  () => route.fullPath,
  () => {
    const next = tabFromRoute()
    if (next !== tab.value) tab.value = next
  },
)

watch(
  () => state.auth.authenticated,
  (authenticated) => {
    if (!authenticated) closeEvents()
  },
)

onMounted(async () => {
  tab.value = tabFromRoute()
  await loadAuth()
})
onUnmounted(() => {
  closeEvents()
  if (authTimer) clearTimeout(authTimer)
  if (tasksRefreshTimer) clearTimeout(tasksRefreshTimer)
  if (downloadsSyncTimer) clearTimeout(downloadsSyncTimer)
})
</script>
