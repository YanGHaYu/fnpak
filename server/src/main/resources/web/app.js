// Piko 服务端 M2 页面：访问口令 → 导入令牌 → 列目录 / 链接解析 / 本机下载。
// 不引框架、不依赖 CDN；所有请求走同源的 /api，会话靠 HttpOnly Cookie。
(() => {
  const $ = (id) => document.getElementById(id);
  const els = {
    health: $('health-badge'),
    badge: $('auth-badge'),
    accessPanel: $('access-panel'),
    accessForm: $('access-form'),
    passcode: $('passcode'),
    accessError: $('access-error'),
    settingsPanel: $('settings-panel'),
    settingsVersion: $('settings-version'),
    tokenFingerprint: $('token-fingerprint'),
    tokenSource: $('token-source'),
    tokenPath: $('token-path'),
    passcodeForm: $('passcode-form'),
    newPasscode: $('new-passcode'),
    passcodeStatus: $('passcode-status'),
    shareSelect: $('share-select'),
    subPath: $('sub-path'),
    testWrite: $('test-write'),
    saveDownloadDir: $('save-download-dir'),
    downloadDir: $('download-dir'),
    downloadStatus: $('download-status'),
    sharesNote: $('shares-note'),
    refreshShares: $('refresh-shares'),
    signinPanel: $('signin-panel'),
    signinForm: $('signin-form'),
    signinAccount: $('signin-account'),
    signinPassword: $('signin-password'),
    signinError: $('signin-error'),
    captchaBox: $('captcha-box'),
    captchaImage: $('captcha-image'),
    captchaNote: $('captcha-note'),
    captchaAnswer: $('captcha-answer'),
    captchaRefresh: $('captcha-refresh'),
    captchaTtl: $('captcha-ttl'),
    loginPanel: $('login-panel'),
    form: $('token-form'),
    account: $('account'),
    accessToken: $('accessToken'),
    refreshToken: $('refreshToken'),
    loginError: $('login-error'),
    drivePanel: $('drive-panel'),
    up: $('up'),
    parentId: $('parent-id'),
    load: $('load'),
    logout: $('logout'),
    path: $('path'),
    status: $('status'),
    items: $('items'),
    linksPanel: $('links-panel'),
    linkInput: $('link-input'),
    passCode: $('pass-code'),
    parse: $('parse'),
    linkStatus: $('link-status'),
    linkItems: $('link-items'),
    saveOffline: $('save-offline'),
    saveStatus: $('save-status'),
    downPanel: $('down-panel'),
    refreshDowns: $('refresh-downs'),
    downItems: $('down-items'),
    tasksPanel: $('tasks-panel'),
    refreshTasks: $('refresh-tasks'),
    taskItems: $('task-items'),
    playerPanel: $('player-panel'),
    playerTitle: $('player-title'),
    playerMode: $('player-mode'),
    playerForceProxy: $('player-force-proxy'),
    playerClose: $('player-close'),
    playerVideo: $('player-video'),
    playerMeta: $('player-meta'),
    playerDiag: $('player-diag'),
  };

  let crumbs = [{ id: '', name: '根目录' }];
  let parsedLink = null;
  let eventSource = null;
  // 当前验证码挑战；为空表示这次登录不需要（或还没申请）
  let signinChallenge = null;
  const currentParentId = () => crumbs[crumbs.length - 1].id;

  function showAccess(message) {
    els.accessPanel.hidden = false;
    els.signinPanel.hidden = true;
    els.loginPanel.hidden = true;
    hideCaptcha();
    els.drivePanel.hidden = true;
    els.linksPanel.hidden = true;
    els.downPanel.hidden = true;
    els.tasksPanel.hidden = true;
    els.settingsPanel.hidden = true;
    if (eventSource) { eventSource.close(); eventSource = null; }
    els.accessError.textContent = message || '';
    els.accessError.hidden = !message;
    els.badge.className = 'badge pending';
    els.badge.textContent = '需要访问口令';
  }

  async function api(path, options = {}) {
    const response = await fetch(path, {
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      ...options,
    });
    let data = null;
    if (response.status !== 204) {
      const text = await response.text();
      if (text) {
        try { data = JSON.parse(text); } catch (_) { data = null; }
      }
    }
    // 口令失效统一收敛：所有面板都退回口令卡片，避免每个调用点各自处理
    if (response.status === 401 && data?.error?.code === 'access_denied') {
      showAccess('访问口令已失效，请重新输入');
    }
    return { ok: response.ok, status: response.status, data };
  }

  const errorText = (result) => result.data?.error?.message || `请求失败（HTTP ${result.status}）`;

  function formatSize(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return '';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
  }

  function formatSpeed(bytes) {
    return bytes > 0 ? `${formatSize(bytes)}/s` : '—';
  }

  const typeOf = (item) => item.isFolder ? '目录' : item.isVideo ? '视频' : (item.mimeType || '文件');

  // ---------- 访问口令 ----------

  async function refreshAccess() {
    const result = await api('/api/access/state');
    if (!result.ok || !result.data?.authenticated) { showAccess(); return false; }
    els.accessPanel.hidden = true;
    // 设置只要求过访问门，不要求已登录 PikPak：改口令/换下载位置都是本机管理员操作
    els.settingsPanel.hidden = false;
    await loadSettings();
    return true;
  }

  els.accessForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    els.accessError.hidden = true;
    const result = await api('/api/access/login', {
      method: 'POST',
      body: JSON.stringify({ passcode: els.passcode.value.trim() }),
    });
    if (!result.ok) {
      els.accessError.textContent = errorText(result);
      els.accessError.hidden = false;
      return;
    }
    els.passcode.value = '';
    await boot();
  });

  // ---------- 账号密码登录 + 验证码 ----------
  //
  // PikPak 的验证码是服务端风控令牌而不是图片：接口仍按 {challengeId, imageDataUrl, expiresInSec}
  // 返回，imageDataUrl 目前恒为 null；将来换成图片挑战时，前端不用改就能把图显示出来。

  function hideCaptcha() {
    els.captchaBox.hidden = true;
    els.captchaImage.hidden = true;
    els.captchaImage.removeAttribute('src');
    els.captchaAnswer.value = '';
    els.captchaTtl.textContent = '';
    signinChallenge = null;
  }

  function applyCaptcha(data) {
    signinChallenge = { id: data.challengeId };
    els.captchaBox.hidden = false;
    els.captchaNote.textContent = data.note || '';
    els.captchaTtl.textContent = `挑战有效约 ${data.expiresInSec} 秒`;
    els.captchaAnswer.placeholder = data.imageDataUrl ? '输入图片里的字符' : '当前没有图片，可留空';
    if (data.imageDataUrl) {
      els.captchaImage.src = data.imageDataUrl;
      els.captchaImage.hidden = false;
    } else {
      els.captchaImage.hidden = true;
      els.captchaImage.removeAttribute('src');
    }
  }

  function showSigninError(message, visible = true) {
    els.signinError.textContent = message || '';
    els.signinError.hidden = !visible || !message;
  }

  async function refreshCaptcha() {
    const account = els.signinAccount.value.trim();
    if (!account) {
      showSigninError('先填账号，再刷新验证码');
      return;
    }
    els.captchaRefresh.disabled = true;
    const result = await api('/api/auth/captcha/init', {
      method: 'POST',
      body: JSON.stringify({ account }),
    });
    els.captchaRefresh.disabled = false;
    if (!result.ok) {
      showSigninError(errorText(result));
      return;
    }
    applyCaptcha(result.data);
    showSigninError('', false);
  }

  els.signinForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const account = els.signinAccount.value.trim();
    const password = els.signinPassword.value;
    if (!account || !password) {
      showSigninError('账号与密码都要填');
      return;
    }
    showSigninError('', false);
    els.signinPanel.querySelector('button[type="submit"]').disabled = true;
    const body = { account, password };
    if (signinChallenge) {
      body.challengeId = signinChallenge.id;
      body.captchaAnswer = els.captchaAnswer.value.trim();
    }
    const result = await api('/api/auth/login', { method: 'POST', body: JSON.stringify(body) });
    els.signinPanel.querySelector('button[type="submit"]').disabled = false;
    if (result.ok) {
      els.signinPassword.value = '';
      hideCaptcha();
      els.signinForm.reset();
      crumbs = [{ id: '', name: '根目录' }];
      await refreshAuth();
      return;
    }
    const code = result.data?.error?.code;
    if (result.status === 401 && code === 'captcha_required') {
      // 服务端已经申请了新的挑战，直接展示并让用户重试（没有图片时留空即可）
      applyCaptcha(result.data);
      showSigninError(result.data.error.message);
      return;
    }
    // 挑战用过/过期后不能再用，下次提交不带 challengeId，由服务端按需重新下发
    hideCaptcha();
    showSigninError(result.data?.error?.message || `登录失败（HTTP ${result.status}）`);
  });

  els.captchaRefresh.addEventListener('click', refreshCaptcha);
  els.captchaImage.addEventListener('click', refreshCaptcha);

  // ---------- 健康态与登录态 ----------

  async function refreshHealth() {
    try {
      const result = await api('/api/health');
      if (!result.ok || !result.data?.ok) throw new Error(`HTTP ${result.status}`);
      els.health.className = 'badge ok';
      els.health.textContent = `服务正常 ${result.data.version} · ${result.data.uptimeSeconds}s`;
      els.health.title = `home: ${result.data.home}`;
    } catch (error) {
      els.health.className = 'badge off';
      els.health.textContent = `服务异常：${error.message}`;
    }
  }

  async function refreshAuth() {
    const result = await api('/api/auth/state');
    if (!result.ok) {
      if (result.status !== 401) {
        els.badge.className = 'badge off';
        els.badge.textContent = '无法获取状态';
      }
      return;
    }
    const state = result.data;
    if (state.authenticated) {
      els.badge.className = 'badge ok';
      els.badge.textContent = `已登录：${state.displayName || state.account}`;
      els.signinPanel.hidden = true;
      hideCaptcha();
      els.loginPanel.hidden = true;
      els.drivePanel.hidden = false;
      els.linksPanel.hidden = false;
      els.downPanel.hidden = false;
      els.tasksPanel.hidden = false;
      await loadList();
      await loadDownloads();
      await loadTasks();
      openEvents();
    } else {
      els.badge.className = 'badge pending';
      els.badge.textContent = state.initializing ? '恢复登录态…' : '未登录';
      els.drivePanel.hidden = true;
      els.linksPanel.hidden = true;
      els.downPanel.hidden = true;
      els.tasksPanel.hidden = true;
      els.signinPanel.hidden = false;
      els.signinAccount.value = state.account || els.signinAccount.value;
      els.loginPanel.hidden = false;
      if (state.initializing) setTimeout(refreshAuth, 1000);
    }
  }

  // ---------- 设置 ----------

  function errorMessage(result, action) {
    const code = result.data?.error?.code;
    const message = result.data?.error?.message;
    if (!result.data) return `${action}失败：HTTP ${result.status}`;
    return `${action}失败：HTTP ${result.status}${code ? ` [${code}]` : ''} ${message || ''}`;
  }

  async function loadSettings() {
    const result = await api('/api/settings');
    if (!result.ok) {
      els.downloadStatus.textContent = `读取设置失败：HTTP ${result.status}`;
      return;
    }
    const data = result.data;
    els.settingsVersion.textContent = `服务端 ${data.version}`;
    els.tokenFingerprint.textContent = data.accessToken.fingerprint;
    els.tokenSource.textContent = `口令来源：${data.accessToken.source}`;
    els.tokenPath.textContent = data.accessToken.filePath;
    els.downloadDir.textContent = data.downloadDir;
    const managed = data.accessToken.managedExternally;
    els.newPasscode.disabled = managed;
    els.passcodeStatus.textContent = managed ? '口令由启动参数/环境变量管理，页面不可改（返回 409 token_managed_externally）。' : '';
    await loadShares();
  }

  async function loadShares() {
    const result = await api('/api/settings/shares');
    if (!result.ok) {
      els.downloadStatus.textContent = `读取授权目录失败：HTTP ${result.status}`;
      return;
    }
    const previous = els.shareSelect.value;
    els.shareSelect.innerHTML = '';
    for (const share of result.data.shares) {
      const option = document.createElement('option');
      const tag = share.source === 'declared' ? '内置共享' : '已授权';
      option.value = share.path;
      option.textContent = share.writable ? `${share.path}（${tag}）` : `${share.path}（${tag}，只读：${share.reason}）`;
      option.disabled = !share.writable;
      els.shareSelect.append(option);
    }
    if (result.data.shares.some((s) => s.path === previous)) {
      els.shareSelect.value = previous;
    }
    els.sharesNote.textContent = result.data.note || '';
    els.downloadStatus.textContent = result.data.shares.length
      ? ''
      : '没有可用的授权目录：到飞牛「应用设置 → 访问权限」点「+ 添加」授权一个文件夹，再点「刷新目录」或重启应用。';
  }

  async function submitDownloadDir(dryRun) {
    const subPath = els.subPath.value.trim();
    const shareRoot = els.shareSelect.value;
    if (!shareRoot) {
      els.downloadStatus.textContent = '请先选择一个授权目录';
      return;
    }
    els.downloadStatus.textContent = dryRun ? '测试中…' : '保存中…';
    const result = await api('/api/settings/download-dir', {
      method: 'POST',
      body: JSON.stringify({ shareRoot, subPath, dryRun }),
    });
    if (!result.ok) {
      els.downloadStatus.textContent = errorMessage(result, dryRun ? '测试写入' : '保存下载位置');
      return;
    }
    if (dryRun) {
      els.downloadStatus.textContent = `可写：${result.data.downloadDir}（尚未保存）`;
      return;
    }
    els.downloadDir.textContent = result.data.downloadDir;
    els.downloadStatus.textContent = `已保存：${result.data.downloadDir}。新任务立即生效，进行中的任务继续用旧目录。`;
    refreshHealth();
  }

  els.passcodeForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    els.passcodeStatus.textContent = '提交中…';
    const result = await api('/api/settings/access-token', {
      method: 'POST',
      body: JSON.stringify({ newPasscode: els.newPasscode.value.trim() }),
    });
    if (result.ok) {
      els.newPasscode.value = '';
      els.passcodeStatus.textContent = '口令已更新：其它会话全部失效，本页继续可用。';
      await loadSettings();
      return;
    }
    els.passcodeStatus.textContent = errorMessage(result, '修改口令');
  });

  els.testWrite.addEventListener('click', () => submitDownloadDir(true));
  els.saveDownloadDir.addEventListener('click', () => submitDownloadDir(false));
  els.refreshShares.addEventListener('click', () => loadShares());

  // ---------- 目录 ----------

  function renderCrumbs() {
    els.path.textContent = '/' + crumbs.slice(1).map((c) => c.name).join('/');
    els.up.disabled = crumbs.length === 1;
    els.parentId.value = currentParentId();
  }

  function renderItems(items) {
    els.items.replaceChildren();
    if (!items.length) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 4;
      cell.className = 'muted';
      cell.textContent = '这个目录是空的';
      row.appendChild(cell);
      els.items.appendChild(row);
      return;
    }
    for (const item of items) {
      const row = document.createElement('tr');
      if (item.isFolder) row.className = 'folder';

      const name = document.createElement('td');
      name.textContent = item.name || item.id;
      name.title = item.id;
      if (item.isFolder) name.addEventListener('click', () => enter(item));

      const size = document.createElement('td');
      size.className = 'num';
      size.textContent = item.isFolder ? '' : formatSize(item.sizeBytes);

      const kind = document.createElement('td');
      kind.className = 'muted';
      kind.textContent = typeOf(item);

      const actions = document.createElement('td');
      if (!item.isFolder) {
        if (item.isVideo) {
          const play = document.createElement('button');
          play.type = 'button';
          play.className = 'small';
          play.textContent = '播放';
          play.addEventListener('click', () => openPlayer(item));
          actions.appendChild(play);
        }
        const download = document.createElement('button');
        download.type = 'button';
        download.className = 'secondary small';
        download.textContent = '下载';
        download.addEventListener('click', () => startDownload(item.id, download));
        actions.appendChild(download);
      }

      row.append(name, size, kind, actions);
      els.items.appendChild(row);
    }
  }

  async function loadList() {
    els.status.textContent = '加载中…';
    const result = await api(`/api/drive/list?parentId=${encodeURIComponent(currentParentId())}`);
    if (!result.ok) {
      els.status.textContent = errorText(result);
      els.items.replaceChildren();
      return;
    }
    const items = result.data?.items || [];
    renderCrumbs();
    renderItems(items);
    els.status.textContent = result.data?.nextPageToken
      ? `${items.length} 项（还有下一页，页面暂不翻页）`
      : `${items.length} 项`;
  }

  function enter(item) { crumbs = [...crumbs, { id: item.id, name: item.name }]; loadList(); }

  function up() {
    if (crumbs.length === 1) return;
    crumbs = crumbs.slice(0, -1);
    loadList();
  }

  function goToTypedId() {
    const typed = els.parentId.value.trim();
    crumbs = typed ? [{ id: '', name: '根目录' }, { id: typed, name: typed }] : [{ id: '', name: '根目录' }];
    loadList();
  }

  // ---------- 浏览器播放（M3） ----------

  let playingItem = null;
  let playingMode = '';

  function showPlayerDiag(text, visible = true) {
    els.playerDiag.textContent = text;
    els.playerDiag.hidden = !visible;
  }

  /**
   * 播放前先打一次 Range 预检，把 HTTP 状态、content-type、content-range 留下来。
   * 这样 <video> 报错时能给出可诊断信息，而不是只有“播放失败”。
   */
  async function probeStream(id) {
    try {
      const response = await fetch(`/api/media/${encodeURIComponent(id)}/stream`, {
        headers: { Range: 'bytes=0-1023' },
        credentials: 'same-origin',
        cache: 'no-store',
      });
      const type = response.headers.get('content-type') || '—';
      const range = response.headers.get('content-range') || '—';
      const mode = response.headers.get('x-piko-media-mode') || '—';
      return {
        text: `预检：HTTP ${response.status}${response.redirected ? '（已跟随 302 到直链）' : ''}` +
          ` · content-type=${type} · content-range=${range} · mode=${mode}`,
        status: response.status,
      };
    } catch (error) {
      return { text: `预检失败：${error.name}: ${error.message}`, status: 0 };
    }
  }

  async function openPlayer(item) {
    playingItem = item;
    playingMode = '';
    els.playerPanel.hidden = false;
    els.playerTitle.textContent = item.name || item.id;
    els.playerMeta.textContent = `${formatSize(item.sizeBytes)} · ${item.mimeType || '未知类型'}`;
    els.playerMode.textContent = '查询模式…';
    els.playerMode.className = 'badge pending';
    els.playerVideo.removeAttribute('src');
    els.playerVideo.load();
    showPlayerDiag('', false);

    const info = await api(`/api/media/${encodeURIComponent(item.id)}/info`);
    if (playingItem !== item) return;
    if (!info.ok) {
      els.playerMode.textContent = '信息失败';
      els.playerMode.className = 'badge danger';
      showPlayerDiag(errorText(info));
      return;
    }
    const data = info.data || {};
    playingMode = data.mode || '';
    els.playerMode.textContent = playingMode === 'redirect' ? '直链模式（302）' : '转发模式（服务端代理）';
    els.playerMode.className = 'badge ok';
    els.playerMeta.textContent = `${formatSize(data.sizeBytes || item.sizeBytes)} · ${data.mimeType || '未知类型'} · 选路原因：${data.modeReason || '—'}`;

    const probe = await probeStream(item.id);
    if (playingItem !== item) return;
    if (probe.status === 416) {
      showPlayerDiag(`${probe.text}\n结论：服务端返回 416，Range 预检不合法或文件大小未知。`);
      return;
    }
    els.playerVideo.src = `/api/media/${encodeURIComponent(item.id)}/stream`;
    els.playerVideo.load();
    showPlayerDiag(probe.text);
  }

  els.playerVideo.addEventListener('error', () => {
    const code = els.playerVideo.error?.code;
    const names = { 1: 'MEDIA_ERR_ABORTED', 2: 'MEDIA_ERR_NETWORK', 3: 'MEDIA_ERR_DECODE', 4: 'MEDIA_ERR_SRC_NOT_SUPPORTED' };
    const reason = names[code] || `未知（${code}）`;
    const supported = playingItem?.mimeType || '未知';
    showPlayerDiag(
      `${els.playerDiag.textContent || '预检：未执行'}\n浏览器错误：${reason}\n声明类型：${supported}\n` +
      `提示：mkv/hevc 等封装浏览器通常无法解码；若 HTTP 正常但无法播放，播放器已拿到字节流。`,
    );
  });

  els.playerForceProxy.addEventListener('click', () => {
    if (!playingItem) return;
    els.playerMode.textContent = '转发模式（手动强制）';
    els.playerMode.className = 'badge ok';
    els.playerVideo.src = `/api/media/${encodeURIComponent(playingItem.id)}/stream?mode=proxy`;
    els.playerVideo.load();
    showPlayerDiag(`${els.playerDiag.textContent}\n已切换为 ?mode=proxy。`);
  });

  els.playerClose.addEventListener('click', () => {
    playingItem = null;
    els.playerVideo.pause();
    els.playerVideo.removeAttribute('src');
    els.playerVideo.load();
    els.playerPanel.hidden = true;
  });

  // ---------- 链接解析 / 离线任务 ----------

  function renderParsedFiles(data) {
    els.linkItems.replaceChildren();
    const files = data?.files || [];
    if (!files.length) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 3;
      cell.className = 'muted';
      cell.textContent = '没有解析出文件';
      row.appendChild(cell);
      els.linkItems.appendChild(row);
      return;
    }
    for (const file of files) {
      const row = document.createElement('tr');
      const name = document.createElement('td');
      name.textContent = file.name;
      const size = document.createElement('td');
      size.className = 'num';
      size.textContent = formatSize(file.sizeBytes);
      const ready = document.createElement('td');
      ready.className = 'muted';
      ready.textContent = file.instantReady ? '可秒传' : '需离线下载';
      row.append(name, size, ready);
      els.linkItems.appendChild(row);
    }
  }

  els.parse.addEventListener('click', async () => {
    const link = els.linkInput.value.trim();
    if (!link) { els.linkStatus.textContent = '先填链接'; return; }
    els.parse.disabled = true;
    els.linkStatus.textContent = '解析中…';
    els.saveOffline.disabled = true;
    parsedLink = null;
    const result = await api('/api/links/parse', {
      method: 'POST',
      body: JSON.stringify({ link, passCode: els.passCode.value.trim() }),
    });
    els.parse.disabled = false;
    if (!result.ok) {
      els.linkStatus.textContent = errorText(result);
      els.linkItems.replaceChildren();
      return;
    }
    parsedLink = { link, kind: result.data.kind };
    const kindText = result.data.kind === 'magnet' ? '磁力' : result.data.kind === 'share' ? '分享' : '直链';
    els.linkStatus.textContent = `${kindText} · ${result.data.name || '未命名'} · ${(result.data.files || []).length} 个文件` +
      (result.data.truncated ? '（目录更深，列表不完整）' : '');
    renderParsedFiles(result.data);
    els.saveOffline.disabled = false;
  });

  els.saveOffline.addEventListener('click', async () => {
    if (!parsedLink) return;
    els.saveOffline.disabled = true;
    els.saveStatus.textContent = '提交中…';
    const result = await api('/api/links/save', {
      method: 'POST',
      body: JSON.stringify({ link: parsedLink.link, parentId: currentParentId() }),
    });
    if (!result.ok) {
      els.saveStatus.textContent = errorText(result);
      els.saveOffline.disabled = false;
      return;
    }
    els.saveStatus.textContent = result.data.instantComplete
      ? `已秒传，文件 ID：${result.data.fileId || '（未知）'}`
      : `已提交离线任务：${result.data.taskId || '（无任务 ID）'}`;
    await loadTasks();
  });

  // ---------- 本机下载 ----------

  const downRows = new Map();

  function upsertDownload(item) {
    let row = downRows.get(item.id);
    if (!row) {
      row = document.createElement('tr');
      row.innerHTML = '<td class="name"></td><td class="num pct"></td><td class="num speed"></td>' +
        '<td class="state"></td><td class="actions"></td>';
      const name = row.querySelector('.name');
      name.title = item.fileId;
      row.querySelector('.actions').append(
        makeButton('暂停', () => downloadAction(item.id, 'pause')),
        makeButton('继续', () => downloadAction(item.id, 'resume', 'secondary')),
        makeButton('取消', () => downloadAction(item.id, 'cancel', 'danger')),
      );
      downRows.set(item.id, row);
      els.downItems.appendChild(row);
    }
    row.querySelector('.name').textContent = item.fileName;
    row.querySelector('.pct').textContent = `${item.percent}% · ${formatSize(item.bytesDone)}/${formatSize(item.bytesTotal)}`;
    row.querySelector('.speed').textContent = formatSpeed(item.speedBytesPerSec);
    const stateCell = row.querySelector('.state');
    stateCell.textContent = item.error ? `${item.status}：${item.error}` : item.status;
    stateCell.className = 'state ' + (item.status === 'FAILED' ? 'error' : item.status === 'COMPLETED' ? 'ok' : 'muted');
  }

  function makeButton(text, onClick, kind) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = text;
    button.className = `small ${kind || ''}`.trim();
    button.addEventListener('click', onClick);
    return button;
  }

  async function loadDownloads() {
    const result = await api('/api/downloads');
    if (!result.ok) return;
    downRows.clear();
    els.downItems.replaceChildren();
    (result.data?.items || []).forEach(upsertDownload);
  }

  async function downloadAction(id, action) {
    const result = await api(`/api/downloads/${encodeURIComponent(id)}/${action}`, { method: 'POST' });
    if (!result.ok) return;
    (result.data?.items || []).forEach(upsertDownload);
  }

  async function startDownload(fileId, button) {
    button.disabled = true;
    const result = await api('/api/downloads', { method: 'POST', body: JSON.stringify({ fileId }) });
    button.disabled = false;
    if (!result.ok) {
      els.status.textContent = errorText(result);
      return;
    }
    (result.data?.items || []).forEach(upsertDownload);
  }

  // ---------- 离线任务 ----------

  const taskRows = new Map();

  function upsertTask(item) {
    let row = taskRows.get(item.id);
    if (!row) {
      row = document.createElement('tr');
      row.innerHTML = '<td class="id"></td><td class="num pct"></td><td class="phase"></td><td class="msg"></td>';
      row.querySelector('.id').textContent = item.id;
      taskRows.set(item.id, row);
      els.taskItems.appendChild(row);
    }
    row.querySelector('.pct').textContent = `${item.progress}%`;
    row.querySelector('.phase').textContent = item.phase;
    row.querySelector('.msg').textContent = item.message || '';
  }

  async function loadTasks() {
    const result = await api('/api/tasks');
    if (!result.ok) return;
    taskRows.clear();
    els.taskItems.replaceChildren();
    (result.data?.items || []).forEach(upsertTask);
  }

  // ---------- SSE ----------

  function openEvents() {
    if (eventSource) return;
    eventSource = new EventSource('/api/events');
    eventSource.addEventListener('download.progress', (event) => {
      try { upsertDownload(JSON.parse(event.data)); } catch (_) { /* 忽略坏帧 */ }
    });
    eventSource.addEventListener('task.progress', (event) => {
      try { upsertTask(JSON.parse(event.data)); } catch (_) { /* 忽略坏帧 */ }
    });
    // 断线由 EventSource 自带重连；重连后后端会先推当前快照
  }

  // ---------- 绑定 ----------

  els.form.addEventListener('submit', async (event) => {
    event.preventDefault();
    els.loginError.hidden = true;
    const body = {
      account: els.account.value.trim(),
      accessToken: els.accessToken.value.trim(),
      refreshToken: els.refreshToken.value.trim(),
    };
    if (!body.account || !body.accessToken) {
      els.loginError.textContent = '账号与 accessToken 都要填';
      els.loginError.hidden = false;
      return;
    }
    const result = await api('/api/auth/token', { method: 'POST', body: JSON.stringify(body) });
    if (!result.ok) {
      els.loginError.textContent = errorText(result);
      els.loginError.hidden = false;
      return;
    }
    els.form.reset();
    crumbs = [{ id: '', name: '根目录' }];
    await refreshAuth();
  });

  els.logout.addEventListener('click', async () => {
    await api('/api/auth/logout', { method: 'POST' });
    crumbs = [{ id: '', name: '根目录' }];
    await refreshAuth();
  });

  els.up.addEventListener('click', up);
  els.load.addEventListener('click', goToTypedId);
  els.refreshDowns.addEventListener('click', loadDownloads);
  els.refreshTasks.addEventListener('click', loadTasks);

  async function boot() {
    refreshHealth();
    if (!(await refreshAccess())) return;
    await refreshAuth();
  }

  renderCrumbs();
  boot();
  // 健康态每 10s 刷新：用户能看见服务是否还活着
  setInterval(refreshHealth, 10000);
})();
