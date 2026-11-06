<template>
  <div class="space-y-3 md:space-y-4">
    <!-- 未登录 PikPak：账号密码 / AccessToken 两种入口 -->
    <section v-if="!state.auth.authenticated" class="mx-auto w-full max-w-lg">
      <div class="card p-4 md:p-5">
        <header class="mb-3 flex items-start justify-between gap-3">
          <div>
            <h1 class="text-base font-semibold">连接 PikPak</h1>
            <p class="mt-1 text-xs text-muted">登录后即可浏览云端目录、发起离线任务与本机下载。</p>
          </div>
          <span class="rounded-full bg-surface2 px-2 py-0.5 text-xs text-muted">
            {{ state.auth.initializing ? '恢复登录态…' : '未登录' }}
          </span>
        </header>

        <AppSegmented v-model="loginTab" :options="loginTabs" class="mb-3" />

        <form v-if="loginTab === 'password'" class="space-y-3" @submit.prevent="submitSignin">
          <AppField label="账号（手机号 / 邮箱）">
            <AppInput v-model="signin.account" placeholder="账号" autocomplete="username" />
          </AppField>
          <AppField label="密码">
            <AppInput v-model="signin.password" type="password" placeholder="密码" autocomplete="current-password" @submit="submitSignin" />
          </AppField>
          <AppAlert v-if="captcha.visible" tone="info" :title="captcha.note || '需要验证码挑战'">
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <AppInput v-model="captcha.answer" placeholder="验证码（没有图片可留空）" compact class="max-w-[220px]" />
              <span class="text-xs text-muted">{{ captcha.ttl }}</span>
              <AppButton size="sm" :loading="captchaBusy" @click="requestCaptcha">刷新验证码</AppButton>
            </div>
          </AppAlert>
          <div class="flex flex-wrap items-center gap-2">
            <AppButton variant="primary" :loading="signinBusy" @click="submitSignin">登录</AppButton>
          </div>
          <p v-if="signinError" class="text-xs text-danger">{{ signinError }}</p>
        </form>

        <form v-else class="space-y-3" @submit.prevent="submitToken">
          <AppField label="账号">
            <AppInput v-model="tokenForm.account" placeholder="账号" />
          </AppField>
          <AppField label="AccessToken">
            <AppInput v-model="tokenForm.accessToken" type="password" placeholder="accessToken" />
          </AppField>
          <AppField label="RefreshToken（可选）">
            <AppInput v-model="tokenForm.refreshToken" type="password" placeholder="refreshToken" />
          </AppField>
          <div class="flex flex-wrap items-center gap-2">
            <AppButton variant="primary" :loading="tokenBusy" @click="submitToken">导入</AppButton>
          </div>
          <p v-if="tokenError" class="text-xs text-danger">{{ tokenError }}</p>
        </form>
      </div>
    </section>

    <template v-else>
      <!-- 目录 -->
      <section v-if="tab === 'drive'" class="card p-3 md:p-4">
        <header class="mb-3 flex flex-wrap items-center gap-2">
          <h1 class="text-base font-semibold">目录</h1>
          <span class="rounded-full bg-surface2 px-2 py-0.5 text-xs text-muted">
            {{ state.auth.displayName || state.auth.account }}
          </span>
          <span class="text-xs text-muted">{{ listStatus }}</span>
          <span class="flex-1" />
          <AppButton size="sm" variant="ghost" :disabled="crumbs.length === 1" @click="up">上一级</AppButton>
          <AppButton size="sm" variant="ghost" icon="refresh" :loading="listBusy" @click="loadList">刷新</AppButton>
          <DriveSortControl v-model:dimension="driveSort.dimension" v-model:direction="driveSort.direction" />
          <AppButton v-if="!driveSelectionMode && items.length" class="md:hidden" size="sm" variant="ghost" @click="driveSelectionMode = true">
            选择
          </AppButton>
        </header>

        <nav class="mb-2 flex flex-wrap items-center gap-1 text-sm" aria-label="路径">
          <template v-for="(crumb, index) in crumbs" :key="crumb.id + index">
            <button
              type="button"
              class="rounded px-1.5 py-1 transition-colors"
              :class="index === crumbs.length - 1 ? 'font-medium text-ink' : 'text-brand hover:bg-brand-soft'"
              :disabled="index >= crumbs.length - 1"
              @click="jumpTo(index)"
            >
              {{ index === 0 ? '根目录' : crumb.name }}
            </button>
            <span v-if="index < crumbs.length - 1" class="text-muted">/</span>
          </template>
        </nav>

        <div :class="driveSelection.length ? 'pb-28 md:pb-0' : ''">
          <ProgressiveList :items="sortedItems" :step="40">
            <template #default="{ row }">
              <div
                class="mb-2 grid grid-cols-[auto_auto_minmax(0,1fr)_auto] items-center gap-2 rounded-md border border-line bg-surface px-1.5 py-1.5 transition-colors hover:bg-surface2 md:grid-cols-[auto_auto_minmax(0,1fr)_110px_150px_auto]"
                @touchstart.passive="startPress(drivePressTimer, () => enterDriveSelection(row))"
                @touchend="cancelPress(drivePressTimer)"
                @touchmove="cancelPress(drivePressTimer)"
              >
                <AppCheckbox
                  :class="driveSelectionMode ? '' : 'max-md:hidden'"
                  :model-value="isDriveSelected(row)"
                  :label="`选择 ${row.name || row.id}`"
                  @update:model-value="(value) => toggleDriveRow(row, value)"
                />

                <span class="flex h-9 w-9 items-center justify-center rounded-md bg-surface2 text-muted max-md:col-start-2">
                  <AppIcon :name="row.isFolder ? 'folder' : (row.isVideo ? 'play' : 'file')" :size="18" />
                </span>

                <div class="min-w-0 max-md:col-start-3">
                  <ScrollName :label="row.name || row.id">
                    <button
                      type="button"
                      class="flex min-w-0 items-center gap-2 text-left md:w-full"
                      :title="row.name || row.id"
                      @click="onRowClick(row)"
                    >
                      <span class="truncate text-sm font-medium" :class="{ 'font-mono': !row.isFolder }">{{ row.name || row.id }}</span>
                      <span v-if="row.isFolder" class="shrink-0 rounded-full bg-surface2 px-1.5 py-0.5 text-[10px] text-muted">目录</span>
                    </button>
                  </ScrollName>
                  <p class="mt-0.5 flex flex-wrap gap-x-3 text-xs text-muted md:hidden">
                    <span v-if="!row.isFolder">{{ formatSize(row.sizeBytes) || '—' }}</span>
                    <span>{{ row.isFolder ? '文件夹' : (row.mimeType || '—') }}</span>
                  </p>
                </div>

                <span class="hidden text-right font-mono text-xs text-muted md:block">
                  {{ row.isFolder ? '' : formatSize(row.sizeBytes) }}
                </span>
                <span class="hidden truncate text-xs text-muted md:block" :title="row.mimeType">
                  {{ row.isFolder ? '' : row.mimeType || '' }}
                </span>

                <div class="max-md:col-start-4">
                  <RowActionMenu
                    trigger="hover"
                    :label="row.name || row.id"
                    :actions="driveActions(row)"
                    @select="(key) => onDriveAction(row, key)"
                  />
                </div>
              </div>
            </template>
          </ProgressiveList>
          <p v-if="!items.length && !listBusy" class="py-8 text-center text-sm text-muted">这个目录是空的</p>
        </div>

        <SelectionDock v-if="driveSelection.length">
          <template #info>
            <!-- 窄屏只留一行（计数 + 类型），体积与「待扫描」说明在桌面展开 -->
            <span class="md:hidden">
              已选 <b>{{ driveSelection.length }}</b> 项
              <span class="text-muted">· {{ folderSelectionCount }} 文件夹 / {{ fileSelectionCount }} 文件</span>
            </span>
            <span class="hidden md:inline">
              已选 <b>{{ driveSelection.length }}</b> 项，其中
              <b>{{ folderSelectionCount }}</b> 个文件夹、<b>{{ fileSelectionCount }}</b> 个文件
              <span class="text-muted">
                （文件 {{ formatSize(selectedBytes) || '0 B' }}<template v-if="folderSelectionCount">；文件夹大小待扫描</template>）
              </span>
            </span>
          </template>
          <template #actions>
            <AppButton size="sm" variant="primary" :loading="batchBusy" @click="downloadSelected">下载</AppButton>
            <AppButton size="sm" variant="danger-soft" @click="deleteCloud(driveSelection)">移入回收站</AppButton>
            <div class="hidden md:block">
              <AppMenu
                :actions="batchMoreActions"
                label="批量操作"
                placement="top-end"
                @select="batchMoveCopy"
              >
                <template #trigger>
                  <AppButton size="sm">更多</AppButton>
                </template>
              </AppMenu>
            </div>
            <AppButton class="md:hidden" size="sm" @click="batchSheet = true">更多</AppButton>
            <AppButton size="sm" variant="ghost" @click="clearDriveSelection">取消选择</AppButton>
          </template>
        </SelectionDock>

        <p class="mt-2 text-xs text-muted">{{ listHint }}</p>

        <!-- 链接 / 磁力 / 分享 -->
        <div class="mt-3 rounded-md border border-line">
          <button
            type="button"
            class="flex min-h-11 w-full items-center gap-2 rounded-md px-3 text-left text-sm font-medium hover:bg-surface2"
            :aria-expanded="String(linksOpen)"
            @click="linksOpen = !linksOpen"
          >
            <AppIcon :name="linksOpen ? 'chevronDown' : 'chevronRight'" :size="16" />
            链接 / 磁力 / 分享（可选）
          </button>
          <div v-if="linksOpen" class="space-y-2 border-t border-line p-3">
            <div class="grid gap-2 md:grid-cols-[minmax(0,1fr)_140px_auto_auto]">
              <AppInput v-model="linkForm.link" placeholder="粘贴直链 / 磁力 / 分享链接" />
              <AppInput v-model="linkForm.passCode" placeholder="提取码（可选）" />
              <AppButton size="sm" :loading="parseBusy" @click="parseLink">解析</AppButton>
              <AppButton size="sm" variant="primary" :disabled="!parsed" :loading="saveBusy" @click="saveLink">
                存到当前目录
              </AppButton>
            </div>
            <p v-if="linkStatus" class="text-xs text-muted">{{ linkStatus }}</p>
            <div v-if="linkFiles.length" class="overflow-hidden rounded-md border border-line">
              <div
                v-for="file in linkFiles"
                :key="file.name"
                class="flex flex-wrap items-center gap-x-3 border-b border-line px-2 py-2 text-xs last:border-b-0"
              >
                <span class="min-w-0 flex-1 truncate font-mono" :title="file.name">{{ file.name }}</span>
                <span class="font-mono text-muted">{{ formatSize(file.sizeBytes) }}</span>
                <span class="rounded-full bg-surface2 px-1.5 py-0.5">{{ file.instantReady ? '可秒传' : '需离线下载' }}</span>
              </div>
            </div>
            <p v-if="saveStatus" class="text-xs text-muted">{{ saveStatus }}</p>
          </div>
        </div>
      </section>

      <!-- 本机下载 -->
      <section v-if="tab === 'downloads'" class="card p-3 md:p-4">
        <header class="mb-3 flex flex-wrap items-center gap-2">
          <h1 class="text-base font-semibold">本机下载</h1>
          <span class="text-xs text-muted">{{ groups.length }} 个父任务 · {{ downloads.length }} 个子项</span>
          <span class="flex-1" />
          <AppButton size="sm" variant="ghost" icon="refresh" @click="loadDownloads">刷新</AppButton>
          <AppButton v-if="!groupSelectionMode && groups.length" class="md:hidden" size="sm" variant="ghost" @click="groupSelectionMode = true">
            选择
          </AppButton>
        </header>

        <div :class="groupSelection.length ? 'pb-28 md:pb-0' : ''">
          <ProgressiveList :items="groups" :step="30">
            <template #default="{ row }">
              <div
                class="mb-2 overflow-hidden rounded-md border border-line bg-surface transition-colors hover:bg-surface2/60"
                @touchstart.passive="startPress(groupPressTimer, () => enterGroupSelection(row))"
                @touchend="cancelPress(groupPressTimer)"
                @touchmove="cancelPress(groupPressTimer)"
              >
                <div class="grid grid-cols-[auto_auto_minmax(0,1fr)_auto] items-center gap-2 px-1.5 py-1.5 md:grid-cols-[auto_auto_minmax(0,1fr)_220px_90px_110px_auto]">
                  <AppCheckbox
                    :class="groupSelectionMode ? '' : 'max-md:hidden'"
                    :model-value="isGroupSelected(row)"
                    :label="`选择任务 ${row.name}`"
                    @update:model-value="(value) => toggleGroupRow(row, value)"
                  />

                  <button
                    type="button"
                    class="flex h-9 w-9 items-center justify-center rounded-md bg-surface2 text-muted max-md:col-start-2"
                    :aria-label="isExpanded(row) ? '收起子项' : '展开子项'"
                    @click="toggleExpand(row)"
                  >
                    <AppIcon :name="isExpanded(row) ? 'chevronDown' : 'chevronRight'" :size="16" />
                  </button>

                  <div class="min-w-0 max-md:col-start-3">
                    <ScrollName :label="row.name">
                      <button type="button" class="flex min-w-0 items-center gap-2 text-left md:w-full" @click="toggleExpand(row)">
                        <span class="truncate text-sm font-medium" :title="row.name">{{ row.name }}</span>
                        <span class="shrink-0 rounded-full bg-surface2 px-1.5 py-0.5 text-[10px] text-muted">{{ kindText(row.kind) }}</span>
                      </button>
                    </ScrollName>
                    <p class="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted md:hidden">
                      <span class="rounded-full px-1.5 py-0.5" :class="statusChipClass(row.status)">{{ groupStatusText(row.status) }}</span>
                      <!-- 无速率时不渲染占位破折号，避免组元信息前出现多余分隔符 -->
                      <span v-if="Number(row.speedBytesPerSec) > 0" class="font-mono">{{ formatSpeed(row.speedBytesPerSec) }}</span>
                      <span class="font-mono">{{ groupMetaText(row) }}</span>
                    </p>
                  </div>

                  <div class="hidden md:block">
                    <AppProgress v-if="row.status !== 'SCANNING'" :value="Number(row.percent) || 0" :status="progressStatus(row.status)" :height="10" />
                    <span v-else class="text-xs text-muted">扫描中…</span>
                    <span class="mt-1 block font-mono text-xs text-muted">{{ groupMetaText(row) }}</span>
                  </div>
                  <span class="hidden text-right font-mono text-xs text-muted md:block">{{ formatSpeed(row.speedBytesPerSec) }}</span>
                  <span class="hidden md:block">
                    <span class="rounded-full px-1.5 py-0.5 text-xs" :class="statusChipClass(row.status)">{{ groupStatusText(row.status) }}</span>
                  </span>

                  <div class="max-md:col-start-4">
                    <RowActionMenu
                      trigger="hover"
                      :label="row.name"
                      :actions="groupActions(row)"
                      @select="(key) => onGroupAction(row, key)"
                    />
                  </div>
                </div>

                <div v-if="row.status !== 'SCANNING'" class="px-2 md:hidden">
                  <AppProgress :value="Number(row.percent) || 0" :status="progressStatus(row.status)" :height="8" />
                </div>

                <p v-if="row.error" class="border-t border-line px-3 py-1.5 text-xs text-danger">{{ row.error }}</p>

                <div v-if="isExpanded(row)" class="border-t border-line bg-surface2/40 px-2 py-2">
                  <div v-if="childrenOf(row).length" class="space-y-1">
                    <p class="text-xs text-muted">
                      共 {{ childTotal(row) }} 项，显示 {{ childrenOf(row).length }} 项
                      <span v-if="childTotal(row) > childrenOf(row).length">（正在补全子项…）</span>
                    </p>
                    <div class="max-h-[420px] overflow-y-auto overflow-x-hidden rounded-md border border-line bg-surface">
                      <ProgressiveList :items="childrenOf(row)" :step="40">
                        <template #default="{ row: child }">
                          <div class="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-2 gap-y-1 border-b border-line px-2 py-2 last:border-b-0">
                            <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded bg-surface2 text-muted">
                              <AppIcon :name="child.status === 'COMPLETED' ? 'check' : 'file'" :size="14" />
                            </span>
                            <div class="min-w-0">
                              <ScrollName :label="child.fileName">
                                <p class="truncate font-mono text-xs" :title="child.fileName">{{ child.fileName }}</p>
                              </ScrollName>
                              <div class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1">
                                <span class="rounded-full px-1.5 py-0.5 text-[10px]" :class="statusChipClass(child.status)">
                                  {{ childStatusText(child.status) }} · {{ Number(child.percent) || 0 }}%
                                </span>
                                <span class="font-mono text-[11px] text-muted">
                                  {{ formatSize(child.bytesDone) || '0' }} / {{ formatSize(child.bytesTotal) || '—' }}
                                </span>
                              </div>
                              <div class="mt-1">
                                <AppProgress :value="Number(child.percent) || 0" :status="progressStatus(child.status)" :height="6" />
                              </div>
                            </div>
                            <RowActionMenu
                              v-if="childActions(child).length"
                              trigger="hover"
                              placement="bottom-end"
                              :label="child.fileName"
                              :actions="childActions(child)"
                              @select="(key) => onChildAction(child, key)"
                            />
                          </div>
                          <p v-if="child.error" class="border-b border-line bg-danger-soft px-3 py-1 text-xs text-danger">
                            {{ child.error }}
                          </p>
                        </template>
                      </ProgressiveList>
                    </div>
                  </div>
                  <p v-else-if="row.status === 'SCANNING'" class="text-xs text-muted">子项还在扫描或入队中…</p>
                  <p v-else class="text-xs text-muted">没有子项</p>
                </div>
              </div>
            </template>
          </ProgressiveList>
          <p v-if="!groups.length" class="py-8 text-center text-sm text-muted">还没有下载任务</p>
        </div>

        <SelectionDock v-if="groupSelection.length">
          <template #info>已选 <b>{{ groupSelection.length }}</b> 项</template>
          <template #actions>
            <AppButton
              v-if="failedSelectedCount"
              size="sm"
              variant="warning"
              :loading="retryBusy"
              @click="retrySelectedFailed"
            >
              重试失败项 ({{ failedSelectedCount }})
            </AppButton>
            <AppButton size="sm" variant="danger-soft" @click="deleteRecords(groupSelection)">删除记录</AppButton>
            <AppButton size="sm" variant="ghost" @click="clearGroupSelection">取消选择</AppButton>
          </template>
        </SelectionDock>
        <p v-if="downloadHint" class="mt-2 text-xs text-muted">{{ downloadHint }}</p>
      </section>

      <!-- 离线任务 -->
      <section v-if="tab === 'offline'" class="card p-3 md:p-4">
        <header class="mb-3 flex flex-wrap items-center gap-2">
          <h1 class="text-base font-semibold">离线任务</h1>
          <span class="text-xs text-muted">{{ tasks.length }} 项</span>
          <span class="flex-1" />
          <AppButton size="sm" variant="ghost" icon="refresh" @click="loadTasks">刷新</AppButton>
          <AppButton v-if="!offlineSelectionMode && tasks.length" class="md:hidden" size="sm" variant="ghost" @click="offlineSelectionMode = true">
            选择
          </AppButton>
        </header>

        <div :class="offlineSelection.length ? 'pb-28 md:pb-0' : ''">
          <ProgressiveList :items="tasks" :step="40">
            <template #default="{ row }">
              <div
                class="mb-2 grid grid-cols-[auto_auto_minmax(0,1fr)_auto] items-center gap-2 rounded-md border border-line bg-surface px-1.5 py-1.5 transition-colors hover:bg-surface2 md:grid-cols-[auto_auto_minmax(0,1fr)_140px_190px_100px_auto]"
                @touchstart.passive="startPress(offlinePressTimer, () => enterOfflineSelection(row))"
                @touchend="cancelPress(offlinePressTimer)"
                @touchmove="cancelPress(offlinePressTimer)"
              >
                <AppCheckbox
                  :class="offlineSelectionMode ? '' : 'max-md:hidden'"
                  :model-value="isOfflineSelected(row)"
                  :label="`选择任务 ${row.name || row.id}`"
                  @update:model-value="(value) => toggleOfflineRow(row, value)"
                />

                <span class="flex h-9 w-9 items-center justify-center rounded-md bg-surface2 text-muted max-md:col-start-2">
                  <AppIcon :name="row.type === 'folder' ? 'folder' : 'link'" :size="18" />
                </span>

                <div class="min-w-0 max-md:col-start-3">
                  <ScrollName :label="row.name || row.id">
                    <span class="block truncate text-sm font-medium" :title="`名称来源：${sourceText(row.nameSource)}`">
                      {{ row.name || row.id }}
                    </span>
                  </ScrollName>
                  <p class="mt-0.5 flex flex-wrap items-center gap-x-3 text-xs text-muted md:hidden">
                    <span>{{ row.type === 'folder' ? `文件夹${row.folderChildCount ? ` · ${row.folderChildCount}${row.folderChildCountTruncated ? '+' : ''}` : ''}` : row.type === 'file' ? '文件' : '—' }}</span>
                    <span>{{ phaseText(row.phase) }}</span>
                    <span class="font-mono">{{ formatSize(row.fileSize) || '—' }}</span>
                    <span class="font-mono">{{ Number(row.progress) || 0 }}%</span>
                  </p>
                </div>

                <span class="hidden text-xs text-muted md:block">
                  {{ row.type === 'folder' ? `文件夹${row.folderChildCount ? ` · ${row.folderChildCount}${row.folderChildCountTruncated ? '+' : ''}` : ''}` : row.type === 'file' ? '文件' : '—' }}
                </span>
                <span class="hidden text-xs text-ink md:block">{{ phaseText(row.phase) }}</span>
                <div class="hidden md:block">
                  <AppProgress :value="Number(row.progress) || 0" :height="9" />
                </div>
                <span class="hidden text-right font-mono text-xs text-muted md:block">{{ formatSize(row.fileSize) || '—' }}</span>

                <div class="max-md:col-start-4">
                  <RowActionMenu
                    trigger="hover"
                    :label="row.name || row.id"
                    :actions="offlineActions()"
                    @select="() => deleteOffline([row])"
                  />
                </div>
              </div>
              <div class="mb-2 -mt-1.5 px-2 md:hidden">
                <AppProgress :value="Number(row.progress) || 0" :height="8" />
              </div>
            </template>
          </ProgressiveList>
          <p v-if="!tasks.length" class="py-8 text-center text-sm text-muted">没有离线任务</p>
        </div>

        <SelectionDock v-if="offlineSelection.length">
          <template #info>已选 <b>{{ offlineSelection.length }}</b> 项</template>
          <template #actions>
            <AppButton size="sm" variant="danger-soft" :loading="deleteBusy" @click="deleteOffline(offlineSelection)">删除</AppButton>
            <AppButton size="sm" variant="ghost" @click="clearOfflineSelection">取消选择</AppButton>
          </template>
        </SelectionDock>
        <p v-if="offlineHint" class="mt-2 text-xs text-muted">{{ offlineHint }}</p>
      </section>

      <!-- 回收站 -->
      <TrashView v-if="tab === 'trash'" ref="trashRef" @count="onTrashCount" />
    </template>

    <!-- 重命名：单输入框，预填原名，实时校验与服务端相同的规则 -->
    <AppDialog v-model:open="renameDialog.open" title="重命名">
      <form class="space-y-2" @submit.prevent="submitRename">
        <AppField label="新名称">
          <AppInput v-model="renameDialog.name" :maxlength="255" @submit="submitRename" />
        </AppField>
        <p v-if="renameError" class="text-xs text-danger">{{ renameError }}</p>
        <p v-else class="text-xs text-muted">原名：{{ renameDialog.original }}</p>
      </form>
      <template #footer>
        <div class="flex flex-wrap justify-end gap-2">
          <AppButton @click="renameDialog.open = false">取消</AppButton>
          <AppButton variant="primary" :disabled="!!renameError" :loading="renameDialog.busy" @click="submitRename">保存</AppButton>
        </div>
      </template>
    </AppDialog>

    <!-- 移动 / 复制：目录树懒加载，点目录名选中，底部确认 -->
    <AppDialog v-model:open="targetDialog.open" :title="targetDialog.mode === 'move' ? '移动到…' : '复制到…'">
      <p class="mb-1 text-xs text-muted">目标：{{ targetDialog.pathLabel }}</p>
      <div class="max-h-[46vh] overflow-auto rounded-md border border-line p-1">
        <DirTreePicker ref="treeRef" :node="targetTree" :selected-id="targetDialog.targetId" @select="onSelectTarget" />
      </div>
      <p class="mt-2 text-xs text-muted">
        本次目标：{{ targetDialog.label }}（{{ targetDialog.ids.length }} 项）；不能选择所选文件夹自身或其子目录。
        {{ targetDialog.mode === 'copy' ? '复制由 PikPak 服务端按任务执行，源文件保留。' : '' }}
      </p>
      <template #footer>
        <div class="flex flex-wrap justify-end gap-2">
          <AppButton @click="targetDialog.open = false">取消</AppButton>
          <AppButton variant="primary" :loading="targetDialog.busy" @click="submitMoveCopy">
            在此处{{ targetDialog.mode === 'move' ? '移动' : '复制' }}
          </AppButton>
        </div>
      </template>
    </AppDialog>

    <PlayerDialog ref="playerRef" v-model="playerOpen" :item="playingItem" />

    <PreviewDialog v-model="previewOpen" :item="previewItem" @download="downloadPreviewItem" />

    <ArchiveDialog v-model="archiveOpen" :item="archiveItem" @done="loadList()" />

    <TorrentDialog
      v-model="torrentOpen"
      :item="torrentItem"
      :parent-id="currentParentId"
      @saved="loadTasks()"
    />

    <!-- 移动端批量操作抽屉（桌面用行内浮层） -->
    <AppSheet v-model:open="batchSheet" title="批量操作">
      <div class="space-y-2">
        <AppButton block icon="move" @click="batchMoveCopy('move')">移动到…</AppButton>
        <AppButton block icon="copy" @click="batchMoveCopy('copy')">复制到…</AppButton>
      </div>
    </AppSheet>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DirTreePicker from '../components/DirTreePicker.vue'
