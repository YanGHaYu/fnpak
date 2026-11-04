// Piko 服务端 M1 页面：导入令牌 → 列目录。
// 不引框架、不依赖 CDN；所有请求都走同源的 /api。
(() => {
  const $ = (id) => document.getElementById(id);
  const els = {
    health: $('health-badge'),
    badge: $('auth-badge'),
    loginPanel: $('login-panel'),
    drivePanel: $('drive-panel'),
    form: $('token-form'),
    account: $('account'),
    accessToken: $('accessToken'),
    refreshToken: $('refreshToken'),
    loginError: $('login-error'),
    up: $('up'),
    parentId: $('parent-id'),
    load: $('load'),
    logout: $('logout'),
    path: $('path'),
    status: $('status'),
    items: $('items'),
  };

  // 面包屑：第一项固定是根目录（id 为空串，SDK 的根目录约定）
  let crumbs = [{ id: '', name: '根目录' }];

  const currentParentId = () => crumbs[crumbs.length - 1].id;

  async function api(path, options = {}) {
    const response = await fetch(path, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    });
    let data = null;
    if (response.status !== 204) {
      const text = await response.text();
      if (text) {
        try { data = JSON.parse(text); } catch (_) { data = null; }
      }
    }
    return { ok: response.ok, status: response.status, data };
  }

  function errorText(result) {
    return result.data?.error?.message || `请求失败（HTTP ${result.status}）`;
  }

  function formatSize(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return '';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
  }

  function typeOf(item) {
    if (item.isFolder) return '目录';
    if (item.isVideo) return '视频';
    return item.mimeType || '文件';
  }

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
      cell.colSpan = 3;
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

      row.append(name, size, kind);
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
    const next = result.data?.nextPageToken;
    els.status.textContent = next
      ? `${items.length} 项（还有下一页，M1 页面暂不翻页）`
      : `${items.length} 项`;
  }

  function enter(item) {
    crumbs = [...crumbs, { id: item.id, name: item.name }];
    loadList();
  }

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

  // 健康态独立于登录态：后端连不上时页面照样要能打开并说清原因
  async function refreshHealth() {
    try {
      const result = await api('/api/health');
      if (!result.ok || !result.data?.ok) throw new Error(`HTTP ${result.status}`);
      const info = result.data;
      els.health.className = 'badge ok';
      els.health.textContent = `服务正常 ${info.version} · ${info.uptimeSeconds}s`;
      els.health.title = `home: ${info.home}`;
    } catch (error) {
      els.health.className = 'badge off';
      els.health.textContent = `服务异常：${error.message}`;
    }
  }

  async function refreshAuth() {
    const result = await api('/api/auth/state');
    if (!result.ok) {
      els.badge.className = 'badge off';
      els.badge.textContent = '无法获取状态';
      return;
    }
    const state = result.data;
    if (state.authenticated) {
      els.badge.className = 'badge ok';
      els.badge.textContent = `已登录：${state.displayName || state.account}`;
      els.loginPanel.hidden = true;
      els.drivePanel.hidden = false;
      await loadList();
    } else {
      els.badge.className = 'badge pending';
      els.badge.textContent = state.initializing ? '恢复登录态…' : '未登录';
      els.drivePanel.hidden = true;
      els.loginPanel.hidden = false;
      if (state.initializing) setTimeout(refreshAuth, 1000);
    }
  }

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
  renderCrumbs();
  refreshHealth();
  refreshAuth();
  // 健康态每 10s 刷新：用户能看见服务是否还活着
  setInterval(refreshHealth, 10000);
})();
