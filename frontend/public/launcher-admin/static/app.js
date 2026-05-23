/* ============================================================
   OnNow TV V2 — Launcher Admin client
   ──────────────────────────────────────────────────────────── */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

function toast(msg, isError = false) {
    const el = $('#toast');
    if (!el) { console.log(msg); return; }
    el.textContent = msg;
    el.classList.toggle('err', isError);
    el.hidden = false;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { el.hidden = true; }, 3000);
}

async function api(path, opts = {}) {
    const r = await fetch(path, { credentials: 'same-origin', ...opts });
    if (r.status === 401) {
        showLogin();
        throw new Error('Unauthorized');
    }
    if (!r.ok) {
        const err = await r.text();
        throw new Error(err || `${r.status} error`);
    }
    return r.json();
}

/* ─────────────  Auth  ───────────── */
function showLogin() { $('#login').hidden = false; $('#app').hidden = true; }
function showApp()   { $('#login').hidden = true;  $('#app').hidden = false;  refreshAll(); }

$('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const token = e.target.token.value.trim();
    if (!token) return;
    try {
        const form = new FormData();
        form.append('token', token);
        const r = await fetch('/api/admin/login', { method: 'POST', body: form, credentials: 'same-origin' });
        if (!r.ok) throw new Error('Invalid token');
        showApp();
    } catch (err) {
        $('#loginErr').textContent = err.message;
    }
});

$('#logout').addEventListener('click', async () => {
    try { await api('/api/admin/logout', { method: 'POST' }); } catch {}
    showLogin();
});

/* ─────────────  Tabs  ───────────── */
$$('.tab').forEach(btn => btn.addEventListener('click', () => {
    $$('.tab').forEach(t => t.classList.remove('active'));
    $$('.tab-panel').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    $('#tab-' + btn.dataset.tab).classList.add('active');
}));

/* ─────────────  Initial load probe  ───────────── */
(async () => {
    try {
        await api('/api/admin/store');
        showApp();
    } catch {
        showLogin();
    }
})();

/* ─────────────  Refresh helpers  ───────────── */
async function refreshAll() {
    const store = await api('/api/admin/store');
    renderDock(store);
    renderWallpapers(store);
    renderApks(store);
    renderNotifications(store);
}

/* ─────────────  Dock  ───────────── */
function renderDock(store) {
    const list = $('#dockList');
    list.innerHTML = '';
    store.dock_tiles.forEach((t, idx) => {
        const li = document.createElement('li');
        li.dataset.idx = String(idx);
        li.innerHTML = `
            <div class="icon-cell">${(t.label || '?')[0].toUpperCase()}</div>
            <div class="fields">
                <div><label>Label</label><input data-k="label" value="${escapeAttr(t.label || '')}"></div>
                <div><label>Sub</label><input data-k="sub" value="${escapeAttr(t.sub || '')}"></div>
                <div><label>Target package</label><input data-k="target_package" value="${escapeAttr(t.target_package || '')}" placeholder="e.g. tv.onnowtv.app"></div>
                <div><label>Target URL</label><input data-k="target_url" value="${escapeAttr(t.target_url || '')}" placeholder="e.g. https://news.com"></div>
                <div><label>Icon URL</label><input data-k="icon_url" value="${escapeAttr(t.icon_url || '')}" placeholder="https://…/icon.png"></div>
                <div><label>Accent hex</label><input data-k="accent" value="${escapeAttr(t.accent || '')}" placeholder="#2BB6FF"></div>
            </div>
        `;
        list.appendChild(li);
    });
}