import DriveSortControl from '../components/DriveSortControl.vue'
import PlayerDialog from '../components/PlayerDialog.vue'
import ArchiveDialog from '../components/ArchiveDialog.vue'
import PreviewDialog from '../components/PreviewDialog.vue'
import TorrentDialog from '../components/TorrentDialog.vue'
import RowActionMenu from '../components/RowActionMenu.vue'
import ScrollName from '../components/ui/ScrollName.vue'
import TrashView from './TrashView.vue'
import AppAlert from '../components/ui/AppAlert.vue'
import AppButton from '../components/ui/AppButton.vue'
import AppCheckbox from '../components/ui/AppCheckbox.vue'
import AppDialog from '../components/ui/AppDialog.vue'
import AppField from '../components/ui/AppField.vue'
import AppIcon from '../components/ui/AppIcon.vue'
import AppInput from '../components/ui/AppInput.vue'
import AppMenu from '../components/ui/AppMenu.vue'
import AppProgress from '../components/ui/AppProgress.vue'
import AppSegmented from '../components/ui/AppSegmented.vue'
import ProgressiveList from '../components/ui/ProgressiveList.vue'
import SelectionDock from '../components/ui/SelectionDock.vue'
import AppSheet from '../components/ui/AppSheet.vue'
import { confirmDialog } from '../components/ui/confirm'
import { tabCounts } from '../counts'
import { api, errorDetail, errorText, formatSize, formatSpeed } from '../api'
import { readDriveSort, saveDriveSort, sortDriveItems } from '../driveSort'
import { previewKindFor } from '../preview'
import { archiveEntryState } from '../archive'
import { openTorrent, saveOfflineLink, torrentEntryState, torrentErrorText } from '../torrent'
import { toast } from '../components/ui/toast'
import { refreshAuth, state } from '../store'

