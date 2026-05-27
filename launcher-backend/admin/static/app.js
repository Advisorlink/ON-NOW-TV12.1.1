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
function showLogin() { $('#login').hidden = false; $('#app').hidden = true; stopDevicePolling(); }
function showApp()   { $('#login').hidden = true;  $('#app').hidden = false;  refreshAll(); startDevicePolling(); }

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
    renderLayout(store);
    renderApks(store);
    renderNotifications(store);
    refreshDevices();   // v0.4 — re-pull the connected devices panel
}

/* ─────────────  Layout Editor  ─────────────
   v1.1 — Admin-editable launcher layout settings with full
   per-element typography controls (font, size, weight, colour). */
const LAYOUT_DEFAULTS = {
    tile_width_dp: 300,
    tile_height_dp: 168,
    dock_margin_bottom_dp: -16,
    dock_margin_horizontal_dp: 20,
    featured_margin_start_dp: 48,
    featured_margin_bottom_dp: 36,
    topbar_visible: true,

    featured_show_button: true,
    featured_align: 'start',

    featured_heading_size_sp: 56,
    featured_heading_font: 'montserrat',
    featured_heading_weight: 'bold',
    featured_heading_color: '#FFFFFF',

    featured_subheading_size_sp: 22,
    featured_subheading_font: 'montserrat',
    featured_subheading_weight: 'semibold',
    featured_subheading_color: '#F0F4FA',

    featured_description_size_sp: 17,
    featured_description_font: 'montserrat',
    featured_description_weight: 'regular',
    featured_description_color: '#D8E2EF',

    featured_button_size_sp: 13,
    featured_button_font: 'montserrat',
    featured_button_weight: 'bold',
    featured_button_text_color: '#04060B',

    featured_gap_after_heading_dp: 6,
    featured_gap_after_subheading_dp: 10,
    featured_gap_after_description_dp: 22,
    featured_heading_letter_spacing: -1,
    featured_subheading_letter_spacing: 2,
    featured_description_letter_spacing: 0,
    featured_button_letter_spacing: 18,
    featured_description_line_height_pct: 140,
    featured_show_heading: true,
    featured_show_subheading: true,
    featured_show_description: true,
    featured_heading_image_url: '',
    featured_heading_image_height_dp: 80,
    // v1.8 — Group offset (nudge whole panel as one block).
    featured_group_offset_x_dp: 0,
    featured_group_offset_y_dp: 0,
};

const LAYOUT_FONTS = [
    // Sans-serif body fonts (good for headings + descriptions)
    { key: 'montserrat',       label: 'Montserrat — modern geometric sans' },
    { key: 'inter',            label: 'Inter — premium UI sans' },
    { key: 'poppins',          label: 'Poppins — friendly geometric sans' },
    { key: 'roboto',           label: 'Roboto — Android default sans' },
    { key: 'nunito',           label: 'Nunito — rounded humanist sans' },
    // Cinematic / serif
    { key: 'playfair_display', label: 'Playfair Display — cinematic serif' },
    { key: 'merriweather',     label: 'Merriweather — readable serif' },
    { key: 'dm_serif_display', label: 'DM Serif Display — high-contrast serif' },
    // Display / heavy
    { key: 'oswald',           label: 'Oswald — modern condensed display' },
    { key: 'bebas_neue',       label: 'Bebas Neue — bold all-caps display' },
    { key: 'anton',            label: 'Anton — heavy block display' },
    { key: 'russo_one',        label: 'Russo One — industrial display' },
    // Script
    { key: 'lobster',          label: 'Lobster — flowing script' },
    { key: 'pacifico',         label: 'Pacifico — casual handwriting' },
];
const LAYOUT_WEIGHTS = [
    { key: 'regular',  label: 'Regular' },
    { key: 'semibold', label: 'Semi-Bold' },
    { key: 'bold',     label: 'Bold' },
];

const LAYOUT_INT_FIELDS = [
    'tile_width_dp', 'tile_height_dp',
    'dock_margin_bottom_dp', 'dock_margin_horizontal_dp',
    'featured_margin_start_dp', 'featured_margin_bottom_dp',
    'featured_heading_size_sp', 'featured_subheading_size_sp',
    'featured_description_size_sp', 'featured_button_size_sp',
    'featured_gap_after_heading_dp', 'featured_gap_after_subheading_dp',
    'featured_gap_after_description_dp',
    'featured_heading_letter_spacing', 'featured_subheading_letter_spacing',
    'featured_description_letter_spacing', 'featured_button_letter_spacing',
    'featured_description_line_height_pct',
    'featured_heading_image_height_dp',
    // v1.8 — Group offset for the whole featured panel.
    'featured_group_offset_x_dp', 'featured_group_offset_y_dp',
];
const LAYOUT_STR_FIELDS = [
    'featured_align',
    'featured_heading_font', 'featured_heading_weight', 'featured_heading_color',
    'featured_subheading_font', 'featured_subheading_weight', 'featured_subheading_color',
    'featured_description_font', 'featured_description_weight', 'featured_description_color',
    'featured_button_font', 'featured_button_weight', 'featured_button_text_color',
    'featured_heading_image_url',
];
const LAYOUT_BOOL_FIELDS = [
    'topbar_visible', 'featured_show_button',
    'featured_show_heading', 'featured_show_subheading', 'featured_show_description',
];