$('#saveDock').addEventListener('click', async () => {
    const tiles = $$('#dockList li').map(li => {
        const inputs = li.querySelectorAll('input');
        const out = { key: $('#dockList').children[li.dataset.idx]?.dataset.key };
        inputs.forEach(i => {
            out[i.dataset.k] = i.value.trim() || null;
        });
        // Preserve the original `key` (we don't expose it as editable)
        // by reading it from current state via fetch.
        return out;
    });
    // The dock tile schema requires `key` — read it from the store before
    // sending to avoid losing keys.  Use the live store as source of truth.
    const store = await api('/api/admin/store');
    const payload = store.dock_tiles.map((t, idx) => ({
        key: t.key,
        label: tiles[idx].label || t.label,
        sub: tiles[idx].sub || t.sub,
        icon_url: tiles[idx].icon_url || null,
        target_package: tiles[idx].target_package || null,
        target_url: tiles[idx].target_url || null,
        accent: tiles[idx].accent || null,
    }));
    try {
        await api('/api/admin/dock', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        toast('Dock saved — devices will pull on next poll');
    } catch (e) { toast('Save failed: ' + e.message, true); }
});

/* ─────────────  Wallpapers  ───────────── */
$('#wallpaperFile').addEventListener('change', async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const form = new FormData();
    form.append('file', f);
    try {
        await api('/api/admin/wallpapers', { method: 'POST', body: form });
        toast('Wallpaper uploaded');
        refreshAll();
    } catch (err) { toast('Upload failed: ' + err.message, true); }
});

function renderWallpapers(store) {
    const grid = $('#wallpaperGrid');
    grid.innerHTML = '';
    const ws = store.wallpapers || [];
    if (!ws.length) {
        grid.innerHTML = '<p style="color:var(--txt-tertiary);grid-column:1/-1;">No wallpapers uploaded yet.</p>';
        return;
    }
    ws.forEach(w => {
        const active = store.active_wallpaper_id === w.id;
        const card = document.createElement('div');
        card.className = 'card' + (active ? ' active' : '');
        card.innerHTML = `
            ${active ? '<span class="badge">ACTIVE</span>' : ''}
            <img src="${escapeAttr(w.url)}" alt="">
            <div class="meta">
                <span>${new Date(w.uploaded_at * 1000).toLocaleString()}</span>
                <div>
                    ${active ? '' : `<button class="activate" data-id="${w.id}">Activate</button>`}
                    <button class="delete" data-id="${w.id}">Delete</button>
                </div>
            </div>`;
        grid.appendChild(card);
    });
    grid.querySelectorAll('button.activate').forEach(b => b.addEventListener('click', async () => {
        try {
            await api('/api/admin/wallpapers/active', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: b.dataset.id }),
            });
            toast('Wallpaper activated');
            refreshAll();
        } catch (e) { toast('Failed: ' + e.message, true); }
    }));
    grid.querySelectorAll('button.delete').forEach(b => b.addEventListener('click', async () => {
        if (!confirm('Delete this wallpaper?')) return;
        try {
            await api('/api/admin/wallpapers/' + b.dataset.id, { method: 'DELETE' });
            toast('Wallpaper deleted');
            refreshAll();
        } catch (e) { toast('Delete failed: ' + e.message, true); }
    }));
}

/* ─────────────  APKs  ───────────── */
$('#apkSubmit').addEventListener('click', async () => {
    const name = $('#apkName').value.trim();
    if (!name) return toast('Name is required', true);
    const apkUrl  = $('#apkUrl').value.trim();
    const apkFile = $('#apkFile').files[0];
    if (!apkUrl && !apkFile) return toast('Enter a URL or upload a file', true);
    const form = new FormData();
    form.append('name', name);
    if ($('#apkPackage').value.trim()) form.append('package_id',  $('#apkPackage').value.trim());
    if ($('#apkVersion').value.trim()) form.append('version_name',$('#apkVersion').value.trim());
    if ($('#apkIconUrl').value.trim()) form.append('icon_url',    $('#apkIconUrl').value.trim());
    if ($('#apkDesc').value.trim())    form.append('description', $('#apkDesc').value.trim());
    try {
        if (apkFile) {
            form.append('file', apkFile);
            await api('/api/admin/apks/upload', { method: 'POST', body: form });
        } else {
            form.append('apk_url', apkUrl);
            await api('/api/admin/apks', { method: 'POST', body: form });
        }
        ['apkName','apkPackage','apkVersion','apkIconUrl','apkDesc','apkUrl'].forEach(id => $('#' + id).value = '');
        $('#apkFile').value = '';
        toast('APK added — devices will see it on next poll');
        refreshAll();
    } catch (e) { toast('Failed: ' + e.message, true); }
});