const route = useRoute()
const router = useRouter()

const loginTab = ref('password')
const loginTabs = [
  { key: 'password', label: '账号密码' },
  { key: 'token', label: 'AccessToken' },
]
const signin = reactive({ account: '', password: '' })
const signinError = ref('')
const signinBusy = ref(false)
const captcha = reactive({ visible: false, id: '', note: '', ttl: '', answer: '' })
const captchaBusy = ref(false)
const tokenForm = reactive({ account: '', accessToken: '', refreshToken: '' })
const tokenError = ref('')
const tokenBusy = ref(false)

const TRASH_TAB = 'trash'
const VALID_TABS = ['drive', 'downloads', 'offline', TRASH_TAB]
const tab = computed(() => tabFromRoute())
const trashRef = ref(null)
const crumbs = ref([{ id: '', name: '根目录' }])
const items = ref([])
// 排序只作用于目录列表的展示数据源；items 本身保持接口原序，供全选/选中按 id 使用。
const driveSort = reactive(readDriveSort())
const sortedItems = computed(() => sortDriveItems(items.value, driveSort.dimension, driveSort.direction))
watch(
  () => [driveSort.dimension, driveSort.direction],
  () => saveDriveSort({ ...driveSort }),
)
const listStatus = ref('')
const listHint = ref('只展示第一页；文件与文件夹都能勾选，混合下载会合并成一个批次（文件夹先递归扫描）。')
const listBusy = ref(false)
const driveSelection = ref([])
const driveSelectionMode = ref(false)
const batchBusy = ref(false)
const linksOpen = ref(false)
const batchSheet = ref(false)
const batchMoreActions = [
  { key: 'move', label: '移动到', icon: 'move' },
  { key: 'copy', label: '复制到', icon: 'copy' },
]
const currentParentId = computed(() => crumbs.value[crumbs.value.length - 1].id)
const selectedBytes = computed(() =>
  driveSelection.value.filter((row) => !row.isFolder).reduce((sum, row) => sum + (row.sizeBytes || 0), 0),
)
const folderSelectionCount = computed(() => driveSelection.value.filter((row) => row.isFolder).length)
const fileSelectionCount = computed(() => driveSelection.value.filter((row) => !row.isFolder).length)

