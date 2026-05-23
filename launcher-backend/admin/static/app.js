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
    renderApks(store);
    renderNotifications(store);
}

/* ─────────────  Dock  ─────────────
   Each tile now renders as a stacked card with:
     - Move ↑ / ↓ buttons (reorder, persisted server-side)
     - Tile image preview (JPEG card art) + Upload / Remove
     - Wallpaper preview (focus background) + Upload / Remove
     - Label / Sub / Target Package / Target URL / Accent inputs
   The text inputs commit on "Save text fields"; images & wallpapers
   commit instantly on upload (each has its own endpoint).            */
function renderDock(store) {
    const list = $('#dockList');
    list.innerHTML = '';
    const tiles = store.dock_tiles || [];
    tiles.forEach((t, idx) => {
        const li = document.createElement('li');
        li.dataset.key = t.key;
        li.dataset.idx = String(idx);
        const imgPreview = t.image_url
            ? `<img src="${escapeAttr(t.image_url)}" alt="">`
            : '<div class="empty">No image yet</div>';
        const wpPreview  = t.wallpaper_url
            ? `<img src="${escapeAttr(t.wallpaper_url)}" alt="">`
            : '<div class="empty">No wallpaper yet</div>';
        li.innerHTML = `
            <div class="tile-head">
                <div class="reorder">
                    <button class="reorder-btn"
                            data-act="up"
                            data-key="${escapeAttr(t.key)}"
                            ${idx === 0 ? 'disabled' : ''}
                            aria-label="Move up">↑</button>
                    <button class="reorder-btn"
                            data-act="down"
                            data-key="${escapeAttr(t.key)}"
                            ${idx === tiles.length - 1 ? 'disabled' : ''}
                            aria-label="Move down">↓</button>
                </div>
                <div class="tile-title">
                    <span class="position">${idx + 1}</span>
                    <span class="key">${escapeAttr(t.key)}</span>
                    <span class="dot" style="background:${escapeAttr(t.accent || '#38B8FF')}"></span>
                </div>
            </div>
            <div class="tile-media">
                <div class="media-slot">
                    <div class="media-label">Tile image <small>(JPEG, ~16:9)</small></div>
                    <div class="media-preview image">${imgPreview}</div>
                    <div class="media-actions">
                        <label class="uploader sm">
                            <input type="file" data-act="upload-image" data-key="${escapeAttr(t.key)}"
                                   accept="image/jpeg,image/png,image/webp" hidden>
                            <span>${t.image_url ? 'Replace' : 'Upload'}</span>
                        </label>
                        ${t.image_url ? `<button class="ghost" data-act="clear-image" data-key="${escapeAttr(t.key)}">Remove</button>` : ''}
                    </div>
                </div>
                <div class="media-slot">
                    <div class="media-label">Wallpaper <small>(JPEG, ~16:9, 1920×1080)</small></div>
                    <div class="media-preview wallpaper">${wpPreview}</div>
                    <div class="media-actions">
                        <label class="uploader sm">
                            <input type="file" data-act="upload-wallpaper" data-key="${escapeAttr(t.key)}"
                                   accept="image/jpeg,image/png,image/webp" hidden>
                            <span>${t.wallpaper_url ? 'Replace' : 'Upload'}</span>
                        </label>
                        ${t.wallpaper_url ? `<button class="ghost" data-act="clear-wallpaper" data-key="${escapeAttr(t.key)}">Remove</button>` : ''}
                    </div>
                </div>
            </div>
            <div class="fields">
                <div><label>Label</label><input data-k="label" value="${escapeAttr(t.label || '')}"></div>
                <div><label>Sub</label><input data-k="sub" value="${escapeAttr(t.sub || '')}"></div>
                <div><label>Target package</label><input data-k="target_package" value="${escapeAttr(t.target_package || '')}" placeholder="e.g. tv.onnowtv.app"></div>
                <div><label>Target URL</label><input data-k="target_url" value="${escapeAttr(t.target_url || '')}" placeholder="e.g. https://news.com"></div>
                <div><label>Accent hex</label><input data-k="accent" value="${escapeAttr(t.accent || '')}" placeholder="#2BB6FF"></div>
            </div>
        `;
        list.appendChild(li);
    });
    bindDockHandlers();
}

function bindDockHandlers() {
    /* Reorder buttons */
    $$('#dockList .reorder-btn').forEach((b) => {
        b.addEventListener('click', async () => {
            const key = b.dataset.key;
            const dir = b.dataset.act;
            const store = await api('/api/admin/store');
            const order = store.dock_tiles.map((t) => t.key);
            const i = order.indexOf(key);
            if (i < 0) return;
            const j = dir === 'up' ? i - 1 : i + 1;
            if (j < 0 || j >= order.length) return;
            [order[i], order[j]] = [order[j], order[i]];
            try {
                await api('/api/admin/dock/reorder', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ order }),
                });
                toast(`Moved ${key} ${dir}`);
                refreshAll();
            } catch (e) { toast('Reorder failed: ' + e.message, true); }
        });
    });

    /* Image / wallpaper upload */
    $$('#dockList input[type="file"]').forEach((inp) => {
        inp.addEventListener('change', async () => {
            const f = inp.files && inp.files[0];
            if (!f) return;
            const key = inp.dataset.key;
            const kind = inp.dataset.act === 'upload-image' ? 'image' : 'wallpaper';
            const form = new FormData();
            form.append('file', f);
            try {
                await api(`/api/admin/dock/${encodeURIComponent(key)}/${kind}`, {
                    method: 'POST',
                    body: form,
                });
                toast(`${kind === 'image' ? 'Tile image' : 'Wallpaper'} uploaded for ${key}`);
                refreshAll();
            } catch (e) { toast('Upload failed: ' + e.message, true); }
        });
    });

    /* Clear image / wallpaper */
    $$('#dockList button[data-act^="clear-"]').forEach((b) => {
        b.addEventListener('click', async () => {
            const key = b.dataset.key;
            const kind = b.dataset.act === 'clear-image' ? 'image' : 'wallpaper';
            if (!confirm(`Remove ${kind} for "${key}"?`)) return;
            try {
                await api(`/api/admin/dock/${encodeURIComponent(key)}/${kind}`, {
                    method: 'DELETE',
                });
                toast(`${kind} cleared for ${key}`);
                refreshAll();
            } catch (e) { toast('Failed: ' + e.message, true); }
        });
    });
}

$('#saveDock').addEventListener('click', async () => {
    /* Walk the rendered list IN ORDER and build a 6-tile payload that
       preserves each tile's key.  Image/wallpaper paths are managed
       separately by the upload endpoints — the backend's set_dock()
       merges them back in so we don't have to send them here. */
    const store = await api('/api/admin/store');
    const byKey = Object.fromEntries(store.dock_tiles.map((t) => [t.key, t]));
    const payload = $$('#dockList li').map((li) => {
        const key = li.dataset.key;
        const existing = byKey[key] || {};
        const out = { key, label: existing.label, sub: existing.sub,
                      target_package: null, target_url: null, accent: null };
        li.querySelectorAll('.fields input').forEach((i) => {
            out[i.dataset.k] = i.value.trim() || null;
        });
        return out;
    });
    try {
        await api('/api/admin/dock', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        toast('Dock text fields saved');
        refreshAll();
    } catch (e) { toast('Save failed: ' + e.message, true); }
});

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
