(() => {
  const token = window.location.hash.slice(1);
  history.replaceState(null, '', window.location.pathname);
  const button = document.getElementById('download');
  const status = document.getElementById('status');
  if (!token) {
    status.textContent = 'Open the private download link provided in your chat.';
    button.disabled = true;
    return;
  }
  button.addEventListener('click', async () => {
    button.disabled = true;
    status.textContent = 'Preparing your APK…';
    try {
      const response = await fetch('/api/download/vesper-native-apk', {
        headers: { 'X-Download-Token': token }, cache: 'no-store', credentials: 'omit'
      });
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
      status.textContent = 'Download unavailable. Check that you opened the complete private link, then try again.';
    } finally {
      button.disabled = false;
    }
  });
})();