const downloads = ref([])
const groups = ref([])
const groupSelection = ref([])
const groupSelectionMode = ref(false)
const downloadHint = ref('')
const retryBusy = ref(false)
// 展开中的批次：全量刷新后仍保留展开状态
const expandedGroupIds = reactive(new Set())
let downloadsSyncTimer = null

const tasks = ref([])
const offlineSelection = ref([])
const offlineSelectionMode = ref(false)
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
const previewOpen = ref(false)
const previewItem = ref(null)
const archiveOpen = ref(false)
const archiveItem = ref(null)
const torrentOpen = ref(false)
const torrentItem = ref(null)

const activeGroupCount = computed(() =>
  groups.value.filter((group) => ['SCANNING', 'QUEUED', 'DOWNLOADING'].includes(group.status)).length,
)
const unfinishedTaskCount = computed(() =>
  tasks.value.filter((task) => !['PHASE_TYPE_COMPLETE', 'PHASE_TYPE_ERROR'].includes(task.phase)).length,
)
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
const progressStatus = (status) => (status === 'COMPLETED' ? 'success' : status === 'FAILED' ? 'error' : '')
const statusChipClass = (status) =>
  ({
    FAILED: 'bg-danger-soft text-danger',
    COMPLETED: 'bg-success-soft text-success',
    DOWNLOADING: 'bg-brand-soft text-brand',
    SCANNING: 'bg-surface3 text-muted',
    QUEUED: 'bg-surface3 text-muted',
    PAUSED: 'bg-warning-soft text-warning',
    CANCELLED: 'bg-surface3 text-muted',
    PENDING: 'bg-surface3 text-muted',
  }[status] || 'bg-surface3 text-muted')
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

