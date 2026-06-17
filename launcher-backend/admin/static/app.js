/* ============================================================
   OnNow TV V2 — Launcher Admin client
   ──────────────────────────────────────────────────────────── */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

/* v2.8.40 — When the admin UI is served behind a reverse proxy at
 * a sub-path (e.g. https://onnowtv.duckdns.org/launcher/admin), all
 * absolute `/api/...` requests would 404.  Detect the prefix from
 * the current page URL once at startup and prepend it to every
 * call.  In local dev (where the page lives at /admin) the prefix
 * is "" — backwards compatible. */
const API_BASE = (() => {
    const p = window.location.pathname;
    const m = p.match(/^(.*?)\/admin(?:\/|$)/);
    return m && m[1] ? m[1] : '';
})();

function _abs(path) {
    if (!path || path.startsWith('http') || !path.startsWith('/')) return path;
    // v2.10.48 — Avoid double-prefixing.  The reverse-proxy rewriter
    // already rewrites `/api/admin/...` → `/api/launcher-admin/api/admin/...`
    // before the JS reaches the browser; without this guard we'd
    // then prepend API_BASE again and end up with
    // `/api/launcher-admin/api/launcher-admin/api/admin/...` which 404s.
    if (API_BASE && path.startsWith(API_BASE + '/')) return path;
    return API_BASE + path;
}

function toast(msg, isError = false) {
    const el = $('#toast');
    if (!el) { console.log(msg); return; }
    el.textContent = msg;
    el.classList.toggle('err', isError);
    el.hidden = false;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { el.hidden = true; }, 3000);
}

/* v2.10.58 — Persistent (non-auto-dismissing) toast for in-flight
   operations like APK uploads.  Returns a tiny controller object
   with `update(msg)` / `success(msg)` / `error(msg)` / `close()` so
   the caller can stream progress into a single toast bubble.

   The old auto-toast above stays exactly as it was; admin code that
   wants to keep using one-shot toasts won't notice. */
function persistentToast(initialMsg) {
    const el = $('#toast');
    if (!el) {
        console.log('persistentToast:', initialMsg);
        return { update: console.log, success: console.log, error: console.warn, close: () => {} };
    }
    clearTimeout(toast._t);
    el.textContent = initialMsg;
    el.classList.remove('err');
    el.classList.add('progress');
    el.hidden = false;
    return {
        update(msg) {
            el.textContent = msg;
            el.classList.remove('err');
            el.classList.add('progress');
            el.hidden = false;
        },
        success(msg) {
            el.classList.remove('progress', 'err');
            el.textContent = msg;
            el.hidden = false;
            clearTimeout(toast._t);
            toast._t = setTimeout(() => { el.hidden = true; }, 3000);
        },
        error(msg) {
            el.classList.remove('progress');
            el.classList.add('err');
            el.textContent = msg;
            el.hidden = false;
            clearTimeout(toast._t);
            toast._t = setTimeout(() => { el.hidden = true; }, 6000);
        },
        close() {
            el.classList.remove('progress', 'err');
            el.hidden = true;
        },
    };
}

/* v2.10.58 — XMLHttpRequest-based upload wrapper that reports
   real-time progress.  We can't use `fetch(...)` for this because
   browsers don't expose upload progress on the fetch ReadableStream
   side until Chrome ≥ 105 with experimental flags.  An XHR is the
   portable answer.

   Returns the parsed JSON response on 2xx, throws on error/timeout.
   `onProgress({ loaded, total, percent })` is called every ~250 ms
   during the upload. */
function uploadWithProgress(url, formData, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', url, true);
        xhr.withCredentials = true;          // mirror fetch's same-origin cookies
        xhr.responseType = 'text';

        xhr.upload.onprogress = (e) => {
            if (!e.lengthComputable) return;
            const percent = (e.loaded / e.total) * 100;
            try { onProgress({ loaded: e.loaded, total: e.total, percent }); } catch (_) {}
        };
        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                try { resolve(xhr.responseText ? JSON.parse(xhr.responseText) : {}); }
                catch (e) { resolve({}); }
            } else {
                let msg = `HTTP ${xhr.status}`;
                try { msg = JSON.parse(xhr.responseText).detail || msg; } catch (_) { msg = xhr.responseText || msg; }
                reject(new Error(msg));
            }
        };
        xhr.onerror = () => reject(new Error('Network error during upload — check your connection.'));
        xhr.ontimeout = () => reject(new Error('Upload timed out.'));
        xhr.timeout = 10 * 60 * 1000;  // 10 minute hard cap

        xhr.send(formData);
    });
}

