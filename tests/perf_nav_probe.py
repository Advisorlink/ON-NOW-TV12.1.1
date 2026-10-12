"""Navigation smoothness probe (desktop Chromium, CPU-throttled to approximate a TV box).

Usage: /opt/plugins-venv/bin/python tests/perf_nav_probe.py [cpu_throttle=6]
Reports DOM/image/layer weight on Home and rAF frame-gap stats while pressing
ArrowRight x10 and ArrowDown x4 with ~120 ms spacing.  Diagnostic only.
"""
import asyncio, json, os, re, sys, statistics
from playwright.async_api import async_playwright

ENV = open('/app/frontend/.env').read()
BASE = re.search(r'REACT_APP_BACKEND_URL=(\S+)', ENV).group(1)
THROTTLE = float(sys.argv[1]) if len(sys.argv) > 1 else 6.0
ANDROID = 'android' in sys.argv[2:]
STUB = "window.OnNowTV = { isAndroidHost: () => true, deviceClass: () => 'normal', getHostPackage: () => 'tv.onnowtv.app' };"

SEED = """async () => {
  const r = await fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:'testuser', password:'testpass123'})});
  const d = await r.json();
  localStorage.setItem('vesper-auth-token-v1', d.access_token || d.token || '');
  localStorage.setItem('vesper-auth-account-v1', JSON.stringify(d.account || d.user || {username:'testuser'}));
  const profile = { id:'p-test-1', name:'Test', avatarId:'a1', kids:false, createdAt:Date.now() };
  localStorage.setItem('onnowtv-profiles-v1:testuser', JSON.stringify([profile]));
  localStorage.setItem('onnowtv-active-profile-v1:testuser', 'p-test-1');
  localStorage.setItem('vesper-onboarding-seen-v1', String(Date.now()));
  localStorage.setItem('onnowtv-whatsnew-seen-v1', '1.5.1');
  sessionStorage.setItem('vesper-e2e', '1');
}"""

WEIGHT = """() => {
  const all = [...document.querySelectorAll('*')];
  const imgs = [...document.images];
  const vis = (e) => { const r = e.getBoundingClientRect(); return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; };
  const bf = all.filter(e => { const cs = getComputedStyle(e); return (cs.backdropFilter && cs.backdropFilter !== 'none'); });
  const filt = all.filter(e => { const cs = getComputedStyle(e); return cs.filter && cs.filter !== 'none'; });
  const anim = document.getAnimations ? document.getAnimations().length : -1;
  const wc = all.filter(e => getComputedStyle(e).willChange !== 'auto').length;
  const shadows = all.filter(e => vis(e) && getComputedStyle(e).boxShadow !== 'none').length;
  const decoded = imgs.reduce((s, i) => s + (i.naturalWidth * i.naturalHeight || 0), 0);
  const over = imgs.filter(i => i.naturalWidth && i.width && i.naturalWidth > i.width * 1.6).length;
  return { nodes: all.length, imgs: imgs.length, imgsVisible: imgs.filter(vis).length, decodedMegapixels: +(decoded/1e6).toFixed(1), oversizedImgs: over,
           backdropFilter: bf.length, backdropVisible: bf.filter(vis).length, filter: filt.length, animations: anim, willChange: wc, visibleShadows: shadows,
           shelves: document.querySelectorAll('[data-testid^="shelf-row-"]').length, posters: document.querySelectorAll('[data-testid^="poster-"]').length };
}"""

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=['--enable-gpu-rasterization'])
        ctx = await b.new_context(viewport={'width': 1920, 'height': 1080})
        page = await ctx.new_page()
        if ANDROID: await page.add_init_script(STUB)
        cdp = await ctx.new_cdp_session(page)
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000)
        await page.wait_for_timeout(6000)
        print('MODE', 'android' if ANDROID else 'desktop', 'class', await page.evaluate("document.documentElement.className"))
        print('WEIGHT', json.dumps(await page.evaluate(WEIGHT)))
        try:
            await cdp.send('LayerTree.enable')
            layers = await cdp.send('LayerTree.compositingReasons', {'layerId': '0'}) if False else None
        except Exception:
            pass
        await cdp.send('Emulation.setCPUThrottlingRate', {'rate': THROTTLE})
        await page.focus('body')
        first = page.locator('[data-testid^="shelf-row-"] [data-testid^="poster-"]').first
        await first.focus()
        await page.wait_for_timeout(600)
        await page.evaluate("""() => { window.__gaps = []; let last = performance.now(); const tick = (t) => { window.__gaps.push(t - last); last = t; if (window.__run) requestAnimationFrame(tick); }; window.__run = true; requestAnimationFrame(tick); }""")
        for _ in range(10):
            await page.keyboard.press('ArrowRight'); await page.wait_for_timeout(120)
        for _ in range(4):
            await page.keyboard.press('ArrowDown'); await page.wait_for_timeout(160)
        await page.wait_for_timeout(900)
        gaps = await page.evaluate("() => { window.__run = false; return window.__gaps.slice(1); }")
        await cdp.send('Emulation.setCPUThrottlingRate', {'rate': 1})
        long = [g for g in gaps if g > 33]
        print('FRAMES', json.dumps({'throttle': THROTTLE, 'samples': len(gaps), 'over33ms': len(long), 'over50ms': len([g for g in gaps if g > 50]), 'p50': round(statistics.median(gaps), 1), 'p95': round(sorted(gaps)[int(len(gaps)*0.95)-1], 1), 'max': round(max(gaps), 1)}))
        focused = await page.evaluate("document.activeElement?.getAttribute('data-testid')")
        print('FOCUS_END', focused)
        await b.close()

asyncio.run(main())