/** Populate the font + weight <select>s once on first render. */
function ensureLayoutSelectsPopulated() {
    $$('.layout-font-select').forEach((sel) => {
        if (sel.children.length) return;
        LAYOUT_FONTS.forEach((f) => {
            const opt = document.createElement('option');
            opt.value = f.key; opt.textContent = f.label;
            sel.appendChild(opt);
        });
    });
    $$('.layout-weight-select').forEach((sel) => {
        if (sel.children.length) return;
        LAYOUT_WEIGHTS.forEach((w) => {
            const opt = document.createElement('option');
            opt.value = w.key; opt.textContent = w.label;
            sel.appendChild(opt);
        });
    });
}

/** Wire bidirectional sync between every colour-picker swatch
 *  (data-color-for="…") and its hex text mirror (id="…"). */
function bindLayoutColorPickers() {
    $$('input[type="color"][data-color-for]').forEach((picker) => {
        const targetId = picker.dataset.colorFor;
        const hex = document.getElementById(targetId);
        if (!hex || picker.dataset.bound) return;
        picker.dataset.bound = '1';
        picker.addEventListener('input', () => {
            hex.value = picker.value.toUpperCase();
        });
        hex.addEventListener('input', () => {
            const v = hex.value.trim();
            if (/^#[0-9a-fA-F]{6}$/.test(v)) picker.value = v;
            else if (/^#[0-9a-fA-F]{8}$/.test(v)) picker.value = '#' + v.slice(3);
        });
    });
}

function renderLayout(store) {
    ensureLayoutSelectsPopulated();
    bindLayoutColorPickers();
    bindLayoutLivePreview();
    const layout = { ...LAYOUT_DEFAULTS, ...(store.layout || {}) };
    LAYOUT_INT_FIELDS.forEach((k) => {
        const el = $('#layout_' + k);
        if (el) el.value = layout[k];
    });
    LAYOUT_STR_FIELDS.forEach((k) => {
        const el = $('#layout_' + k);
        if (el) {
            el.value = layout[k];
            // Sync the colour-picker swatch from the hex text value.
            if (k.endsWith('_color')) {
                const picker = document.querySelector(`input[type="color"][data-color-for="layout_${k}"]`);
                if (picker && /^#[0-9a-fA-F]{6}$/.test(layout[k])) picker.value = layout[k];
            }
        }
    });
    LAYOUT_BOOL_FIELDS.forEach((k) => {
        const el = $('#layout_' + k);
        if (el) el.checked = !!layout[k];
    });
    renderPreview();
}

/* ─────────── v1.2 — Live mini-preview ───────────
   Renders a small 16:9 mockup of the launcher home screen that
   re-paints whenever any layout-form input changes.  Pure CSS,
   uses the SAME Google Fonts that ship in the APK so what you
   see on screen matches the TV at delivery time. */
const PREVIEW_FONT_MAP = {
    'montserrat':       "'Montserrat', sans-serif",
    'inter':            "'Inter', sans-serif",
    'poppins':          "'Poppins', sans-serif",
    'roboto':           "'Roboto', sans-serif",
    'nunito':           "'Nunito', sans-serif",
    'playfair_display': "'Playfair Display', serif",
    'merriweather':     "'Merriweather', serif",
    'dm_serif_display': "'DM Serif Display', serif",
    'oswald':           "'Oswald', sans-serif",
    'bebas_neue':       "'Bebas Neue', sans-serif",
    'anton':            "'Anton', sans-serif",
    'russo_one':        "'Russo One', sans-serif",
    'lobster':          "'Lobster', cursive",
    'pacifico':         "'Pacifico', cursive",
};
const PREVIEW_WEIGHT_MAP = {
    'regular':  '400',
    'semibold': '600',
    'bold':     '700',
};
const PREVIEW_TILE_ACCENTS = ['#FF3D5A', '#2BB6FF', '#2EEA7A', '#FFB454'];

function bindLayoutLivePreview() {
    if (window._previewBound) return;
    window._previewBound = true;
    // Attach a single delegated `input` listener so we don't have to
    // re-wire every time renderLayout() runs.
    const form = $('#layoutForm');
    if (!form) return;
    form.addEventListener('input',  renderPreview);
    form.addEventListener('change', renderPreview);
}

function renderPreview() {
    const cfg = readLayoutForm();
    const root = $('#layoutPreview'); if (!root) return;

    // Top bar visibility.
    $('#lpTopbar').style.display = cfg.topbar_visible ? '' : 'none';

    // Panel position — scale the dp values down to the preview size
    // (preview is ~360 px wide vs 1920 px screen → ÷ ~5.3).
    const scale = 1 / 5.3;
    const panel = $('#lpPanel');
    panel.style.left   = (cfg.featured_margin_start_dp  * scale) + 'px';
    panel.style.bottom = ((cfg.featured_margin_bottom_dp + 50) * scale + 50) + 'px';

    // v1.8 — Group nudge.  Translate the whole panel as a single
    // block, without disturbing the per-element gaps inside.
    const gx = (cfg.featured_group_offset_x_dp || 0) * scale;
    const gy = (cfg.featured_group_offset_y_dp || 0) * scale;
    // Note: positive Y in the dp model = "down"; in CSS translate Y
    // also = "down", so the sign is preserved 1:1.
    panel.style.transform = `translate(${gx}px, ${gy}px)`;

    // Alignment.
    const alignMap = { start: 'flex-start', center: 'center', end: 'flex-end' };
    const textAlignMap = { start: 'left', center: 'center', end: 'right' };
    panel.style.alignItems = alignMap[cfg.featured_align] || 'flex-start';
    panel.style.textAlign  = textAlignMap[cfg.featured_align] || 'left';

    // Per-element typography.
    function applyEl(id, font, weight, size, color, letterSpacing) {
        const el = $('#' + id); if (!el) return;
        el.style.fontFamily = PREVIEW_FONT_MAP[font] || PREVIEW_FONT_MAP.montserrat;
        el.style.fontWeight = PREVIEW_WEIGHT_MAP[weight] || '400';
        // Scale sp values down for the preview so they read sensibly.
        el.style.fontSize   = (size * 0.42) + 'px';
        el.style.color      = color;
        if (letterSpacing !== undefined) {
            el.style.letterSpacing = (letterSpacing / 100) + 'em';
        }
    }
    applyEl('lpHeading',
        cfg.featured_heading_font, cfg.featured_heading_weight,
        cfg.featured_heading_size_sp, cfg.featured_heading_color,
        cfg.featured_heading_letter_spacing);
    applyEl('lpSubheading',
        cfg.featured_subheading_font, cfg.featured_subheading_weight,
        cfg.featured_subheading_size_sp, cfg.featured_subheading_color,
        cfg.featured_subheading_letter_spacing);
    applyEl('lpDescription',
        cfg.featured_description_font, cfg.featured_description_weight,
        cfg.featured_description_size_sp, cfg.featured_description_color,
        cfg.featured_description_letter_spacing);
    applyEl('lpCta',
        cfg.featured_button_font, cfg.featured_button_weight,
        cfg.featured_button_size_sp, cfg.featured_button_text_color,
        cfg.featured_button_letter_spacing);

    // v1.6 — Per-element visibility toggles + heading-as-image.
    const useHeadingImage = !!(cfg.featured_heading_image_url || '').trim();
    let lpHeadingImage = $('#lpHeadingImage');
    if (!lpHeadingImage) {
        // Inject the heading-image element ABOVE the text heading.
        const txt = $('#lpHeading');
        if (txt && txt.parentNode) {
            lpHeadingImage = document.createElement('img');
            lpHeadingImage.id = 'lpHeadingImage';
            lpHeadingImage.alt = '';
            lpHeadingImage.style.display = 'none';
            lpHeadingImage.style.objectFit = 'contain';
            lpHeadingImage.style.objectPosition = 'left';
            lpHeadingImage.style.maxWidth = '100%';
            txt.parentNode.insertBefore(lpHeadingImage, txt);
        }
    }
    if (lpHeadingImage && useHeadingImage) {
        lpHeadingImage.src = cfg.featured_heading_image_url;
        lpHeadingImage.style.display = cfg.featured_show_heading ? 'block' : 'none';
        lpHeadingImage.style.height = ((cfg.featured_heading_image_height_dp || 80) * 0.45) + 'px';
        $('#lpHeading').style.display = 'none';
    } else if (lpHeadingImage) {
        lpHeadingImage.style.display = 'none';
        $('#lpHeading').style.display = cfg.featured_show_heading ? '' : 'none';
    }
    $('#lpSubheading').style.display  = cfg.featured_show_subheading  ? '' : 'none';
    $('#lpDescription').style.display = cfg.featured_show_description ? '' : 'none';
    // Description line height.
    $('#lpDescription').style.lineHeight = (cfg.featured_description_line_height_pct / 100);
    // Vertical gaps between panel elements.
    $('#lpSubheading').style.marginTop  = (cfg.featured_gap_after_heading_dp     * 0.5) + 'px';
    $('#lpDescription').style.marginTop = (cfg.featured_gap_after_subheading_dp  * 0.5) + 'px';
    $('#lpCtaWrap').style.marginTop     = (cfg.featured_gap_after_description_dp * 0.5) + 'px';

    // CTA pill colour = first tile's accent (preview convention).
    $('#lpCta').style.background = PREVIEW_TILE_ACCENTS[0];
    // CTA show/hide toggle.
    $('#lpCtaWrap').style.display = cfg.featured_show_button ? '' : 'none';

    // Mini dock — 4 sample tiles in the configured size/colour.
    const dock = $('#lpDock');
    dock.style.bottom = (cfg.dock_margin_bottom_dp * scale) + 'px';
    dock.innerHTML = '';
    const tileW = Math.round(cfg.tile_width_dp  * scale);
    const tileH = Math.round(cfg.tile_height_dp * scale);
    PREVIEW_TILE_ACCENTS.forEach((accent, i) => {
        const t = document.createElement('div');
        t.className = 'lp-tile';
        t.style.width  = tileW + 'px';
        t.style.height = tileH + 'px';
        t.style.background = `linear-gradient(135deg, ${accent}cc, ${accent}77)`;
        if (i === 0) t.style.boxShadow = `0 0 0 2px ${accent}, 0 0 16px ${accent}66`;
        dock.appendChild(t);
    });
}

function readLayoutForm() {
    const out = {};
    LAYOUT_INT_FIELDS.forEach((k) => {
        const el = $('#layout_' + k);
        const n  = parseInt(el ? el.value : '', 10);
        out[k] = Number.isFinite(n) ? n : LAYOUT_DEFAULTS[k];
    });
    LAYOUT_STR_FIELDS.forEach((k) => {
        const el = $('#layout_' + k);
        const v  = (el && el.value || '').trim();
        // Heading image URL: empty string is valid (means "no image").
        if (k === 'featured_heading_image_url') {
            out[k] = v;
        } else {
            out[k] = v || LAYOUT_DEFAULTS[k];
        }
    });
    LAYOUT_BOOL_FIELDS.forEach((k) => {
        out[k] = !!$('#layout_' + k)?.checked;
    });
    return out;
}

const _saveLayoutBtn = $('#saveLayout');
if (_saveLayoutBtn) {
    _saveLayoutBtn.addEventListener('click', async () => {
        const payload = readLayoutForm();
        try {
            await api('/api/admin/layout', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            toast('Layout saved — devices update within ~30 s');
        } catch (e) {
            toast('Save failed: ' + e.message, true);
        }
    });
}
const _resetLayoutBtn = $('#resetLayout');
if (_resetLayoutBtn) {
    _resetLayoutBtn.addEventListener('click', async () => {
        if (!window.confirm('Reset layout to factory defaults?')) return;
        try {
            await api('/api/admin/layout', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(LAYOUT_DEFAULTS),
            });
            renderLayout({ layout: LAYOUT_DEFAULTS });
            toast('Layout reset to defaults');
        } catch (e) {
            toast('Reset failed: ' + e.message, true);
        }
    });
}

/* ─────────────  Connected devices panel  ─────────────
   v0.4 — Confirmation loop for admins.  Polls /api/admin/devices
   every 5s, shows online count + per-device sync status, and powers
   the "Republish to devices" button.                                   */
let _devicePollHandle = null;
async function refreshDevices() {
    try {
        const d = await api('/api/admin/devices');
        renderDevices(d);
    } catch (e) {
        const el = $('#deviceSummary');
        if (el) el.textContent = 'Status unavailable (' + e.message + ')';
    }
}
function startDevicePolling() {
    if (_devicePollHandle) clearInterval(_devicePollHandle);
    refreshDevices();
    refreshRegisteredDevices();
    _devicePollHandle = setInterval(() => {
        refreshDevices();
        refreshRegisteredDevices();
    }, 5000);
}
function stopDevicePolling() {
    if (_devicePollHandle) clearInterval(_devicePollHandle);
    _devicePollHandle = null;
}
function renderDevices(d) {
    const summary = $('#deviceSummary');
    const list = $('#deviceList');
    if (!summary || !list) return;
    const devs = d.devices || [];
    const online = devs.filter((x) => x.online).length;
    const synced = devs.filter((x) => x.online && x.in_sync).length;
    if (devs.length === 0) {
        summary.innerHTML =
            '<span style="color:var(--txt-tertiary);">' +
            'No devices have polled yet.  After you install the launcher APK, the box ' +
            'will appear here within 30 seconds.</span>';
        list.innerHTML = '';
        return;
    }
    const allSynced = synced === online && online > 0;
    summary.innerHTML =
        `<span class="status-dot ${allSynced ? 'ok' : 'warn'}"></span>` +
        `<strong>${online}</strong> online · ` +
        `<strong>${synced}/${online}</strong> in sync · ` +
        `<span class="muted">latest config: gen ${d.current_generation}</span>`;
    list.innerHTML = '';
    devs.forEach((dev) => {
        const li = document.createElement('li');
        li.className = 'device-row ' + (dev.online ? 'online' : 'offline');
        const age = formatAge(dev.last_seen_age_seconds);
        const inSyncBadge = dev.online
            ? (dev.in_sync
                ? '<span class="dev-badge ok">In sync</span>'
                : '<span class="dev-badge warn">Behind by ' + (d.current_generation - dev.last_generation) + ' gen</span>')
            : '<span class="dev-badge offline">Offline</span>';
        li.innerHTML = `
            <span class="dev-id" title="${escapeAttr(dev.device_id)}">${escapeAttr((dev.device_id || '').slice(0, 12))}…</span>
            <span class="dev-gen">gen ${dev.last_generation}</span>
            <span class="dev-age">${age}</span>
            ${inSyncBadge}
        `;
        list.appendChild(li);
    });
}
function formatAge(s) {
    if (s < 60) return s + 's ago';
    if (s < 3600) return Math.floor(s / 60) + 'm ago';
    if (s < 86400) return Math.floor(s / 3600) + 'h ago';
    return Math.floor(s / 86400) + 'd ago';
}

/* ─────────────  v1.7 — Registered devices (activation gate)  ─────────────
   v1.9 — Moved off the Dock tab into its own "Devices" tab.
   Cards laid out in a responsive grid + a search box that filters
   on name / box model / status.  Block / unblock / delete per card. */
let _registeredDevicesCache = [];

async function refreshRegisteredDevices() {
    try {
        const d = await api('/api/admin/registered-devices');
        _registeredDevicesCache = d.devices || [];
        renderRegisteredDevices(_registeredDevicesCache);
    } catch (e) {
        const grid = $('#devicesGrid');
        if (grid) {
            grid.innerHTML = `<div style="color:var(--danger); padding:32px;">
                Registered devices unavailable: ${escapeAttr(e.message)}
            </div>`;
        }
    }
}

function renderRegisteredDevices(devs) {
    const grid    = $('#devicesGrid');
    const empty   = $('#devicesEmpty');
    const stats   = $('#devicesStats');
    if (!grid) return;
    if (!devs.length) {
        grid.innerHTML = '';
        if (empty) empty.hidden = false;
        if (stats) stats.textContent = '0 DEVICES';
        return;
    }
    if (empty) empty.hidden = true;
    const pending = devs.filter((x) => x.status === 'pending').length;
    const active  = devs.filter((x) => x.status === 'active').length;
    const blocked = devs.filter((x) => x.status === 'blocked').length;
    if (stats) {
        stats.innerHTML =
            `<span style="color:var(--success);">${active} ACTIVE</span>` +
            ` · <span style="color:#FFBD2E;">${pending} PENDING</span>` +
            ` · <span style="color:var(--danger);">${blocked} BLOCKED</span>`;
    }
    grid.innerHTML = devs.map(deviceCardHtml).join('');
    grid.querySelectorAll('button[data-act]').forEach((btn) => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            const id = btn.dataset.id;
            const act = btn.dataset.act;
            try {
                if (act === 'delete') {
                    if (!window.confirm('Permanently delete this device record?')) return;
                    await api('/api/admin/registered-devices/' + encodeURIComponent(id), {
                        method: 'DELETE',
                    });
                    toast('Device deleted');
                } else {
                    const status = act === 'approve' || act === 'unblock' ? 'active' : 'blocked';
                    await api('/api/admin/registered-devices/' + encodeURIComponent(id) + '/status', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ status }),
                    });
                    toast('Status set to ' + status);
                }
                await refreshRegisteredDevices();
                applyDeviceFilter();
            } catch (err) {
                toast('Action failed: ' + err.message, true);
            }
        });
    });
}