function fmtMB(bytes) {
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

async function api(path, opts = {}) {
    const r = await fetch(_abs(path), { credentials: 'same-origin', ...opts });
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

/* ─────────────  Auth (v2.8.126 — disabled, single-operator mode) ───── */
function showLogin() { /* no-op — auth is off */ showApp(); }
function showApp()   { $('#login').hidden = true;  $('#app').hidden = false;  refreshAll(); startDevicePolling(); }

$('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    // Auth is off — just enter the app.
    showApp();
});

$('#logout').addEventListener('click', async () => {
    // Logout is a visual reset only while auth is disabled.
    try { await api('/api/admin/logout', { method: 'POST' }); } catch {}
    showApp();
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
    renderQrVideos();
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
    featured_heading_image_placement: 'above',
    featured_heading_image_offset_x_dp: 0,
    featured_heading_image_offset_y_dp: 0,
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
    'featured_heading_image_offset_x_dp', 'featured_heading_image_offset_y_dp',
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
    'featured_heading_image_placement',
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
    // v2.8.50 — Mirror the heading-image preview tile.
    syncAppstoreImg(
        '#layoutHeadingImg', '#layoutHeadingImgPreview', '#layoutHeadingImgClear',
        layout.featured_heading_image_url,
    );
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
                           ${t.apk_version_code != null ? `<span class="dot-sep">·</span><span>code ${escapeAttr(t.apk_version_code)}</span>` : ''}
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
                <div class="span-2"><label>Update popup — body text <small>(shown on the "Update available" popup. Leave blank for the default copy.)</small></label><input data-k="update_popup_text" value="${escapeAttr(t.update_popup_text || '')}" placeholder="A newer version of this app is ready to install…"></div>
                <div class="span-2"><label>Update popup — secondary button text <small>(extra button next to "Update now". Leave blank to HIDE the button entirely on this tile.)</small></label><input data-k="update_button_text" value="${escapeAttr(t.update_button_text || '')}" placeholder="e.g. Backup my profiles first"></div>
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

    /* Image / wallpaper / APK upload — with real-time progress
       reporting (v2.10.58).  Large APK uploads (60-100 MB on
       residential connections) used to give NO visible feedback
       for 60-180 s while the fetch was in-flight, so admins kept
       thinking the upload was broken and abandoned it.  The new
       flow uses an XHR so we can stream `xhr.upload.onprogress`
       into a persistent toast that shows "Uploading… 23.4 MB /
       58.0 MB · 40 %". */
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
            if (kind === 'apk') {
                const li = inp.closest('li');
                const pkgInput = li.querySelector('input[data-k="apk_package_id"]');
                const verInput = li.querySelector('input[data-k="apk_version"]');
                if (pkgInput && pkgInput.value.trim()) form.append('apk_package_id', pkgInput.value.trim());
                if (verInput && verInput.value.trim()) form.append('apk_version',    verInput.value.trim());
            }
            const friendly = kind === 'image' ? 'image'
                          : kind === 'wallpaper' ? 'wallpaper'
                          : 'APK';
            const tt = persistentToast(`Uploading ${friendly} for "${key}" — ${fmtMB(f.size)}`);
            // Disable the input while in-flight so the user can't
            // double-fire by picking another file.
            inp.disabled = true;
            try {
                await uploadWithProgress(
                    _abs(`/api/admin/dock/${encodeURIComponent(key)}/${kind}`),
                    form,
                    ({ loaded, total, percent }) => {
                        tt.update(
                            `Uploading ${friendly} for "${key}" — ${fmtMB(loaded)} / ${fmtMB(total)} · ${percent.toFixed(0)} %`,
                        );
                    },
                );
                tt.success(`${friendly[0].toUpperCase()}${friendly.slice(1)} uploaded for "${key}"`);
                refreshAll();
            } catch (e) {
                tt.error('Upload failed: ' + (e.message || 'unknown error'));
            } finally {
                inp.disabled = false;
                // Reset the file input so picking the same file again re-fires `change`.
                inp.value = '';
            }
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
    // v2.8.16 — Only the fullscreen background remains as the
    // admin's customisable App Store image.  Hero banner was
    // removed per user request.
    const meta = store.appstore || {};
    syncAppstoreImg(
        '#appstoreBgImg', '#appstoreBgPreview', '#appstoreBgClear',
        meta.background_image_url,
    );
    // v2.8.20 — Mirror the admin-uploadable topbar logo.
    syncAppstoreImg(
        '#appstoreLogoImg', '#appstoreLogoPreview', '#appstoreLogoClear',
        meta.logo_image_url,
    );
    // v2.8.18 — Mirror current tile colors into the color editor.
    syncTileColorInputs(store);
    // v2.8.20 — Mirror topbar pill colors.
    syncTopbarColorInputs(store);
    // v2.8.22 — Mirror Speed Test target package.
    syncSpeedTestTarget(store);
    // v2.8.25 — Mirror V2 AI heading + background preview.
    syncV2AIInputs(store);
    // v2.8.38 — Mirror V2 AI pill-specific color inputs.
    syncV2AIBtnColorInputs(store);
    // v2.8.49 — Mirror V2 AI hero-pill size inputs.
    syncV2AIBtnSizeInputs(store);
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

/** v2.8.10 — Shared "render current image OR show placeholder"
 *  helper.  Used by both the hero banner and the fullscreen
 *  background previews. */
function syncAppstoreImg(imgSel, previewSel, clearSel, url) {
    const img   = $(imgSel);
    const ph    = $(previewSel)?.querySelector('.appstore-hero-placeholder');
    const clear = $(clearSel);
    if (!img) return;
    if (url) {
        img.src = url + (url.includes('?') ? '&' : '?') + '_v=' + Date.now();
        img.hidden = false;
        if (ph) ph.style.display = 'none';
        if (clear) clear.hidden = false;
    } else {
        img.removeAttribute('src');
        img.hidden = true;
        if (ph) ph.style.display = '';
        if (clear) clear.hidden = true;
    }
}

/* —— Generic drag-drop image upload helper (hero + background) —— */
function setupAppstoreDropzone({ zoneSel, inputSel, browseSel, clearSel, endpoint, label }) {
    const zone   = $(zoneSel);
    const input  = $(inputSel);
    const browse = $(browseSel);
    const clear  = $(clearSel);
    if (!zone || !input) return;

    zone.addEventListener('click', (e) => {
        if (e.target === browse) return;
        input.click();
    });
    browse?.addEventListener('click', (e) => {
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
    zone.addEventListener('drop', async (e) => {
        const f = Array.from(e.dataTransfer?.files || []).find(
            (x) => /^image\//.test(x.type),
        );
        if (f) await upload(f);
    });
    input.addEventListener('change', async () => {
        const f = input.files?.[0];
        if (f) await upload(f);
        input.value = '';
    });
    clear?.addEventListener('click', async (e) => {
        e.stopPropagation();
        try {
            await api(endpoint, { method: 'DELETE' });
            toast(`${label} cleared`);
            refreshAll();
        } catch (err) { toast('Clear failed: ' + err.message, true); }
    });

    async function upload(file) {
        try {
            const form = new FormData();
            form.append('file', file);
            await api(endpoint, { method: 'POST', body: form });
            toast(`${label} updated`);
            refreshAll();
        } catch (err) { toast('Upload failed: ' + err.message, true); }
    }
}

(function setupAppstoreDropzones() {
    setupAppstoreDropzone({
        zoneSel:   '#appstoreBgDrop',
        inputSel:  '#appstoreBgFile',
        browseSel: '#appstoreBgBrowse',
        clearSel:  '#appstoreBgClear',
        endpoint:  '/api/admin/appstore/background',
        label:     'Background image',
    });
    setupAppstoreDropzone({
        zoneSel:   '#appstoreLogoDrop',
        inputSel:  '#appstoreLogoFile',
        browseSel: '#appstoreLogoBrowse',
        clearSel:  '#appstoreLogoClear',
        endpoint:  '/api/admin/appstore/logo',
        label:     'Logo image',
    });
    // v2.8.25 — V2 AI screen background dropzone.
    setupAppstoreDropzone({
        zoneSel:   '#v2aiBgDrop',
        inputSel:  '#v2aiBgFile',
        browseSel: '#v2aiBgBrowse',
        clearSel:  '#v2aiBgClear',
        endpoint:  '/api/admin/v2ai/background',
        label:     'V2 AI background',
    });
    // v2.8.26 — V2 AI top-bar button icon dropzone.
    setupAppstoreDropzone({
        zoneSel:   '#v2aiBtnDrop',
        inputSel:  '#v2aiBtnFile',
        browseSel: '#v2aiBtnBrowse',
        clearSel:  '#v2aiBtnClear',
        endpoint:  '/api/admin/v2ai/button',
        label:     'V2 AI button icon',
    });
    // v2.8.30 — V2 AI in-activity HOLD-button image dropzone.
    setupAppstoreDropzone({
        zoneSel:   '#v2aiHoldDrop',
        inputSel:  '#v2aiHoldFile',
        browseSel: '#v2aiHoldBrowse',
        clearSel:  '#v2aiHoldClear',
        endpoint:  '/api/admin/v2ai/hold-button',
        label:     'V2 AI hold button',
    });
    // v2.8.50 — Featured-panel HEADING image dropzone (Layout Editor).
    setupAppstoreDropzone({
        zoneSel:   '#layoutHeadingImgDrop',
        inputSel:  '#layoutHeadingImgFile',
        browseSel: '#layoutHeadingImgBrowse',
        clearSel:  '#layoutHeadingImgClear',
        endpoint:  '/api/admin/layout/heading-image',
        label:     'Heading image',
    });
    // v2.8.58 — Inline "× Clear image" button next to the URL field.
    // Hits the same DELETE endpoint as the Remove badge on the
    // preview tile — gives the admin a second, more obvious path
    // to clear an image they uploaded.  Also blanks the URL input
    // immediately so the next Save reflects the cleared state.
    const inlineClear = document.getElementById('layoutHeadingImgClearInline');
    if (inlineClear) {
        inlineClear.addEventListener('click', async (e) => {
            e.preventDefault();
            try {
                await api('/api/admin/layout/heading-image', { method: 'DELETE' });
                const urlInput = document.getElementById('layout_featured_heading_image_url');
                if (urlInput) urlInput.value = '';
                toast('Heading image cleared');
                refreshAll();
            } catch (err) { toast('Clear failed: ' + err.message, true); }
        });
    }
})();

/* v2.8.25 — V2 AI screen heading text editor. */
function syncV2AIInputs(store) {
    const v2ai = (store && store.v2ai) || {};
    const $i = document.getElementById('v2aiHeadingText');
    if ($i) $i.value = v2ai.heading_text || '';
    syncAppstoreImg(
        '#v2aiBgImg', '#v2aiBgPreview', '#v2aiBgClear',
        v2ai.background_image_url,
    );
    syncAppstoreImg(
        '#v2aiBtnImg', '#v2aiBtnPreview', '#v2aiBtnClear',
        v2ai.button_image_url,
    );
    // v2.8.30 — Mirror hold-button image + visibility toggle.
    syncAppstoreImg(
        '#v2aiHoldImg', '#v2aiHoldPreview', '#v2aiHoldClear',
        v2ai.hold_button_image_url,
    );
    const $hv = document.getElementById('v2aiHoldVisible');
    if ($hv) $hv.checked = v2ai.hold_button_visible !== false;
    // v2.8.26 — Mirror waveform style selection.
    const selectedStyle = (v2ai.waveform_style || 'bars').toLowerCase();
    document.querySelectorAll('#v2aiWaveformGrid .v2ai-wf-card').forEach(card => {
        card.classList.toggle('selected', card.dataset.style === selectedStyle);
    });
}
(function setupV2AIControls() {
    const $i     = document.getElementById('v2aiHeadingText');
    const $save  = document.getElementById('v2aiHeadingSave');
    const $reset = document.getElementById('v2aiHeadingReset');
    if (!$i || !$save) return;
    $save.addEventListener('click', async () => {
        try {
            await api('/api/admin/v2ai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ heading_text: $i.value }),
            });
            toast('V2 AI heading saved');
            refreshAll();
        } catch (e) { toast('Save failed: ' + e.message, true); }
    });
    $reset?.addEventListener('click', async () => {
        $i.value = '';
        try {
            await api('/api/admin/v2ai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ heading_text: '' }),
            });
            toast('Reset to default');
            refreshAll();
        } catch (e) { toast('Reset failed: ' + e.message, true); }
    });
    // v2.8.26 — Waveform style picker.
    document.querySelectorAll('#v2aiWaveformGrid .v2ai-wf-card').forEach(card => {
        card.addEventListener('click', async () => {
            const style = card.dataset.style;
            if (!style) return;
            try {
                await api('/api/admin/v2ai/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ waveform_style: style }),
                });
                toast(`Waveform set to "${style}"`);
                refreshAll();
            } catch (e) { toast('Could not save: ' + e.message, true); }
        });
    });
    // v2.8.30 — Hold-button visibility toggle.
    const $hv = document.getElementById('v2aiHoldVisible');
    $hv?.addEventListener('change', async () => {
        try {
            await api('/api/admin/v2ai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ hold_button_visible: $hv.checked }),
            });
            toast($hv.checked ? 'Hold button visible' : 'Hold button hidden');
            refreshAll();
        } catch (e) {
            toast('Could not save: ' + e.message, true);
            $hv.checked = !$hv.checked;  // revert UI
        }
    });
})();

/* v2.8.18 — App-tile color editor.  Two `<input type="color">`
   widgets that POST to /api/admin/appstore/tile-colors when the
   user clicks "Save colors".  The render path picks up the
   current values on every store refresh. */
function syncTileColorInputs(store) {
    const meta = (store && store.appstore) || {};
    // Backend stores '#AARRGGBB' (Android format) — strip alpha for
    // the `<input type=color>` which only accepts '#RRGGBB'.
    const stripAlpha = (c) => {
        if (!c) return null;
        if (c.length === 9) return '#' + c.slice(3);  // #AARRGGBB → #RRGGBB
        return c.length === 7 ? c : null;
    };
    const bg = stripAlpha(meta.tile_bg_color)   || '#0F1B30';
    const tx = stripAlpha(meta.tile_text_color) || '#F4F7FB';
    const $bg = document.getElementById('tileBgColor');
    const $tx = document.getElementById('tileTextColor');
    const $bgHex = document.getElementById('tileBgColorHex');
    const $txHex = document.getElementById('tileTextColorHex');
    if ($bg)   $bg.value = bg;
    if ($tx)   $tx.value = tx;
    if ($bgHex) $bgHex.textContent = bg.toUpperCase();
    if ($txHex) $txHex.textContent = tx.toUpperCase();
}

(function setupTileColorEditor() {
    const $bg    = document.getElementById('tileBgColor');
    const $tx    = document.getElementById('tileTextColor');
    const $bgHex = document.getElementById('tileBgColorHex');
    const $txHex = document.getElementById('tileTextColorHex');
    const $save  = document.getElementById('tileColorsSave');
    const $reset = document.getElementById('tileColorsReset');
    if (!$bg || !$tx || !$save) return;
    $bg.addEventListener('input', () => {
        if ($bgHex) $bgHex.textContent = $bg.value.toUpperCase();
    });
    $tx.addEventListener('input', () => {
        if ($txHex) $txHex.textContent = $tx.value.toUpperCase();
    });
    $save.addEventListener('click', async () => {
        try {
            await api('/api/admin/appstore/tile-colors', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    tile_bg_color:   $bg.value,
                    tile_text_color: $tx.value,
                }),
            });
            toast('Tile colors saved — boxes will update on next poll');
            refreshAll();
        } catch (err) { toast('Save failed: ' + err.message, true); }
    });
    $reset.addEventListener('click', async () => {
        try {
            await api('/api/admin/appstore/tile-colors', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    tile_bg_color:   null,
                    tile_text_color: null,
                }),
            });
            toast('Tile colors reset to defaults');
            refreshAll();
        } catch (err) { toast('Reset failed: ' + err.message, true); }
    });
})();

