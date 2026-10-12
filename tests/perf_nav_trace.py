"""Chrome trace breakdown of D-pad presses on Home: where does each press spend main-thread time?
Usage: /opt/plugins-venv/bin/python tests/perf_nav_trace.py [cpu_throttle=6] [perf_json]
  perf_json: optional JSON for localStorage 'vesper-perf-lab-v1', e.g. '{"shelfMotion":"instant"}'
"""
import asyncio, json, re, sys, collections
from playwright.async_api import async_playwright
sys.path.insert(0, '/app/tests')
from perf_nav_probe import SEED, STUB, BASE

THROTTLE = float(sys.argv[1]) if len(sys.argv) > 1 else 6.0
PERF = sys.argv[2] if len(sys.argv) > 2 else '{}'
PRESSES = ['ArrowRight'] * 10 + ['ArrowDown'] * 2 + ['ArrowRight'] * 6

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={'width': 1920, 'height': 1080})
        page = await ctx.new_page(); await page.add_init_script(STUB)
        cdp = await ctx.new_cdp_session(page)
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.evaluate(f"localStorage.setItem('vesper-perf-lab-v1', JSON.stringify({PERF}))")
        if 'notrailer' in sys.argv: await page.evaluate("localStorage.setItem('onnowtv-auto-trailer:p-test-1', '0')")
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000)
        await page.wait_for_timeout(6000)
        first = await page.query_selector('[data-testid="shelves-region"] [data-focusable="true"]')
        await first.focus(); await page.wait_for_timeout(800)
        await cdp.send('Emulation.setCPUThrottlingRate', {'rate': THROTTLE})
        events = []
        cdp.on('Tracing.dataCollected', lambda e: events.extend(e['value']))
        done = asyncio.get_event_loop().create_future()
        cdp.on('Tracing.tracingComplete', lambda e: done.set_result(True))
        await cdp.send('Tracing.start', {'categories': 'devtools.timeline,disabled-by-default-devtools.timeline', 'transferMode': 'ReportEvents'})
        for k in PRESSES:
            await page.keyboard.press(k); await page.wait_for_timeout(250)
        await page.wait_for_timeout(500)
        await cdp.send('Tracing.end'); await done
        by = collections.Counter(); cnt = collections.Counter()
        for e in events:
            if e.get('ph') != 'X' or 'dur' not in e: continue
            n = e['name']
            if n in ('UpdateLayoutTree', 'Layout', 'Paint', 'PrePaint', 'Layerize', 'Commit', 'FunctionCall', 'EventDispatch', 'HitTest', 'Decode Image', 'ImageDecodeTask', 'UpdateLayer', 'CompositeLayers', 'ScheduleStyleRecalculation', 'TimerFire', 'FireAnimationFrame', 'RunMicrotasks', 'GCEvent', 'MinorGC', 'MajorGC', 'ParseHTML', 'EvaluateScript', 'v8.compile'):
                by[n] += e['dur'] / 1000; cnt[n] += 1
        n = len(PRESSES)
        print(f"perf={PERF} throttle={THROTTLE} presses={n}")
        for k, v in by.most_common():
            print(f"  {v:7.1f} ms total  {v/n:6.1f} ms/press  x{cnt[k]:<4} {k}")
        # Layout detail: how many nodes per layout?
        lay = [e for e in events if e.get('name') == 'Layout' and e.get('ph') == 'X']
        if lay:
            dirty = [e['args'].get('beginData', {}).get('dirtyObjects', 0) for e in lay]
            total = [e['args'].get('beginData', {}).get('totalObjects', 0) for e in lay]
            print(f"  layouts: {len(lay)}  avg dirty {sum(dirty)/len(lay):.0f}  avg total {sum(total)/len(lay):.0f}  max dur {max(e['dur'] for e in lay)/1000:.1f} ms")
        st = [e for e in events if e.get('name') == 'UpdateLayoutTree' and e.get('ph') == 'X']
        if st:
            el = [e['args'].get('elementCount', 0) for e in st]
            print(f"  style recalcs: {len(st)}  avg elements {sum(el)/len(st):.0f}  max {max(el)}  max dur {max(e['dur'] for e in st)/1000:.1f} ms")
        ev = collections.Counter(); evc = collections.Counter()
        for e in events:
            if e.get('name') == 'EventDispatch' and e.get('ph') == 'X':
                t = e['args'].get('data', {}).get('type', '?'); ev[t] += e['dur'] / 1000; evc[t] += 1
        print("  EventDispatch by type:", ', '.join(f"{k} {v:.0f}ms x{evc[k]}" for k, v in ev.most_common(8)))
        fc = collections.Counter(); fcc = collections.Counter()
        for e in events:
            if e.get('name') == 'FunctionCall' and e.get('ph') == 'X':
                d = e['args'].get('data', {}); k = f"{d.get('functionName') or '(anon)'}:{d.get('lineNumber')}"; fc[k] += e['dur'] / 1000; fcc[k] += 1
        print("  FunctionCall top:")
        for k, v in fc.most_common(10): print(f"     {v:7.1f} ms x{fcc[k]:<4} {k}")
        await b.close()

asyncio.run(main())