function deviceCardHtml(dev) {
    const initial = (dev.name || '?').trim().charAt(0).toUpperCase() || '?';
    // registered_at / last_seen_at are stored as Unix epoch SECONDS
    // (see _save_store in main.py) — multiply by 1000 for the JS
    // Date ctor, which expects milliseconds.
    const when = dev.registered_at
        ? new Date(dev.registered_at * 1000).toLocaleString()
        : '—';
    const lastSeen = dev.last_seen_at
        ? new Date(dev.last_seen_at * 1000).toLocaleString()
        : '—';
    const status = dev.status || 'pending';
    // v1.9 — Show the FULL device id verbatim (user request: "please
    // add all of the device IDs as well").  Long 32-char uppercase
    // legacy IDs and shorter modern UUIDs both render in a
    // monospaced, slightly wrapped row.
    const fullId = dev.id || '';
    // NOT HK1 chip — shown for any legacy import whose model isn't
    // exactly "Amlogic HK1 BOX S905X3", so the admin can spot edge
    // devices at a glance.  Doesn't change behaviour, purely visual.
    const notHk1Pill = dev.not_hk1
        ? `<span class="device-card-pill warn">NOT HK1</span>`
        : '';
    const actions = [
        status !== 'active'  && `<button class="primary" data-act="approve" data-id="${escapeAttr(dev.id)}">Approve</button>`,
        status !== 'blocked' && `<button data-act="block" data-id="${escapeAttr(dev.id)}">Block</button>`,
        status === 'blocked' && `<button data-act="unblock" data-id="${escapeAttr(dev.id)}">Unblock</button>`,
        `<button class="danger" data-act="delete" data-id="${escapeAttr(dev.id)}">Delete</button>`,
    ].filter(Boolean).join('');
    return `
        <div class="device-card" data-device-status="${escapeAttr(status)}">
            <div class="device-card-top">
                <div class="device-card-avatar">${escapeAttr(initial)}</div>
                <div style="min-width:0; flex:1;">
                    <div class="device-card-name">${escapeAttr(dev.name || '(no name)')}</div>
                    <div class="device-card-id-full">${escapeAttr(fullId)}</div>
                </div>
                <span class="device-card-status ${escapeAttr(status)}">${escapeAttr(status)}</span>
            </div>
            ${notHk1Pill ? `<div class="device-card-pills">${notHk1Pill}</div>` : ''}
            <div class="device-card-meta">
                <div><strong>Model</strong> · ${escapeAttr(dev.model || 'Unknown')}</div>
                <div><strong>Registered</strong> · ${escapeAttr(when)}</div>
                <div><strong>Last seen</strong> · ${escapeAttr(lastSeen)}</div>
            </div>
            <div class="device-card-actions">${actions}</div>
        </div>`;
}

