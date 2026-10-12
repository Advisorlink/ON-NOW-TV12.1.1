"""CPU profile of D-pad navigation on Home (android mode).  Prints top self-time functions."""
import asyncio, json, re, sys, collections
from playwright.async_api import async_playwright
sys.path.insert(0, '/app/tests')
from perf_nav_probe import SEED, STUB, BASE

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={'width': 1920, 'height': 1080})
        page = await ctx.new_page(); await page.add_init_script(STUB)
        cdp = await ctx.new_cdp_session(page)
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000)
        await page.wait_for_timeout(6000)
        await page.locator('[data-testid^="shelf-row-"] [data-testid^="poster-"]').first.focus()
        await page.wait_for_timeout(800)
        await cdp.send('Profiler.enable'); await cdp.send('Profiler.setSamplingInterval', {'interval': 200})
        await cdp.send('Profiler.start')
        for _ in range(10):
            await page.keyboard.press('ArrowRight'); await page.wait_for_timeout(120)
        for _ in range(4):
            await page.keyboard.press('ArrowDown'); await page.wait_for_timeout(160)
        await page.wait_for_timeout(700)
        prof = (await cdp.send('Profiler.stop'))['profile']
        nodes = {n['id']: n for n in prof['nodes']}
        self_us = collections.Counter(); total_us = collections.Counter()
        parent = {}
        for n in prof['nodes']:
            for c in n.get('children', []): parent[c] = n['id']
        deltas = prof['timeDeltas']; samples = prof['samples']
        for sid, dt in zip(samples, deltas):
            n = nodes[sid]; cf = n['callFrame']
            key = f"{cf['functionName'] or '(anon)'} {cf['url'].split('/')[-1].split('?')[0]}:{cf['lineNumber']}"
            self_us[key] += dt
            seen = set(); cur = sid
            while cur is not None and cur not in seen:
                seen.add(cur); c = nodes[cur]['callFrame']
                total_us[f"{c['functionName'] or '(anon)'} {c['url'].split('/')[-1].split('?')[0]}:{c['lineNumber']}"] += dt
                cur = parent.get(cur)
        tot = sum(deltas)
        print(f"profile {tot/1000:.0f} ms total")
        print("== top self time")
        for k, v in self_us.most_common(18): print(f"  {v/1000:7.1f} ms  {k}")
        print("== top inclusive (app code)")
        shown = 0
        for k, v in total_us.most_common(80):
            if '(root)' in k or '(program)' in k or '(idle)' in k or 'react-dom' in k or 'chunk' in k and 'node_modules' in k: continue
            print(f"  {v/1000:7.1f} ms  {k}"); shown += 1
            if shown >= 22: break
        await b.close()
asyncio.run(main())