/* v2.8.22 — Top-bar pill color editor.  Handles resting + focused
   states.  Posts the 4 hex values to /api/admin/appstore/topbar-colors. */
function syncTopbarColorInputs(store) {
    const meta = (store && store.appstore) || {};
    const stripAlpha = (c) => {
        if (!c) return null;
        if (c.length === 9) return '#' + c.slice(3);
        return c.length === 7 ? c : null;
    };
    const set = (inputId, hexId, val, fallback) => {
        const v = stripAlpha(val) || fallback;
        const $i = document.getElementById(inputId);
        const $h = document.getElementById(hexId);
        if ($i) $i.value = v;
        if ($h) $h.textContent = v.toUpperCase();
    };
    set('topbarBtnBgColor',        'topbarBtnBgColorHex',        meta.topbar_btn_bg_color,         '#203A5C');
    set('topbarBtnTextColor',      'topbarBtnTextColorHex',      meta.topbar_btn_text_color,       '#FFFFFF');
    set('topbarBtnFocusBgColor',   'topbarBtnFocusBgColorHex',   meta.topbar_btn_focus_bg_color,   '#2BB6FF');
    set('topbarBtnFocusTextColor', 'topbarBtnFocusTextColorHex', meta.topbar_btn_focus_text_color, '#04060B');
}

