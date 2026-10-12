"""E2E: Performance-lab toggles take effect + benchmark produces a scored row.
Usage: /opt/plugins-venv/bin/python tests/perf_lab_e2e.py [cpu_throttle=4]
"""
import asyncio, json, re, sys
from playwright.async_api import async_playwright

ENV = open('/app/frontend/.env').read()
BASE = re.search(r'REACT_APP_BACKEND_URL=(\S+)', ENV).group(1)
THROTTLE = float(sys.argv[1]) if len(sys.argv) > 1 else 4.0
SEED = open('/app/tests/perf_nav_probe.py').read().split('SEED = """')[1].split('"""')[0]

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={'width': 1920, 'height': 1080})
        page = await ctx.new_page()
        cdp = await ctx.new_cdp_session(page)
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000)
        await page.wait_for_timeout(4000)
        # Baseline poster src + titles count
        src0 = await page.evaluate("document.querySelector('.vesper-shelf img')?.src")
        count0 = await page.evaluate("[...document.querySelectorAll('header span.vesper-mono')].map(e=>e.textContent).filter(t=>/titles/.test(t))")
        print('BASE poster', src0); print('BASE counts', count0)

        # Toggle posterSize small + itemsPerRow 18 + shelfMotion instant from Settings
        await page.evaluate("sessionStorage.setItem('vesper-settings-jump-to','performance')")
        await page.goto(f'{BASE}/settings', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid="performance-lab"]', timeout=20000)
        for tid in ['perf-posterSize-small', 'perf-itemsPerRow-18', 'perf-shelfMotion-instant', 'perf-hud-on']:
            await page.click(f'[data-testid="{tid}"]', force=True); await page.wait_for_timeout(200)
        print('ACTIVE', await page.evaluate("[...document.querySelectorAll('[data-testid^=perf-active-]')].map(e=>e.textContent)"))
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000)
        await page.wait_for_timeout(4000)
        src1 = await page.evaluate("document.querySelector('.vesper-shelf img')?.src")
        count1 = await page.evaluate("[...document.querySelectorAll('header span.vesper-mono')].map(e=>e.textContent).filter(t=>/titles/.test(t))")
        print('SMALL poster', src1); print('18 counts', count1)
        assert src1 != src0 and ('w185' in src1 or '/small/' in src1), 'poster size toggle had no effect'
        assert all(c.startswith('18 ') for c in count1 if 'titles' in c), 'itemsPerRow 18 had no effect'
        # HUD visible with latency readout after presses
        first = await page.query_selector('[data-testid="shelves-region"] [data-focusable="true"]')
        await first.focus()
        for _ in range(5):
            await page.keyboard.press('ArrowRight'); await page.wait_for_timeout(150)
        hud = await page.evaluate("document.querySelector('[data-testid=perf-hud]')?.innerText")
        print('HUD', hud)
        assert hud and 'ms / press' in hud, 'HUD missing latency'
        anims = await page.evaluate("document.getAnimations().filter(a=>a.playState==='running').length")
        print('running animations (instant mode):', anims)

        # Benchmark with throttle
        await cdp.send('Emulation.setCPUThrottlingRate', {'rate': THROTTLE})
        await page.evaluate("sessionStorage.setItem('vesper-settings-jump-to','performance')")
        await page.goto(f'{BASE}/settings', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid="perf-bench-run"]', timeout=20000)
        await page.click('[data-testid="perf-bench-run"]', force=True)
        await page.wait_for_selector('[data-testid="perf-bench-overlay"]', timeout=20000)
        print('overlay shown on', page.url)
        await page.wait_for_selector('[data-testid="perf-bench-results"]', timeout=90000)
        rows = await page.evaluate("[...document.querySelectorAll('[data-testid^=perf-bench-row-]')].map(r=>r.innerText.replace(/\\t/g,' | '))")
        print('RESULTS', json.dumps(rows, indent=1))
        assert rows and ' ms' in rows[0], 'no benchmark row'
        await page.screenshot(path='/app/tests/perf_lab_bench.png')
        await b.close()
        print('PASS')

asyncio.run(main())