/* Live search filter on the Devices tab. */
function applyDeviceFilter() {
    const q = ($('#devicesSearch')?.value || '').trim().toLowerCase();
    if (!q) return renderRegisteredDevices(_registeredDevicesCache);
    const filtered = _registeredDevicesCache.filter((d) => {
        // v1.9 — Match against name + model + status + full id (not
        // just first 8 chars), so admins can paste a complete legacy
        // device id from their old roster and find the row instantly.
        const hay = [d.name, d.model, d.status, d.id]
            .filter(Boolean).join(' ').toLowerCase();
        return hay.includes(q);
    });
    renderRegisteredDevices(filtered);
}
$('#devicesSearch')?.addEventListener('input', applyDeviceFilter);
const _republishBtn = $('#republishBtn');
if (_republishBtn) {
    _republishBtn.addEventListener('click', async () => {
        _republishBtn.disabled = true;
        const original = _republishBtn.textContent;
        _republishBtn.textContent = 'Republishing…';
        try {
            const r = await api('/api/admin/republish', { method: 'POST' });
            toast(r.message || 'Republished to devices');
            refreshDevices();
        } catch (e) {
            toast('Republish failed: ' + e.message, true);
        } finally {
            _republishBtn.disabled = false;
            _republishBtn.textContent = original;
        }
    });
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
        const apkPreview = t.apk_url
            ? `<div class="apk-card">
                   <div class="apk-icon">APK</div>
                   <div class="apk-meta">
                       <div class="apk-name" title="${escapeAttr(t.apk_filename || '')}">${escapeAttr(t.apk_filename || '(uploaded)')}</div>
                       <div class="apk-sub">
                           <span>${escapeAttr(t.apk_package_id || '— no package id —')}</span>
                           <span class="dot-sep">·</span>
                           <span>v${escapeAttr(t.apk_version || '?')}</span>
                       </div>
                   </div>
               </div>`
            : '<div class="empty">No APK yet</div>';
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
                <button class="tile-delete-btn"
                        data-act="delete-tile"
                        data-key="${escapeAttr(t.key)}"
                        ${tiles.length <= 1 ? 'disabled' : ''}
                        title="Remove this tile from the dock"
                        aria-label="Delete tile">×</button>
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
            <div class="tile-apk-row">
                <div class="media-label">APK <small>(installed + launched when this tile is tapped — leave blank to just open <code>target package</code>)</small></div>
                <div class="apk-preview ${t.apk_url ? 'has' : 'empty-state'}">${apkPreview}</div>
                <div class="media-actions">
                    <label class="uploader sm">
                        <input type="file" data-act="upload-apk" data-key="${escapeAttr(t.key)}"
                               accept=".apk,application/vnd.android.package-archive" hidden>
                        <span>${t.apk_url ? 'Replace APK' : 'Upload APK'}</span>
                    </label>
                    ${t.apk_url ? `<button class="ghost" data-act="clear-apk" data-key="${escapeAttr(t.key)}">Remove APK</button>` : ''}
                </div>
            </div>
            <div class="fields">
                <div><label>Label</label><input data-k="label" value="${escapeAttr(t.label || '')}"></div>
                <div><label>Sub</label><input data-k="sub" value="${escapeAttr(t.sub || '')}"></div>
                <div class="span-2"><label>Wallpaper heading <small>(big Montserrat title shown over the wallpaper)</small></label><input data-k="heading" value="${escapeAttr(t.heading || '')}" placeholder="e.g. Movies"></div>
                <div class="span-2"><label>Wallpaper subheading <small>(mid-size accent line between heading and description)</small></label><input data-k="subheading" value="${escapeAttr(t.subheading || '')}" placeholder="e.g. On demand · 4K HDR"></div>
                <div class="span-2"><label>Wallpaper description <small>(supporting line under the heading)</small></label><input data-k="description" value="${escapeAttr(t.description || '')}" placeholder="e.g. Stream the latest blockbusters in 4K HDR."></div>
                <div><label>CTA label <small>(button text — defaults to "ENTER")</small></label><input data-k="cta_label" value="${escapeAttr(t.cta_label || '')}" placeholder="ENTER"></div>
                <div><label>Target package</label><input data-k="target_package" value="${escapeAttr(t.target_package || '')}" placeholder="e.g. tv.onnowtv.app"></div>
                <div><label>Target URL</label><input data-k="target_url" value="${escapeAttr(t.target_url || '')}" placeholder="e.g. https://news.com"></div>
                <div><label>APK package id <small>(metadata)</small></label><input data-k="apk_package_id" value="${escapeAttr(t.apk_package_id || '')}" placeholder="e.g. tv.onnowtv.app"></div>
                <div><label>APK version <small>(metadata)</small></label><input data-k="apk_version" value="${escapeAttr(t.apk_version || '')}" placeholder="e.g. 2.7.85"></div>
                <div class="accent-field">
                    <label>Glow colour</label>
                    <div class="accent-pickers">
                        <input type="color"
                               data-k="accent"
                               data-accent-color
                               value="${escapeAttr(t.accent || '#2BB6FF')}"
                               title="Pick the glow colour for this tile">
                        <input type="text"
                               data-accent-hex
                               value="${escapeAttr(t.accent || '')}"
                               placeholder="#2BB6FF"
                               maxlength="9"
                               spellcheck="false">
                    </div>
                </div>
            </div>
        `;
        list.appendChild(li);
    });
    bindDockHandlers();
}

function bindDockHandlers() {
    /* v0.8 — Bidirectional sync between the colour picker (the
       saved field) and the hex text mirror.  Lets non-technical
       users pick visually, AND power users paste an exact hex. */
    $$('#dockList .accent-field').forEach((wrap) => {
        const picker = wrap.querySelector('[data-accent-color]');
        const hex    = wrap.querySelector('[data-accent-hex]');
        if (!picker || !hex) return;
        picker.addEventListener('input', () => {
            hex.value = picker.value.toUpperCase();
        });
        hex.addEventListener('input', () => {
            const v = hex.value.trim();
            // Accept #RRGGBB.  type=color only handles 6-digit hex,
            // so strip a leading alpha byte if the user typed one.
            if (/^#[0-9a-fA-F]{6}$/.test(v)) {
                picker.value = v;
            } else if (/^#[0-9a-fA-F]{8}$/.test(v)) {
                picker.value = '#' + v.slice(3);
            }
        });
    });

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

    /* Image / wallpaper / APK upload */
    $$('#dockList input[type="file"]').forEach((inp) => {
        inp.addEventListener('change', async () => {
            const f = inp.files && inp.files[0];
            if (!f) return;
            const key = inp.dataset.key;
            const act = inp.dataset.act;        // upload-image | upload-wallpaper | upload-apk
            const kind = act === 'upload-image' ? 'image'
                       : act === 'upload-wallpaper' ? 'wallpaper'
                       : 'apk';
            const form = new FormData();
            form.append('file', f);
            // For APK uploads, also send any metadata the admin has
            // already typed into the field row.  The endpoint persists
            // them on the tile so the launcher can do version checks.
            if (kind === 'apk') {
                const li = inp.closest('li');
                const pkgInput = li.querySelector('input[data-k="apk_package_id"]');
                const verInput = li.querySelector('input[data-k="apk_version"]');
                if (pkgInput && pkgInput.value.trim()) form.append('apk_package_id', pkgInput.value.trim());
                if (verInput && verInput.value.trim()) form.append('apk_version',    verInput.value.trim());
            }
            try {
                await api(`/api/admin/dock/${encodeURIComponent(key)}/${kind}`, {
                    method: 'POST',
                    body: form,
                });
                const friendly = kind === 'image' ? 'Tile image'
                              : kind === 'wallpaper' ? 'Wallpaper'
                              : 'APK';
                toast(`${friendly} uploaded for ${key}`);
                refreshAll();
            } catch (e) { toast('Upload failed: ' + e.message, true); }
        });
    });

    /* Clear image / wallpaper / APK */
    $$('#dockList button[data-act^="clear-"]').forEach((b) => {
        b.addEventListener('click', async () => {
            const key = b.dataset.key;
            const act = b.dataset.act;          // clear-image | clear-wallpaper | clear-apk
            const kind = act === 'clear-image' ? 'image'
                       : act === 'clear-wallpaper' ? 'wallpaper'
                       : 'apk';
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

    /* v0.5 — Delete an entire tile */
    $$('#dockList button[data-act="delete-tile"]').forEach((b) => {
        b.addEventListener('click', async () => {
            const key = b.dataset.key;
            if (!confirm(
                `Remove the "${key}" tile entirely?  Its uploaded image, wallpaper, and APK will also be deleted.  This cannot be undone.`
            )) return;
            try {
                await api(`/api/admin/dock/${encodeURIComponent(key)}`, {
                    method: 'DELETE',
                });
                toast(`Tile "${key}" removed`);
                refreshAll();
            } catch (e) { toast('Delete failed: ' + e.message, true); }
        });
    });
}

/* v0.5 — "Add tile" button.  Prompts for a label, creates an empty
   tile at the end of the dock, refreshes the list. */
const _addTileBtn = $('#addTile');
if (_addTileBtn) {
    _addTileBtn.addEventListener('click', async () => {
        const label = window.prompt(
            'Label for the new tile (e.g. "YouTube", "Spotify", "News"):',
            ''
        );
        if (!label || !label.trim()) return;
        try {
            await api('/api/admin/dock/add', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ label: label.trim() }),
            });
            toast(`Tile "${label.trim()}" added`);
            refreshAll();
        } catch (e) {
            toast('Add failed: ' + e.message, true);
        }
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
                      target_package: null, target_url: null, accent: null,
                      heading: null, subheading: null, description: null, cta_label: null };
        li.querySelectorAll('.fields input').forEach((i) => {
            // Skip inputs without a `data-k` mapping (e.g. the
            // text-mirror next to the colour picker — only the
            // colour picker itself carries data-k="accent").
            if (!i.dataset.k) return;
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

/* ─────────────  APKs / App Store — v1.9 redesign  ─────────────
   The legacy URL-based form + table is gone.  In its place:
     • A drag-and-drop dropzone that POSTs the APK to
       /api/admin/apks/upload, where pyaxmlparser extracts
       package id, version, app name and icon.
     • A 3-column grid of tiles (matches the launcher's App Store).
     • A slide-in edit drawer that lets the admin rename, edit
       description / version, swap icon, or delete.
*/
let _activeStoreId = null;

/* —— Drag-drop wiring (drop or click → browse) —— */
(function setupAppStoreDropzone() {
    const zone   = $('#apkDropZone');
    const input  = $('#apkDropFile');
    const browse = $('#apkDropBrowse');
    const prog   = $('#apkDropProgress');
    if (!zone || !input) return;

    zone.addEventListener('click', (e) => {
        if (e.target === browse) return;
        input.click();
    });
    browse.addEventListener('click', (e) => {
        e.stopPropagation();
        input.click();
    });
    ['dragenter', 'dragover'].forEach((ev) =>
        zone.addEventListener(ev, (e) => {
            e.preventDefault();
            zone.classList.add('drag-over');
        }),
    );
    ['dragleave', 'drop'].forEach((ev) =>
        zone.addEventListener(ev, (e) => {
            e.preventDefault();
            zone.classList.remove('drag-over');
        }),
    );
    zone.addEventListener('drop', (e) => {
        const files = Array.from(e.dataTransfer?.files || [])
            .filter((f) => /\.apk$/i.test(f.name));
        if (files.length) uploadApks(files);
    });
    input.addEventListener('change', () => {
        const files = Array.from(input.files || []);
        if (files.length) uploadApks(files);
        input.value = '';
    });

    async function uploadApks(files) {
        for (let i = 0; i < files.length; i++) {
            const f = files[i];
            prog.hidden = false;
            prog.textContent = `Uploading ${i + 1} / ${files.length} — ${f.name}…`;
            try {
                const form = new FormData();
                form.append('file', f);
                // Name omitted on purpose — backend will use the
                // extracted app label.  Admin can rename later.
                const r = await api('/api/admin/apks/upload', {
                    method: 'POST', body: form,
                });
                prog.textContent =
                    `✓ ${r.apk?.name || f.name} added — ` +
                    `${r.auto_detected?.package_id || 'no package id'}`;
                toast(`${r.apk?.name || 'App'} added`);
            } catch (e) {
                prog.textContent = `✗ ${f.name}: ${e.message}`;
                toast(`Upload failed: ${e.message}`, true);
            }
        }
        await refreshAll();
        setTimeout(() => { prog.hidden = true; }, 4500);
    }
})();

function renderApks(store) {
    const grid  = $('#appStoreGrid');
    const empty = $('#appStoreEmpty');
    const apks  = store.apks || [];
    if (!grid) return;
    if (!apks.length) {
        grid.innerHTML = '';
        if (empty) empty.hidden = false;
        return;
    }
    if (empty) empty.hidden = true;
    grid.innerHTML = apks.map(appStoreTileHtml).join('');
    grid.querySelectorAll('[data-store-tile]').forEach((el) => {
        el.addEventListener('click', () => openStoreDrawer(el.dataset.storeTile));
    });
}

function appStoreTileHtml(a) {
    const initial = (a.name || '?').trim().charAt(0).toUpperCase() || '?';
    const iconHtml = a.icon_url
        ? `<img src="${escapeAttr(a.icon_url)}" alt="${escapeAttr(a.name)}" loading="lazy">`
        : `<span class="appstore-icon-fallback">${escapeAttr(initial)}</span>`;
    const versionPill = a.version_name
        ? `<div class="appstore-version">v${escapeAttr(a.version_name)}</div>`
        : '';
    const pkgLine = a.package_id
        ? `<div class="appstore-package">${escapeAttr(a.package_id)}</div>`
        : '';
    return `
        <div class="appstore-tile" data-store-tile="${escapeAttr(a.id)}"
             role="button" tabindex="0">
            <div class="appstore-icon-wrap">${iconHtml}</div>
            <div class="appstore-name">${escapeAttr(a.name || '(no name)')}</div>
            ${versionPill}
            ${pkgLine}
        </div>`;
}

/* —— Edit drawer —— */
function openStoreDrawer(id) {
    _activeStoreId = id;
    api('/api/admin/store').then((store) => {
        const apk = (store.apks || []).find((a) => a.id === id);
        if (!apk) return;
        $('#drawerName').value        = apk.name        || '';
        $('#drawerVersion').value     = apk.version_name || '';
        $('#drawerPackage').value     = apk.package_id   || '(unknown)';
        $('#drawerDescription').value = apk.description || '';
        const img = $('#drawerIcon');
        if (apk.icon_url) {
            img.src = apk.icon_url;
            img.alt = apk.name || '';
            img.style.visibility = 'visible';
        } else {
            img.removeAttribute('src');
            img.alt = '';
        }
        $('#appStoreDrawer').hidden = false;
    });
}
function closeStoreDrawer() {
    _activeStoreId = null;
    $('#appStoreDrawer').hidden = true;
}
$('#appStoreDrawerClose')?.addEventListener('click', closeStoreDrawer);
$('#appStoreDrawerBackdrop')?.addEventListener('click', closeStoreDrawer);
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('#appStoreDrawer')?.hidden) closeStoreDrawer();
});
$('#drawerSave')?.addEventListener('click', async () => {
    if (!_activeStoreId) return;
    try {
        await api('/api/admin/apks/' + encodeURIComponent(_activeStoreId), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name:         $('#drawerName').value.trim()        || null,
                version_name: $('#drawerVersion').value.trim()     || null,
                description:  $('#drawerDescription').value.trim() || null,
            }),
        });
        toast('Saved');
        closeStoreDrawer();
        refreshAll();
    } catch (e) { toast('Save failed: ' + e.message, true); }
});
$('#drawerDelete')?.addEventListener('click', async () => {
    if (!_activeStoreId) return;
    if (!confirm('Delete this app from the store?')) return;
    try {
        await api('/api/admin/apks/' + encodeURIComponent(_activeStoreId), {
            method: 'DELETE',
        });
        toast('Deleted');
        closeStoreDrawer();
        refreshAll();
    } catch (e) { toast('Delete failed: ' + e.message, true); }
});
$('#drawerIconFile')?.addEventListener('change', async (e) => {
    if (!_activeStoreId) return;
    const f = e.target.files?.[0];
    if (!f) return;
    try {
        const form = new FormData();
        form.append('file', f);
        const r = await api('/api/admin/apks/' + encodeURIComponent(_activeStoreId) + '/icon', {
            method: 'POST', body: form,
        });
        // Bust cache so the new icon shows immediately.
        $('#drawerIcon').src = r.icon_url + '?ts=' + Date.now();
        toast('Icon updated');
        refreshAll();
    } catch (err) { toast('Icon upload failed: ' + err.message, true); }
    e.target.value = '';
});

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