(function setupTopbarColorEditor() {
    const ids = [
        'topbarBtnBgColor', 'topbarBtnTextColor',
        'topbarBtnFocusBgColor', 'topbarBtnFocusTextColor',
    ];
    ids.forEach((id) => {
        const $i = document.getElementById(id);
        const $h = document.getElementById(id + 'Hex');
        if (!$i) return;
        $i.addEventListener('input', () => {
            if ($h) $h.textContent = $i.value.toUpperCase();
        });
    });
    const $save  = document.getElementById('topbarColorsSave');
    const $reset = document.getElementById('topbarColorsReset');
    if (!$save) return;
    $save.addEventListener('click', async () => {
        try {
            await api('/api/admin/appstore/topbar-colors', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    topbar_btn_bg_color:         document.getElementById('topbarBtnBgColor').value,
                    topbar_btn_text_color:       document.getElementById('topbarBtnTextColor').value,
                    topbar_btn_focus_bg_color:   document.getElementById('topbarBtnFocusBgColor').value,
                    topbar_btn_focus_text_color: document.getElementById('topbarBtnFocusTextColor').value,
                }),
            });
            toast('Top-bar colors saved');
            refreshAll();
        } catch (err) { toast('Save failed: ' + err.message, true); }
    });
    $reset.addEventListener('click', async () => {
        try {
            await api('/api/admin/appstore/topbar-colors', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    topbar_btn_bg_color:         null,
                    topbar_btn_text_color:       null,
                    topbar_btn_focus_bg_color:   null,
                    topbar_btn_focus_text_color: null,
                }),
            });
            toast('Top-bar colors reset to defaults');
            refreshAll();
        } catch (err) { toast('Reset failed: ' + err.message, true); }
    });
})();

/* v2.8.38 — V2 AI pill-specific color editor.  Overrides the shared
 * top-bar palette JUST for the V2 AI pill.  Uses /api/admin/v2ai/config
 * which already accepts button_* color fields. */