function groupMetaText(group) {
  if (group.status === 'SCANNING') {
    return `已发现 ${group.childCount} 个文件 / ${formatSize(group.bytesTotal) || '0'}`
  }
  return `${group.completedCount}/${group.childCount} · ${formatSize(group.bytesDone) || '0'} / ${formatSize(group.bytesTotal) || '—'}`
}

/* 长按进入选择模式（移动端）：500ms 后选中当前行并显示 checkbox */
const drivePressTimer = ref(null)
const groupPressTimer = ref(null)
const offlinePressTimer = ref(null)

function startPress(timerRef, callback) {
  cancelPress(timerRef)
  timerRef.value = setTimeout(callback, 500)
}
function cancelPress(timerRef) {
  if (timerRef.value) clearTimeout(timerRef.value)
  timerRef.value = null
}
function enterDriveSelection(row) {
  driveSelectionMode.value = true
  if (!isDriveSelected(row)) toggleDriveRow(row, true)
}
function enterGroupSelection(row) {
  groupSelectionMode.value = true
  if (!isGroupSelected(row)) toggleGroupRow(row, true)
}
function enterOfflineSelection(row) {
  offlineSelectionMode.value = true
  if (!isOfflineSelected(row)) toggleOfflineRow(row, true)
}

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

// 目录勾选：文件与文件夹都可选；下载时文件夹走递归扫描
const isDriveSelected = (row) => driveSelection.value.some((item) => item.id === row.id)
const allDriveSelected = computed(
  () => items.value.length > 0 && driveSelection.value.length === items.value.length,
)
function toggleDriveRow(row, value) {
  const exists = isDriveSelected(row)
  if (value && !exists) driveSelection.value = [...driveSelection.value, row]
  if (!value && exists) driveSelection.value = driveSelection.value.filter((item) => item.id !== row.id)
  if (!driveSelection.value.length) driveSelectionMode.value = false
}
function toggleAllDrive() {
  driveSelection.value = allDriveSelected.value ? [] : items.value.slice()
  if (!driveSelection.value.length) driveSelectionMode.value = false
}
function clearDriveSelection() {
  driveSelection.value = []
  driveSelectionMode.value = false
}

