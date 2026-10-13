/* Parent may send only scene data, never commands/HTML/remote resource URLs. */
window.addEventListener('message', event => {
  if (event.source !== parent || event.origin !== location.origin || event.data?.type !== 'v2-sports-scene') return;
  const data = event.data.payload;
  if (!data || typeof data !== 'object') return;
  const success = window.SportsScene?.render(data) === true;
  parent.postMessage({type:'v2-sports-scene-ready', success}, location.origin);
});
window.addEventListener('pagehide', () => window.SportsScene?.dispose());