function syncV2AIBtnColorInputs(store) {
    const v2ai = (store && store.v2ai) || {};
    const stripAlpha = (c) => {
        if (!c) return null;
        if (c.length === 9) return '#' + c.slice(3);
        return c.length === 7 ? c : null;
    };
    const set = (inputId, hexId, val, fallback) => {
        const v = stripAlpha(val) || fallback;
        const $i = document.getElementById(inputId);
        const $h = document.getElementById(hexId);
        if ($i) $i.value = v;
        if ($h) $h.textContent = v.toUpperCase();
    };
    set('v2aiBtnBgColor',        'v2aiBtnBgColorHex',        v2ai.button_bg_color,         '#203A5C');
    set('v2aiBtnTextColor',      'v2aiBtnTextColorHex',      v2ai.button_text_color,       '#FFFFFF');
    set('v2aiBtnFocusBgColor',   'v2aiBtnFocusBgColorHex',   v2ai.button_focus_bg_color,   '#2BB6FF');
    set('v2aiBtnFocusTextColor', 'v2aiBtnFocusTextColorHex', v2ai.button_focus_text_color, '#04060B');
}

(function setupV2AIBtnColorEditor() {
    const ids = [
        'v2aiBtnBgColor', 'v2aiBtnTextColor',
        'v2aiBtnFocusBgColor', 'v2aiBtnFocusTextColor',
    ];
    ids.forEach((id) => {
        const $i = document.getElementById(id);
        const $h = document.getElementById(id + 'Hex');
        if (!$i) return;
        $i.addEventListener('input', () => {
            if ($h) $h.textContent = $i.value.toUpperCase();
        });
    });
    const $save  = document.getElementById('v2aiBtnColorsSave');
    const $reset = document.getElementById('v2aiBtnColorsReset');
    if (!$save) return;
    $save.addEventListener('click', async () => {
        try {
            await api('/api/admin/v2ai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    button_bg_color:         document.getElementById('v2aiBtnBgColor').value,
                    button_text_color:       document.getElementById('v2aiBtnTextColor').value,
                    button_focus_bg_color:   document.getElementById('v2aiBtnFocusBgColor').value,
                    button_focus_text_color: document.getElementById('v2aiBtnFocusTextColor').value,
                }),
            });
            toast('V2 AI button colors saved');
            refreshAll();
        } catch (err) { toast('Save failed: ' + err.message, true); }
    });
    $reset.addEventListener('click', async () => {
        try {
            await api('/api/admin/v2ai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    button_bg_color:         '',
                    button_text_color:       '',
                    button_focus_bg_color:   '',
                    button_focus_text_color: '',
                }),
            });
            toast('V2 AI button now uses shared top-bar colors');
            refreshAll();
        } catch (err) { toast('Reset failed: ' + err.message, true); }
    });
})();

/* v2.8.49 — V2 AI hero-pill size editor (height + width).  Height
 * applies always (default 64 dp); width 0 = wrap_content (default),
 * otherwise pill is fixed at that width — important when the admin
 * uploads a custom image so the image gets a proper hero canvas. */
function syncV2AIBtnSizeInputs(store) {
    const v2ai = (store && store.v2ai) || {};
    const $h = document.getElementById('v2aiBtnHeightDp');
    const $w = document.getElementById('v2aiBtnWidthDp');
    if ($h) $h.value = Number.isInteger(v2ai.button_height_dp) ? v2ai.button_height_dp : 64;
    if ($w) $w.value = Number.isInteger(v2ai.button_width_dp)  ? v2ai.button_width_dp  : 0;
}

(function setupV2AIBtnSizeEditor() {
    const $save  = document.getElementById('v2aiBtnSizeSave');
    const $reset = document.getElementById('v2aiBtnSizeReset');
    if (!$save) return;
    $save.addEventListener('click', async () => {
        try {
            const h = parseInt(document.getElementById('v2aiBtnHeightDp').value, 10);
            const w = parseInt(document.getElementById('v2aiBtnWidthDp').value,  10);
            if (!Number.isInteger(h) || h < 32 || h > 200) {
                toast('Height must be 32–200 dp', true); return;
            }
            if (!Number.isInteger(w) || (w !== 0 && (w < 60 || w > 600))) {
                toast('Width must be 0 (auto) or 60–600 dp', true); return;
            }
            await api('/api/admin/v2ai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ button_height_dp: h, button_width_dp: w }),
            });
            toast('V2 AI pill size saved');
            refreshAll();
        } catch (err) { toast('Save failed: ' + err.message, true); }
    });
    if ($reset) $reset.addEventListener('click', async () => {
        try {
            await api('/api/admin/v2ai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ button_height_dp: 64, button_width_dp: 0 }),
            });
            toast('V2 AI pill size reset (64 dp · auto)');
            refreshAll();
        } catch (err) { toast('Reset failed: ' + err.message, true); }
    });
})();


/* v2.8.22 — Speed Test target APK package editor. */
function syncSpeedTestTarget(store) {
    const meta = (store && store.appstore) || {};
    const $i = document.getElementById('speedTestPackage');
    if ($i) $i.value = meta.speed_test_package || '';
}

(function setupSpeedTestTargetEditor() {
    const $i     = document.getElementById('speedTestPackage');
    const $save  = document.getElementById('speedTestSave');
    const $clear = document.getElementById('speedTestClear');
    if (!$i || !$save) return;
    $save.addEventListener('click', async () => {
        try {
            await api('/api/admin/appstore/speed-test-target', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    speed_test_package: ($i.value || '').trim() || null,
                }),
            });
            toast('Speed Test target saved');
            refreshAll();
        } catch (err) { toast('Save failed: ' + err.message, true); }
    });
    $clear.addEventListener('click', async () => {
        try {
            $i.value = '';
            await api('/api/admin/appstore/speed-test-target', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ speed_test_package: null }),
            });
            toast('Speed Test target cleared');
            refreshAll();
        } catch (err) { toast('Clear failed: ' + err.message, true); }
    });
})();

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
        $('#drawerCategory').value    = apk.category    || '';
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
                category:     $('#drawerCategory').value.trim()    || null,
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

