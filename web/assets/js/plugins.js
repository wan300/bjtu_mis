(function () {
  'use strict';
  const page = document.body.dataset.pluginPage;
  const categoryNames = { academic: '学业学习', campus: '校园生活', information: '信息服务', productivity: '效率工具', assistant: '智能助手', other: '其他' };
  let me = null;
  const escapeHtml = (value) => String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  const setStatus = (message, error = false) => { const node = document.querySelector('[data-status]'); if (node) { node.textContent = message; node.classList.toggle('is-error', error); } };

  async function request(url, options = {}) {
    const headers = { Accept: 'application/json', ...(options.headers || {}) };
    if (options.body && !(options.body instanceof FormData)) headers['Content-Type'] = 'application/json';
    if (options.method && options.method !== 'GET' && me?.csrfToken) headers['X-CSRF-Token'] = me.csrfToken;
    const response = await fetch(url, { credentials: 'same-origin', ...options, headers });
    const payload = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
    if (!response.ok) throw new Error(payload?.error?.message || `请求失败（${response.status}）`);
    return payload;
  }
  async function currentUser() { me = await request('/api/v1/auth/me'); return me; }

  function card(plugin) {
    return `<article class="plugin-card"><div class="plugin-card__head"><img src="${escapeHtml(plugin.iconUrl)}" alt=""><div><p class="plugin-kicker">${escapeHtml(categoryNames[plugin.category] || '其他')} · ${escapeHtml(plugin.version)}</p><h2><a href="detail.html?id=${encodeURIComponent(plugin.id)}">${escapeHtml(plugin.name)}</a></h2><p>${escapeHtml(plugin.author)}</p></div></div><p>${escapeHtml(plugin.description)}</p><div class="plugin-tags">${(plugin.tags || []).map((tag) => `<span>${escapeHtml(tag)}</span>`).join('')}</div><div class="plugin-card__meta"><span class="plugin-warning">未人工审核</span><code>${escapeHtml(String(plugin.commitSha).slice(0, 8))}</code></div><a class="btn btn--ghost" href="detail.html?id=${encodeURIComponent(plugin.id)}">查看风险与安装信息</a></article>`;
  }
  async function initCatalog() {
    const form = document.querySelector('[data-catalog-filter]'); const list = document.querySelector('[data-plugin-list]'); const more = document.querySelector('[data-load-more]'); let cursor = null;
    async function load(append = false) {
      setStatus('正在读取插件目录…'); const data = new FormData(form); const params = new URLSearchParams();
      for (const key of ['query', 'category']) if (data.get(key)) params.set(key, String(data.get(key))); if (append && cursor) params.set('cursor', cursor);
      try { const payload = await request(`/api/v1/plugins?${params}`); if (!append) list.innerHTML = ''; list.insertAdjacentHTML('beforeend', payload.items.map(card).join('')); cursor = payload.nextCursor; more.hidden = !cursor; setStatus(payload.items.length || append ? '' : '暂无符合条件的插件。'); } catch (error) { setStatus(error.message, true); }
    }
    form.addEventListener('submit', (event) => { event.preventDefault(); cursor = null; load(); }); more.addEventListener('click', () => load(true)); await load();
  }

  function detailSections(plugin) {
    const configuration = plugin.configuration || []; const permissions = [...(plugin.permissions?.required || []), ...(plugin.permissions?.optional || [])];
    return `<div class="plugin-detail__grid"><section><h2>权限与来源</h2><h3>权限</h3><ul>${permissions.map((item) => `<li><code>${escapeHtml(item)}</code></li>`).join('') || '<li>无</li>'}</ul><h3>允许的远端来源</h3><ul>${(plugin.allowedOrigins || []).map((item) => `<li><code>${escapeHtml(item)}</code></li>`).join('') || '<li>仅插件本地内容</li>'}</ul></section><section><h2>配置要求</h2>${configuration.length ? configuration.map((item) => `<div class="plugin-config-item"><strong>${escapeHtml(item.label)}</strong><code>${escapeHtml(item.key)}</code><span>${escapeHtml(item.type)}${item.required ? ' · 必填' : ''}</span><p>${escapeHtml(item.description)}</p></div>`).join('') : '<p>此插件不需要用户配置。</p>'}</section></div>`;
  }
  async function initDetail() {
    const id = new URLSearchParams(location.search).get('id'); if (!id) return setStatus('缺少插件 ID。', true);
    try { const plugin = await request(`/api/v1/plugins/${encodeURIComponent(id)}`); document.title = `${plugin.name} · 插件大厅`; document.querySelector('[data-plugin-detail]').innerHTML = `<header class="plugin-detail__header"><img src="${escapeHtml(plugin.iconUrl)}" alt=""><div><p class="plugin-kicker">${escapeHtml(categoryNames[plugin.category] || '其他')} · ${escapeHtml(plugin.version)}</p><h1>${escapeHtml(plugin.name)}</h1><p>${escapeHtml(plugin.description)}</p><span class="plugin-warning">自动校验通过 · 未人工审核</span></div></header>${detailSections(plugin)}<section class="plugin-digests"><h2>可验证发布信息</h2><dl><div><dt>仓库</dt><dd><a href="${escapeHtml(plugin.repositoryUrl)}" target="_blank" rel="noreferrer">${escapeHtml(plugin.repository)}</a></dd></div><div><dt>Commit</dt><dd><code>${escapeHtml(plugin.commitSha)}</code></dd></div><div><dt>归档 SHA-256</dt><dd><code>${escapeHtml(plugin.archiveSha256)}</code></dd></div><div><dt>dist digest</dt><dd><code>${escapeHtml(plugin.packageDigestSha256)}</code></dd></div><div><dt>包大小</dt><dd>${Math.ceil(plugin.packageBytes / 1024)} KiB</dd></div></dl><p>请在 Android 客户端的插件大厅中安装。客户端会在解压前后分别校验两个 digest。</p></section>`; setStatus(''); } catch (error) { setStatus(error.message, true); }
  }

  function renderAuth(user) { const target = document.querySelector('[data-auth-panel]'); if (target) target.innerHTML = user.authenticated ? `已使用 GitHub 账号 <strong>${escapeHtml(user.login)}</strong> 登录。` : '<a class="btn btn--primary" href="/api/v1/auth/github/start">使用 GitHub 登录</a><p>登录仅用于确认公开仓库管理权限和管理投稿。</p>'; }
  async function initSubmit() {
    const user = await currentUser(); renderAuth(user); const form = document.querySelector('[data-submission-form]'); form.hidden = !user.authenticated;
    form.addEventListener('submit', async (event) => { event.preventDefault(); setStatus('正在创建自动校验任务…'); try { const repositoryUrl = new FormData(form).get('repositoryUrl'); const result = await request('/api/v1/submissions', { method: 'POST', body: JSON.stringify({ repositoryUrl }) }); setStatus(`投稿已进入队列（${result.id}），可在“我的插件”查看结果。`); form.reset(); } catch (error) { setStatus(error.message, true); } });
  }
  function manageRow(item) {
    const pluginId = item.plugin_id || item.pluginId; const source = item.source_url || item.sourceUrl; const error = item.error_text || item.errorText;
    return `<article class="plugin-row"><div><p class="plugin-kicker">${escapeHtml(item.status)}</p><h2>${pluginId ? escapeHtml(pluginId) : '等待识别 manifest'}</h2><a href="${escapeHtml(source)}" target="_blank" rel="noreferrer">${escapeHtml(source)}</a>${error ? `<p class="plugin-error">${escapeHtml(error)}</p>` : ''}</div>${pluginId ? `<div class="plugin-row__actions"><button class="btn btn--ghost" data-action="revalidate" data-id="${escapeHtml(pluginId)}">重新校验</button><button class="btn btn--ghost" data-action="unpublish" data-id="${escapeHtml(pluginId)}">下架</button></div>` : ''}</article>`;
  }
  async function initManage() {
    const user = await currentUser(); renderAuth(user); if (!user.authenticated) return; const adminLink = document.querySelector('[data-admin-link]'); if (adminLink) adminLink.hidden = !user.admin; const target = document.querySelector('[data-manage-list]');
    async function refresh() { const payload = await request('/api/v1/me/plugins'); target.innerHTML = payload.items.map(manageRow).join('') || '<p>还没有投稿记录。</p>'; }
    target.addEventListener('click', async (event) => { const button = event.target.closest('button[data-action]'); if (!button) return; try { await request(`/api/v1/plugins/${encodeURIComponent(button.dataset.id)}/${button.dataset.action === 'revalidate' ? 'revalidate' : 'unpublish'}`, { method: 'POST', body: '{}' }); setStatus('操作已提交。'); await refresh(); } catch (error) { setStatus(error.message, true); } }); await refresh();
  }
  async function initAdmin() {
    const user = await currentUser(); if (!user.authenticated || !user.admin) return setStatus('需要管理员权限。', true); const payload = await request('/api/v1/admin/overview'); const target = document.querySelector('[data-admin-content]');
    target.innerHTML = `<section class="plugin-admin"><h2>插件</h2>${payload.plugins.map((item) => `<article class="plugin-row"><div><strong>${escapeHtml(item.plugin_id)}</strong><p>${escapeHtml(item.status)} · ${item.admin_suspended ? '管理员下架' : '正常'}</p></div><div class="plugin-row__actions"><button class="btn btn--ghost" data-admin-action="unpublish" data-id="${escapeHtml(item.plugin_id)}">管理员下架</button><button class="btn btn--ghost" data-admin-action="restore" data-id="${escapeHtml(item.plugin_id)}">恢复</button></div></article>`).join('')}</section><section class="plugin-admin"><h2>校验任务</h2>${payload.jobs.map((item) => `<article class="plugin-row"><div><strong>${escapeHtml(item.source_url)}</strong><p>${escapeHtml(item.status)} · 尝试 ${escapeHtml(item.attempt)}</p></div></article>`).join('')}</section><section class="plugin-admin"><h2>举报</h2>${payload.reports.map((item) => `<article class="plugin-row"><div><strong>${escapeHtml(item.plugin_id)} · ${escapeHtml(item.reason)}</strong><p>${escapeHtml(item.details)}</p></div><div class="plugin-row__actions"><button class="btn btn--ghost" data-report="resolved" data-id="${escapeHtml(item.id)}">已处理</button><button class="btn btn--ghost" data-report="dismissed" data-id="${escapeHtml(item.id)}">驳回</button></div></article>`).join('')}</section>`;
    target.addEventListener('click', async (event) => { const pluginButton = event.target.closest('[data-admin-action]'); const reportButton = event.target.closest('[data-report]'); try { if (pluginButton) await request(`/api/v1/admin/plugins/${encodeURIComponent(pluginButton.dataset.id)}/${pluginButton.dataset.adminAction}`, { method: 'POST', body: JSON.stringify({ reason: '管理员操作' }) }); if (reportButton) await request(`/api/v1/admin/reports/${encodeURIComponent(reportButton.dataset.id)}/resolve`, { method: 'POST', body: JSON.stringify({ status: reportButton.dataset.report }) }); if (pluginButton || reportButton) location.reload(); } catch (error) { setStatus(error.message, true); } }); setStatus('');
  }
  const initializers = { catalog: initCatalog, detail: initDetail, submit: initSubmit, manage: initManage, admin: initAdmin };
  Promise.resolve(initializers[page]?.()).catch((error) => setStatus(error.message, true));
})();
