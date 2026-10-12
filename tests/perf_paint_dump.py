"""Dump which elements on Home carry expensive paint properties (diagnostic)."""
import asyncio, json, re, sys
from playwright.async_api import async_playwright
BASE = re.search(r'REACT_APP_BACKEND_URL=(\S+)', open('/app/frontend/.env').read()).group(1)
sys.path.insert(0, '/app/tests')
from perf_nav_probe import SEED

DUMP = """() => {
  const all = [...document.querySelectorAll('*')];
  const vis = (e) => { const r = e.getBoundingClientRect(); return r.width && r.height && r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; };
  const tag = (e) => (e.getAttribute('data-testid') || '') + '|' + e.tagName.toLowerCase() + '.' + String(e.className||'').toString().split(' ').slice(0,3).join('.');
  const group = (list, key) => { const m = {}; for (const e of list) { const k = key(e); m[k] = (m[k]||0)+1; } return Object.entries(m).sort((a,b)=>b[1]-a[1]).slice(0,14); };
  const bf = all.filter(e => getComputedStyle(e).backdropFilter !== 'none');
  const filt = all.filter(e => getComputedStyle(e).filter !== 'none');
  const wc = all.filter(e => getComputedStyle(e).willChange !== 'auto');
  const sh = all.filter(e => vis(e) && getComputedStyle(e).boxShadow !== 'none');
  const imgs = [...document.images].filter(i => i.naturalWidth && i.width && i.naturalWidth > i.width * 1.6).map(i => `${i.naturalWidth}x${i.naturalHeight} shown ${Math.round(i.width)}x${Math.round(i.height)} ${(i.currentSrc||'').replace(/^https?:\\/\\/[^/]+/, '').slice(0,70)}`).slice(0,10);
  return {
    backdrop: group(bf, e => (vis(e)?'VIS ':'') + tag(e) + ' => ' + getComputedStyle(e).backdropFilter),
    filter: group(filt, e => tag(e) + ' => ' + getComputedStyle(e).filter.slice(0,40)),
    willChange: group(wc, e => tag(e) + ' => ' + getComputedStyle(e).willChange),
    shadows: group(sh, e => tag(e) + ' => ' + getComputedStyle(e).boxShadow.slice(0,60)),
    oversized: imgs,
  };
}"""

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        page = await (await b.new_context(viewport={'width': 1920, 'height': 1080})).new_page()
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000)
        await page.wait_for_timeout(6000)
        d = await page.evaluate(DUMP)
        for k, v in d.items():
            print('==', k)
            for row in v: print('  ', row)
        await b.close()
asyncio.run(main())