/* ─────────────  QR Videos  ───────────── */
function _qrKindFor(url) {
    const u = String(url || '').trim();
    if (!u) return 'link';
    if (/drive\.google\.com\/file\/d\//.test(u)) return 'iframe';
    if (/drive\.google\.com\/.*[?&]id=/.test(u)) return 'iframe';
    if (/dropbox\.com/.test(u)) return 'video';
    if (/youtube\.com\/watch\?v=|youtu\.be\//.test(u)) return 'iframe';
    if (/\.(mp4|m4v|mov|webm|ogg|mkv)(\?|$)/i.test(u)) return 'video';
    return 'link';
}
function _qrKindLabel(kind) {
    if (kind === 'iframe') return 'EMBED';
    if (kind === 'video')  return 'INLINE VIDEO';
    return 'OPEN LINK';
}

async function _fetchQrVideos() {
    const r = await api('/api/admin/qr-videos');
    return r.data || [];
}

async function renderQrVideos() {
    const list = $('#qrVideoList');
    const empty = $('#qrVideoEmpty');
    if (!list) return;
    let items;
    try { items = await _fetchQrVideos(); }
    catch { items = []; }
    if (!items.length) {
        list.innerHTML = '';
        empty.hidden = false;
        return;
    }
    empty.hidden = true;
    list.innerHTML = items.map(v => {
        const kind = _qrKindFor(v.url);
        const kindLabel = _qrKindLabel(kind);
        const visBadge = v.visible
            ? '<span class="qr-badge visible-on">Visible on home</span>'
            : '<span class="qr-badge visible-off">Hidden</span>';
        const cardCls = v.visible ? 'qr-card' : 'qr-card hidden-card';
        const caption = v.caption
            ? `<div class="qr-card-caption">${escapeAttr(v.caption)}</div>`
            : '';
        const toggleLabel = v.visible ? 'Hide from home' : 'Show on home';
        return `
        <li class="${cardCls}" data-id="${escapeAttr(v.id)}">
            <div class="qr-card-head">
                <img class="qr-card-img" src="${escapeAttr(v.qr_image_url)}" alt="QR code">
                <div class="qr-card-body">
                    <div class="qr-card-title">${escapeAttr(v.name)}</div>
                    ${caption}
                    <div class="qr-card-url">${escapeAttr(v.url)}</div>
                    <div class="qr-card-badges">
                        <span class="qr-badge kind-${kind}">${kindLabel}</span>
                        ${visBadge}
                    </div>
                </div>
            </div>
            <div class="qr-card-actions">
                <a class="primary" href="${escapeAttr(v.player_url || '#')}" target="_blank" rel="noopener" data-testid="qrv-preview-${escapeAttr(v.id)}">Preview player</a>
                <a href="${escapeAttr(v.qr_image_url)}" target="_blank" rel="noopener" download="qr-${escapeAttr(v.id)}.png" data-testid="qrv-download-${escapeAttr(v.id)}">Download QR</a>
                <button class="qrv-edit" data-testid="qrv-edit-${escapeAttr(v.id)}">Edit</button>
                <button class="qrv-toggle" data-testid="qrv-toggle-${escapeAttr(v.id)}">${toggleLabel}</button>
                <button class="qrv-delete danger" data-testid="qrv-delete-${escapeAttr(v.id)}">Delete</button>
            </div>
        </li>`;
    }).join('');

    list.querySelectorAll('.qrv-toggle').forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.closest('li').dataset.id;
            const current = items.find(v => v.id === id);
            try {
                await api('/api/admin/qr-videos/' + encodeURIComponent(id), {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ visible: !current.visible }),
                });
                toast(current.visible ? 'Hidden from home' : 'Now visible on home');
                renderQrVideos();
            } catch (e) { toast('Toggle failed: ' + e.message, true); }
        });
    });
    list.querySelectorAll('.qrv-delete').forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.closest('li').dataset.id;
            if (!confirm('Delete this QR video?  The QR code will stop working.')) return;
            try {
                await api('/api/admin/qr-videos/' + encodeURIComponent(id), {
                    method: 'DELETE',
                });
                toast('Deleted');
                renderQrVideos();
            } catch (e) { toast('Delete failed: ' + e.message, true); }
        });
    });
    list.querySelectorAll('.qrv-edit').forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.closest('li').dataset.id;
            const v = items.find(x => x.id === id);
            if (!v) return;
            const newName = prompt('Title:', v.name);
            if (newName === null) return;
            const newCaption = prompt(
                'Caption (leave blank for none):',
                v.caption || '',
            );
            if (newCaption === null) return;
            const newUrl = prompt('Video URL:', v.url);
            if (newUrl === null) return;
            try {
                await api('/api/admin/qr-videos/' + encodeURIComponent(id), {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        name: newName.trim() || null,
                        caption: newCaption.trim(),
                        url: newUrl.trim() || null,
                    }),
                });
                toast('Saved');
                renderQrVideos();
            } catch (e) { toast('Save failed: ' + e.message, true); }
        });
    });
}

$('#qrVideoForm')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = $('#qrvName').value.trim();
    const url = $('#qrvUrl').value.trim();
    const caption = $('#qrvCaption').value.trim();
    const visible = $('#qrvVisible').checked;
    if (!name || !url) {
        toast('Title and URL are required', true);
        return;
    }
    try {
        await api('/api/admin/qr-videos', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, url, caption, visible }),
        });
        $('#qrvName').value = '';
        $('#qrvUrl').value = '';
        $('#qrvCaption').value = '';
        $('#qrvVisible').checked = true;
        toast('QR generated');
        renderQrVideos();
    } catch (err) { toast('Failed: ' + err.message, true); }
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

/* ============================================================
   Vesper Logins tab — manage the Vesper v2 login vault
   ============================================================ */