const isGroupSelected = (row) => groupSelection.value.some((item) => item.id === row.id)
const allGroupsSelected = computed(
  () => groups.value.length > 0 && groupSelection.value.length === groups.value.length,
)
function toggleGroupRow(row, value) {
  const exists = isGroupSelected(row)
  if (value && !exists) groupSelection.value = [...groupSelection.value, row]
  if (!value && exists) groupSelection.value = groupSelection.value.filter((item) => item.id !== row.id)
  if (!groupSelection.value.length) groupSelectionMode.value = false
}
function toggleAllGroups() {
  groupSelection.value = allGroupsSelected.value ? [] : groups.value.slice()
  if (!groupSelection.value.length) groupSelectionMode.value = false
}
function clearGroupSelection() {
  groupSelection.value = []
  groupSelectionMode.value = false
}

const isOfflineSelected = (row) => offlineSelection.value.some((item) => item.id === row.id)
const allOfflineSelected = computed(
  () => tasks.value.length > 0 && offlineSelection.value.length === tasks.value.length,
)
function toggleOfflineRow(row, value) {
  const exists = isOfflineSelected(row)
  if (value && !exists) offlineSelection.value = [...offlineSelection.value, row]
  if (!value && exists) offlineSelection.value = offlineSelection.value.filter((item) => item.id !== row.id)
  if (!offlineSelection.value.length) offlineSelectionMode.value = false
}
function toggleAllOffline() {
  offlineSelection.value = allOfflineSelected.value ? [] : tasks.value.slice()
  if (!offlineSelection.value.length) offlineSelectionMode.value = false
}
function clearOfflineSelection() {
  offlineSelection.value = []
  offlineSelectionMode.value = false
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

function isExpanded(group) {
  return expandedGroupIds.has(group.id)
}

/** 展开时兜底拉一次全量；childCount 比本地子项多就补拉，展开状态按 id 记住。 */
function toggleExpand(group) {
  if (expandedGroupIds.has(group.id)) {
    expandedGroupIds.delete(group.id)
    return
  }
  expandedGroupIds.add(group.id)
  if (childrenOf(group).length < number(group.childCount)) scheduleDownloadsSync(300)
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
  goTab('downloads')
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
  goTab('downloads')
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
    const ok = await confirmDialog({
      title: '取消下载',
      message: `停止「${group.name || group.id}」这个父任务的全部下载。已经下载完成的文件会保留在本地，不会被删除。`,
      confirmText: '停止',
      cancelText: '继续下载',
      danger: true,
    })
    if (!ok) return
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
  const ok = await confirmDialog({
    title: '删除下载任务记录',
    message:
      `${title}\n只删任务记录与进度，不动磁盘上的文件。` +
      '正在下载的任务会先停止，已写入的分块/临时文件也会保留。' +
      detail,
    confirmText: '删除记录',
    danger: true,
  })
  if (!ok) return
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

async function loadTasks() {
  const result = await api('/api/tasks')
  if (result.ok) tasks.value = result.data?.items || []
}

async function deleteOffline(rows) {
  const ids = rows.map((row) => row.id)
  if (!ids.length || deleteBusy.value) return
  const title = rows.length === 1
    ? `删除离线任务「${rows[0].name || rows[0].id}」？`
    : `删除这 ${ids.length} 个离线任务？`
  const ok = await confirmDialog({
    title: '删除离线任务',
    message: `${title}\n仅删除任务，不会删除云端已完成的文件。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
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
  const previewKind = previewKindFor(row)
  // 视频沿用「播放」入口；图片/音频/PDF/文本/Markdown 给「预览」
  if (row.isVideo) actions.push({ key: 'play', label: '播放', icon: 'play', hint: `播放「${row.name || row.id}」` })
  else if (previewKind) actions.push({ key: 'preview', label: '预览', icon: 'eye', hint: `在线预览「${row.name || row.id}」` })
  if (archiveEntryState(row) === 'extractable') {
    actions.push({ key: 'extract', label: '解压', icon: 'archive', hint: `在线解压「${row.name || row.id}」到当前目录` })
  }
  if (torrentEntryState(row)) {
    actions.push({ key: 'torrent-open', label: '打开', icon: 'link', hint: `查看「${row.name || row.id}」的文件清单` })
    actions.push({ key: 'torrent-offline', label: '离线下载', icon: 'download', hint: '解析后直接提交到云端离线，不先展示清单' })
  }
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
  if (key === 'preview') return openPreview(row)
  if (key === 'extract') return openExtract(row)
  if (key === 'torrent-open') return openTorrentDialog(row)
  if (key === 'torrent-offline') return directTorrentOffline(row)
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
  const ok = await confirmDialog({
    title: '删除云端内容',
    message:
      title + listed +
      `\n${folders ? `其中 ${folders} 个文件夹会连同内容一起移入回收站；` : ''}` +
      '可在回收站恢复，不是永久删除。',
    confirmText: '移入回收站',
    danger: true,
  })
  if (!ok) return
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
  if (failedRows.length) {
    driveSelection.value = failedRows
    driveSelectionMode.value = true
  }
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
  batchSheet.value = false
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
  if (failedRows.length) {
    driveSelection.value = failedRows
    driveSelectionMode.value = true
  }
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

/** 非视频的可预览文件走统一预览弹层；视频继续用播放弹层（Range 探测逻辑复用）。 */
function openPreview(item) {
  const kind = previewKindFor(item)
  if (!kind) return
  if (kind === 'video') return openPlayer(item)
  previewItem.value = item
  previewOpen.value = true
}

async function downloadPreviewItem(item) {
  previewOpen.value = false
  await startDownload(item.id)
}

/** 在线解压只对单卷 zip/rar/7z 开放，分卷与 tar 在 driveActions 里就不给入口。 */
function openExtract(item) {
  if (archiveEntryState(item) !== 'extractable') return
  archiveItem.value = item
  archiveOpen.value = true
}

/** 「打开」先解析文件清单；「离线下载」跳过弹层，解析出 magnet 后直接提交。 */
function openTorrentDialog(item) {
  if (!torrentEntryState(item)) return
  torrentItem.value = item
  torrentOpen.value = true
}

async function directTorrentOffline(item) {
  toast('正在解析种子…', 'info', 8000)
  const info = await openTorrent(item.id)
  if (!info.ok) {
    toast.error(torrentErrorText(info))
    return
  }
  const result = await saveOfflineLink(info.data?.magnet || '', currentParentId.value)
  if (!result.ok) {
    toast.error(torrentErrorText(result))
    return
  }
  toast.success(result.data?.instantComplete
    ? `已秒传：${result.data.fileId || '（未知）'}`
    : `已提交离线任务：${result.data?.taskId || '（无任务 ID）'}`)
  await loadTasks()
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

// Tab 与地址栏双向同步：`/trash` 深链直接进回收站；其它 Tab 用 `?tab=` 持久化
function tabFromRoute() {
  if (route.path === '/trash') return TRASH_TAB
  const query = String(route.query.tab || '')
  return VALID_TABS.includes(query) ? query : 'drive'
}

function goTab(next) {
  if (next === TRASH_TAB) {
    if (route.path !== '/trash') router.push({ path: '/trash' })
    return
  }
  router.push({ path: '/', query: next === 'drive' ? {} : { tab: next } })
}

function onTrashCount(count) {
  tabCounts.trash = count
}

watch(tab, (next, previous) => {
  if (previous) scrollByTab[previous] = window.scrollY
  if (next === 'downloads') loadDownloads()
  if (next === TRASH_TAB) trashRef.value?.load?.()
  nextTick(() => {
    window.scrollTo({ top: scrollByTab[next] || 0 })
  })
})

watch(activeGroupCount, (value) => (tabCounts.downloads = value), { immediate: true })
watch(unfinishedTaskCount, (value) => (tabCounts.offline = value), { immediate: true })

watch(
  () => state.auth.authenticated,
  (authenticated) => {
    if (!authenticated) closeEvents()
  },
)

onMounted(async () => {
  await loadAuth()
})

onUnmounted(() => {
  closeEvents()
  if (authTimer) clearTimeout(authTimer)
  if (tasksRefreshTimer) clearTimeout(tasksRefreshTimer)
  if (downloadsSyncTimer) clearTimeout(downloadsSyncTimer)
  cancelPress(drivePressTimer)
  cancelPress(groupPressTimer)
  cancelPress(offlinePressTimer)
})
</script>
