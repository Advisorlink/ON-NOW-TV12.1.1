"""A/B check: run the benchmark under 3 variants (CPU-throttled) and print the table.
Usage: /opt/plugins-venv/bin/python tests/perf_lab_ab.py [cpu_throttle=6]
"""
import asyncio, json, os, re, sys
from playwright.async_api import async_playwright

ENV = open('/app/frontend/.env').read()
BASE = os.environ.get('PERF_BASE') or re.search(r'REACT_APP_BACKEND_URL=(\S+)', ENV).group(1)
THROTTLE = float(sys.argv[1]) if len(sys.argv) > 1 else 6.0
SEED = open('/app/tests/perf_nav_probe.py').read().split('SEED = """')[1].split('"""')[0]
VARIANTS = [
    ['perf-reset'], [],
    ['perf-heroFollow-off', 'perf-shelfMotion-instant'], [],
    ['perf-layers-all', 'perf-recycle-off', 'perf-itemsPerRow-60', 'perf-heroFollow-on', 'perf-shelfMotion-flip'], [],
]

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={'width': 1920, 'height': 1080})
        page = await ctx.new_page()
        cdp = await ctx.new_cdp_session(page)
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.evaluate("localStorage.removeItem('vesper-perf-bench-results')")
        await cdp.send('Emulation.setCPUThrottlingRate', {'rate': THROTTLE})
        for clicks in VARIANTS:
            await page.evaluate("sessionStorage.setItem('vesper-settings-jump-to','performance')")
            await page.goto(f'{BASE}/settings', wait_until='domcontentloaded')
            await page.wait_for_selector('[data-testid="perf-bench-run"]', timeout=30000)
            for tid in clicks:
                await page.evaluate(f"document.querySelector('[data-testid=\"{tid}\"]').click()"); await page.wait_for_timeout(150)
            await page.evaluate("document.querySelector('[data-testid=\"perf-bench-run\"]').click()")
            await page.wait_for_selector('[data-testid="perf-bench-overlay"]', timeout=30000)
            await page.wait_for_selector('[data-testid="perf-bench-results"]', timeout=120000)
            await page.wait_for_timeout(500)
        rows = await page.evaluate("[...document.querySelectorAll('[data-testid^=perf-bench-row-]')].map(r=>[...r.children].map(c=>c.innerText).join(' | '))")
        print('\n'.join(rows))
        await page.screenshot(path='/app/tests/perf_lab_ab.png')
        await b.close()

asyncio.run(main())