const Vesper = {
    accounts: [],
    filter: '',
    editingId: null,
    confirmingDeleteId: null,

    async load() {
        try {
            const data = await api('/api/admin/vesper-accounts');
            this.accounts = (data && Array.isArray(data.accounts)) ? data.accounts : [];
            this.render();
        } catch (e) {
            console.error('Vesper.load failed', e);
            this.accounts = [];
            this.render(e.message || 'Failed to load');
        }
    },

    fmtExpires(iso) {
        if (!iso) return { label: 'No expiry', state: 'ok' };
        try {
            const d = new Date(iso);
            const now = new Date();
            const days = Math.floor((d.getTime() - now.getTime()) / 86400000);
            const niceDate = d.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
            if (days < 0) return { label: `Expired ${niceDate}`, state: 'expired' };
            if (days <= 14) return { label: `${niceDate} (in ${days}d)`, state: 'expiring' };
            return { label: niceDate, state: 'ok' };
        } catch { return { label: iso, state: 'ok' }; }
    },

    statusLabel(row) {
        if ((row.status || 'active') === 'disabled') return { label: 'Suspended', cls: 'disabled' };
        const e = this.fmtExpires(row.expires_at);
        if (e.state === 'expired')  return { label: 'Expired', cls: 'expired' };
        if (e.state === 'expiring') return { label: 'Active', cls: 'expiring' };
        return { label: 'Active', cls: '' };
    },

    render(errMsg = '') {
        const list = $('#vesperList');
        const count = $('#vesperCount');
        if (!list) return;
        const filter = (this.filter || '').toLowerCase().trim();
        const rows = this.accounts.filter(a => !filter
            || (a.username || '').toLowerCase().includes(filter)
            || (a.label || '').toLowerCase().includes(filter)
            || (a.notes || '').toLowerCase().includes(filter));
        count.textContent = String(this.accounts.length);
        if (errMsg) {
            list.innerHTML = `<li class="vesper-empty" style="color:var(--danger,#ff5e5e)">Error: ${errMsg}</li>`;
            return;
        }
        if (!this.accounts.length) {
            list.innerHTML = '<li class="vesper-empty">No clients yet — add your first one above.</li>';
            return;
        }
        if (!rows.length) {
            list.innerHTML = '<li class="vesper-empty">No matches.</li>';
            return;
        }
        list.innerHTML = rows.map(r => this.renderRow(r)).join('');
        // Wire row buttons
        rows.forEach(r => this.wireRow(r));
    },

    renderRow(r) {
        const isEditing = this.editingId === r.id;
        const exp = this.fmtExpires(r.expires_at);
        const st = this.statusLabel(r);
        const expVal = r.expires_at ? r.expires_at.slice(0, 10) : '';
        const safeNotes = (r.notes || '').replace(/"/g, '&quot;');
        if (isEditing) {
            return `
<li class="vesper-row editing" data-id="${r.id}">
    <div class="v-cell">
        <span class="v-eyebrow">Username</span>
        <input data-v-field="username" type="text" value="${r.username || ''}">
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">Password</span>
        <input data-v-field="password" type="text" value="${r.password || ''}">
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">Label / Notes</span>
        <input data-v-field="label" type="text" value="${r.label || ''}" placeholder="Label">
        <input data-v-field="notes" type="text" value="${safeNotes}" placeholder="Notes" style="margin-top:6px;">
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">Expires</span>
        <input data-v-field="expires_at" type="date" value="${expVal}">
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">Status</span>
        <select data-v-field="status" style="height:34px;padding:0 10px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--txt);font-size:13px;font-weight:500;">
            <option value="active"   ${ (r.status||'active')==='active'   ? 'selected' : '' }>Active</option>
            <option value="disabled" ${ (r.status||'active')==='disabled' ? 'selected' : '' }>Suspended</option>
        </select>
    </div>
    <div class="v-actions">
        <button data-v-save="${r.id}" class="primary">Save</button>
        <button data-v-cancel="${r.id}">Cancel</button>
    </div>
</li>`;
        }
        return `
<li class="vesper-row" data-id="${r.id}">
    <div class="v-cell">
        <span class="v-eyebrow">Username</span>
        <span class="v-value" title="${r.username || ''}">${r.username || ''}</span>
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">Password</span>
        <span class="v-value" title="${r.password || ''}">${r.password || ''}</span>
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">${r.label ? 'Label' : 'Notes'}</span>
        <span class="v-value" title="${(r.label || r.notes || '').replace(/"/g, '&quot;')}">${r.label || r.notes || '—'}</span>
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">Expires</span>
        <span class="v-value" title="${exp.label}">${exp.label}</span>
    </div>
    <div class="v-cell">
        <span class="v-eyebrow">Status</span>
        <span class="v-status ${st.cls}"><span class="dot"></span>${st.label}</span>
    </div>
    <div class="v-actions">
        <button data-v-edit="${r.id}">Edit</button>
        <button data-v-delete="${r.id}" class="danger${this.confirmingDeleteId === r.id ? ' confirming' : ''}">
            ${this.confirmingDeleteId === r.id ? 'Confirm?' : 'Delete'}
        </button>
    </div>
</li>`;
    },

    wireRow(r) {
        const root = document.querySelector(`.vesper-row[data-id="${r.id}"]`);
        if (!root) return;
        const editBtn = root.querySelector(`[data-v-edit="${r.id}"]`);
        if (editBtn) editBtn.addEventListener('click', () => {
            this.editingId = r.id; this.confirmingDeleteId = null; this.render();
        });
        const cancelBtn = root.querySelector(`[data-v-cancel="${r.id}"]`);
        if (cancelBtn) cancelBtn.addEventListener('click', () => {
            this.editingId = null; this.render();
        });
        const saveBtn = root.querySelector(`[data-v-save="${r.id}"]`);
        if (saveBtn) saveBtn.addEventListener('click', () => this.save(r.id));
        const delBtn = root.querySelector(`[data-v-delete="${r.id}"]`);
        if (delBtn) delBtn.addEventListener('click', () => this.delete(r.id));
    },

    async save(id) {
        const root = document.querySelector(`.vesper-row[data-id="${id}"]`);
        if (!root) return;
        const get = (f) => root.querySelector(`[data-v-field="${f}"]`).value;
        const payload = {
            username:   get('username').trim(),
            password:   get('password'),
            label:      get('label').trim(),
            notes:      get('notes').trim(),
            status:     get('status'),
            expires_at: get('expires_at') ? `${get('expires_at')}T23:59:59` : null,
        };
        if (!payload.username || !payload.password) {
            toast('Username and password are required', true);
            return;
        }
        try {
            await api(`/api/admin/vesper-accounts/${id}`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            this.editingId = null;
            toast('Saved');
            await this.load();
        } catch (e) {
            toast(e.message || 'Save failed', true);
        }
    },

    async delete(id) {
        if (this.confirmingDeleteId !== id) {
            this.confirmingDeleteId = id;
            this.render();
            // Auto-cancel after 4 s
            clearTimeout(this._confirmTimer);
            this._confirmTimer = setTimeout(() => {
                this.confirmingDeleteId = null; this.render();
            }, 4000);
            return;
        }
        clearTimeout(this._confirmTimer);
        this.confirmingDeleteId = null;
        try {
            await api(`/api/admin/vesper-accounts/${id}`, { method: 'DELETE' });
            toast('Deleted');
            await this.load();
        } catch (e) {
            toast(e.message || 'Delete failed', true);
        }
    },

    async create(payload) {
        await api('/api/admin/vesper-accounts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
    },
};

// Wire the Add form
const _vForm = $('#vesperForm');
if (_vForm) {
    _vForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const status = $('#vesperFormStatus');
        const payload = {
            username:   $('#vesperUsername').value.trim(),
            password:   $('#vesperPassword').value,
            label:      $('#vesperLabel').value.trim(),
            notes:      $('#vesperNotes').value.trim(),
            expires_at: $('#vesperExpires').value ? `${$('#vesperExpires').value}T23:59:59` : null,
        };
        if (!payload.username || !payload.password) {
            status.textContent = 'Username and password are required';
            status.className = 'vesper-form-status err';
            return;
        }
        status.textContent = 'Saving…'; status.className = 'vesper-form-status';
        try {
            await Vesper.create(payload);
            status.textContent = `Added ${payload.username}`;
            status.className = 'vesper-form-status ok';
            // Reset form
            $('#vesperUsername').value = '';
            $('#vesperPassword').value = '';
            $('#vesperLabel').value = '';
            $('#vesperNotes').value = '';
            $('#vesperExpires').value = '';
            await Vesper.load();
            setTimeout(() => { status.textContent = ''; status.className = 'vesper-form-status'; }, 3000);
        } catch (e) {
            status.textContent = e.message || 'Save failed';
            status.className = 'vesper-form-status err';
        }
    });
}

const _vSearch = $('#vesperSearch');
if (_vSearch) _vSearch.addEventListener('input', () => {
    Vesper.filter = _vSearch.value;
    Vesper.render();
});

const _vRefresh = $('#vesperRefresh');
if (_vRefresh) _vRefresh.addEventListener('click', () => Vesper.load());

// Lazy-load the data the first time the tab opens, plus every time
// the user re-clicks the tab (so they always see current state).
$$('.tab').forEach((btn) => {
    if (btn.dataset.tab === 'vesper') {
        btn.addEventListener('click', () => Vesper.load());
    }
});

/* ============================================================
   Backup & Restore tab — full launcher snapshot ZIP migration
   ============================================================ */
const _backupBtn = $('#backupDownload');
if (_backupBtn) _backupBtn.addEventListener('click', async () => {
    const status = $('#backupStatus');
    status.textContent = 'Preparing your backup… this can take a minute on a launcher with lots of APKs.';
    status.className = 'backup-status busy';
    try {
        // Direct download via anchor — works for large files (multi-GB)
        // because the browser streams to disk instead of buffering in JS.
        const a = document.createElement('a');
        a.href = _abs('/api/admin/backup');
        // Browser will pick the filename from Content-Disposition.
        a.click();
        status.textContent = 'Download started. Check your browser downloads tray.';
        status.className = 'backup-status ok';
    } catch (e) {
        status.textContent = e.message || 'Backup failed';
        status.className = 'backup-status err';
    }
});

const _restoreInput = $('#restoreFile');
const _restoreBtn   = $('#restoreBtn');
const _restoreLabel = document.querySelector('#tab-backup .backup-file-label');

if (_restoreInput) _restoreInput.addEventListener('change', () => {
    const f = _restoreInput.files && _restoreInput.files[0];
    if (f) {
        _restoreLabel.textContent = `${f.name}  ·  ${(f.size / (1024 * 1024)).toFixed(1)} MB`;
        _restoreLabel.classList.add('has-file');
        _restoreBtn.disabled = false;
    } else {
        _restoreLabel.textContent = 'Choose backup ZIP…';
        _restoreLabel.classList.remove('has-file');
        _restoreBtn.disabled = true;
    }
});

if (_restoreBtn) _restoreBtn.addEventListener('click', async () => {
    const f = _restoreInput.files && _restoreInput.files[0];
    if (!f) return;
    if (!confirm(`Restore ${f.name}?\n\nThis will OVERWRITE the current dock tiles, APKs, wallpapers, devices and layout settings. Vesper Logins are unaffected.\n\nProceed?`)) return;
    const status = $('#restoreStatus');
    status.textContent = `Uploading ${f.name}…`;
    status.className = 'backup-status busy';
    _restoreBtn.disabled = true;
    const fd = new FormData();
    fd.append('file', f);
    try {
        const r = await fetch(_abs('/api/admin/restore'), {
            method: 'POST',
            body: fd,
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) {
            status.textContent = `Restore failed: ${data.detail || r.status}`;
            status.className = 'backup-status err';
            _restoreBtn.disabled = false;
            return;
        }
        status.innerHTML = `Restored. ${data.files} files unpacked · ${data.dock_tiles} dock tiles · ${data.apks} APKs · ${data.devices} devices. <strong>Refresh this page</strong> to see everything.`;
        status.className = 'backup-status ok';
        toast('Restore complete — refresh the page');
    } catch (e) {
        status.textContent = e.message || 'Restore failed';
        status.className = 'backup-status err';
        _restoreBtn.disabled = false;
    }
});
