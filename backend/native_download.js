(() => {
  'use strict';
  const storageKey = 'vesper-native-download-v1';
  const retentionMs = 15 * 60 * 1000;
  const button = document.getElementById('download');
  const status = document.getElementById('status');
  const direct = document.getElementById('direct-download');
  const directHelp = document.getElementById('direct-help');
  let access = null;
  let downloading = false;
  let ticketExpiresAt = 0;

  function clearDirectLink() {
    direct.removeAttribute('href');
    directHelp.hidden = true;
    ticketExpiresAt = 0;
  }

  function forgetAccess() {
    access = null;
    clearDirectLink();
    try { sessionStorage.removeItem(storageKey); } catch (_) {}
  }

  function valid(record) {
    return record && typeof record.token === 'string' &&
      /^[A-Za-z0-9_-]{16,512}$/.test(record.token) &&
      Number.isFinite(record.expiresAt) && record.expiresAt > Date.now() &&
      record.expiresAt <= Date.now() + retentionMs;
  }

  function restoreAccess() {
    const fragment = window.location.hash.slice(1);
    if (fragment) {
      forgetAccess();
      try {
        const record = { token: decodeURIComponent(fragment), expiresAt: Date.now() + retentionMs };
        if (valid(record)) {
          access = record;
          try { sessionStorage.setItem(storageKey, JSON.stringify(record)); } catch (_) {}
        }
      } catch (_) {}
    } else {
      try { access = JSON.parse(sessionStorage.getItem(storageKey)); } catch (_) {}
    }
    try { history.replaceState(null, '', window.location.pathname); } catch (_) {}
    if (!valid(access)) forgetAccess();
    button.disabled = downloading || !access;
    status.textContent = access ? 'Ready to download your private test APK.' :
      'Open the complete private download link from your chat. Access lasts 15 minutes in this tab.';
  }

  restoreAccess();
  window.addEventListener('hashchange', restoreAccess);
  direct.addEventListener('click', event => {
    if (!ticketExpiresAt || Date.now() >= ticketExpiresAt) {
      event.preventDefault();
      clearDirectLink();
      status.textContent = 'The direct link expired. Tap Download APK for a new one.';
    }
  });
  button.addEventListener('click', async () => {
    if (downloading) return;
    if (!valid(access)) {
      forgetAccess();
      button.disabled = true;
      status.textContent = 'Download access expired. Reopen the complete private link from your chat.';
      return;
    }
    const currentAccess = access;
    clearDirectLink();
    downloading = true;
    button.disabled = true;
    status.textContent = 'Getting your private download link…';
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch('/api/download/vesper-native-ticket', {
        method: 'POST', headers: { 'X-Download-Token': currentAccess.token, 'Accept': 'application/json' },
        cache: 'no-store', credentials: 'omit', signal: controller.signal
      });
      if (access !== currentAccess) return;
      if (response.status === 404) {
        if (access === currentAccess) forgetAccess();
        status.textContent = 'Download unavailable. Reopen the complete private link from your chat.';
        return;
      }
      if (!response.ok) throw new Error('Download unavailable');
      const data = await response.json();
      if (access !== currentAccess) return;
      const url = new URL(data.url, window.location.origin);
      if (url.origin !== window.location.origin || url.pathname !== '/api/download/vesper-native-file' ||
          !url.searchParams.get('ticket') || !Number.isFinite(data.expiresIn) || data.expiresIn <= 0 || data.expiresIn > 3600) {
        throw new Error('Invalid download link');
      }
      direct.href = url.href;
      direct.download = data.filename;
      ticketExpiresAt = Date.now() + data.expiresIn * 1000;
      directHelp.hidden = false;
      status.textContent = 'Your APK is ready. Check your browser’s Downloads, or tap Download directly below.';
      direct.click();
    } catch (error) {
      if (access === currentAccess) status.textContent = error.name === 'AbortError' ?
        'The download request timed out. Tap Download APK to retry.' :
        'Download interrupted. Check your connection, then try again.';
    } finally {
      window.clearTimeout(timeout);
      downloading = false;
      button.disabled = !valid(access);
    }
  });
})();