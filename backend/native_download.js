(() => {
  'use strict';
  const storageKey = 'vesper-native-download-v1';
  const retentionMs = 15 * 60 * 1000;
  const button = document.getElementById('download');
  const status = document.getElementById('status');
  let access = null;
  let downloading = false;

  function forgetAccess() {
    access = null;
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
  button.addEventListener('click', async () => {
    if (downloading) return;
    if (!valid(access)) {
      forgetAccess();
      button.disabled = true;
      status.textContent = 'Download access expired. Reopen the complete private link from your chat.';
      return;
    }
    const currentAccess = access;
    downloading = true;
    button.disabled = true;
    status.textContent = 'Preparing your APK…';
    try {
      const response = await fetch('/api/download/vesper-native-apk', {
        headers: { 'X-Download-Token': currentAccess.token }, cache: 'no-store', credentials: 'omit'
      });
      if (response.status === 404) {
        if (access === currentAccess) forgetAccess();
        status.textContent = 'Download unavailable. Reopen the complete private link from your chat.';
        return;
      }
      if (!response.ok) throw new Error('Download unavailable');
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = url;
      link.download = /filename="([^"]+)"/.exec(response.headers.get('content-disposition') || '')?.[1] || 'vesper-native-private.apk';
      link.setAttribute('data-testid', 'download-artifact-link');
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60000);
      status.textContent = 'Download started. Open the APK on your Android TV box to install the update.';
    } catch {
      status.textContent = 'Download interrupted. Check your connection, then try again.';
    } finally {
      downloading = false;
      button.disabled = !valid(access);
    }
  });
})();