function renderApks(store) {
    const tbl = $('#apkTable');
    const apks = store.apks || [];
    if (!apks.length) {
        tbl.innerHTML = `
            <thead><tr><th colspan="4">No APKs added yet. Use the form above to add the first one.</th></tr></thead>`;
        return;
    }
    tbl.innerHTML = `
        <thead>
            <tr><th>Name</th><th>Source</th><th>Added</th><th></th></tr>
        </thead>
        <tbody>
            ${apks.map(a => `
                <tr>
                    <td class="name">${escapeAttr(a.name)}<br>
                        <span style="font-size:11px;color:var(--txt-tertiary);">${escapeAttr(a.package_id || '')} ${escapeAttr(a.version_name || '')}</span>
                    </td>
                    <td class="url">${escapeAttr(a.apk_url)}</td>
                    <td>${new Date(a.added_at * 1000).toLocaleDateString()}</td>
                    <td><button class="delete" data-id="${a.id}">Remove</button></td>
                </tr>
            `).join('')}
        </tbody>`;
    tbl.querySelectorAll('button.delete').forEach(b => b.addEventListener('click', async () => {
        if (!confirm('Remove this APK from the manifest?')) return;
        try {
            await api('/api/admin/apks/' + b.dataset.id, { method: 'DELETE' });
            toast('APK removed');
            refreshAll();
        } catch (e) { toast('Failed: ' + e.message, true); }
    }));
}

/* ─────────────  Notifications  ───────────── */
$('#notifyForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const title = $('#notifyTitle').value.trim();
    const body  = $('#notifyBody').value.trim();
    const image = $('#notifyImg').value.trim();
    const ttl   = parseInt($('#notifyTtl').value, 10);
    if (!title || !body) return;
    try {
        await api('/api/admin/notify', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                title, body,
                image_url: image || null,
                ttl_seconds: ttl,
            }),
        });
        $('#notifyTitle').value = '';
        $('#notifyBody').value  = '';
        $('#notifyImg').value   = '';
        toast('Notification broadcast');
        refreshAll();
    } catch (err) { toast('Failed: ' + err.message, true); }
});

function renderNotifications(store) {
    const list = $('#notifyList');
    const ns = (store.notifications || []).filter(n => n.expires_at * 1000 > Date.now());
    if (!ns.length) {
        list.innerHTML = '<p style="color:var(--txt-tertiary);">No active notifications.</p>';
        return;
    }
    list.innerHTML = ns.map(n => `
        <li>
            <div>
                <div class="title">${escapeAttr(n.title)}</div>
                <div class="body">${escapeAttr(n.body)}</div>
                <div class="ttl">Created ${new Date(n.created_at * 1000).toLocaleString()} · Expires ${new Date(n.expires_at * 1000).toLocaleString()} · ${n.seen_by?.length || 0} device(s) seen</div>
            </div>
            <button class="delete primary" style="background:transparent;color:var(--danger);border:1px solid var(--danger);" data-id="${n.id}">Withdraw</button>
        </li>
    `).join('');
    list.querySelectorAll('button.delete').forEach(b => b.addEventListener('click', async () => {
        try {
            await api('/api/admin/notify/' + b.dataset.id, { method: 'DELETE' });
            toast('Notification withdrawn');
            refreshAll();
        } catch (e) { toast('Failed: ' + e.message, true); }
    }));
}

/* ─────────────  Util  ───────────── */
function escapeAttr(s) {
    return String(s || '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[c]);
}
