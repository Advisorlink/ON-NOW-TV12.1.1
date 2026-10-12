"""Focus a Home poster, wait for the trailer to play, expect the stream-count verdict badge."""
import asyncio, sys
sys.path.insert(0, '/app/tests'); from lib_seed import *
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        page = await b.new_page(viewport={'width': 1920, 'height': 1080})
        # Emulate the TV box so the preview uses a plain <video> we can poke.
        await page.add_init_script("window.OnNowTV = window.OnNowTV || {};")
        errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
        # googlevideo URLs are IP-bound (403 off-box) → hold the request open so the <video> stays mounted.
        async def hold(route): await asyncio.sleep(120)
        await page.route('**googlevideo.com/**', hold)
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000); await page.wait_for_timeout(5000)
        tile = await page.query_selector('[data-testid="shelf-row-com.linvo.cinemeta-movie-top"] [data-testid^="poster-"]')
        await tile.focus(); await page.keyboard.press('ArrowRight'); await page.wait_for_timeout(300)
        found = False
        for i in range(30):
            await page.wait_for_timeout(1000)
            # Headless Chromium may lack the codec: fake the 'playing' signal once the <video> exists.
            await page.evaluate("(()=>{const v=document.querySelector('[data-testid=trailer-hover-video]'); if(v && !v.__poked){v.__poked=1; v.dispatchEvent(new Event('playing'));}})()")
            badge = await page.evaluate("(()=>{const b=document.querySelector('[data-testid=preview-stream-check]'); return b? {status:b.dataset.status,count:b.dataset.count,text:b.innerText}:null})()")
            playing = await page.evaluate("!!document.querySelector('[data-testid=trailer-hover-preview] iframe, [data-testid=trailer-hover-video]')")
            if badge: print(i, 'media', playing, 'badge', badge); found = True
            if badge and badge['status'] == 'done': break
        print('badge found', found)
        await page.screenshot(path='/tmp/sc1.jpg', quality=40, type='jpeg')
        # move off → badge gone
        await page.keyboard.press('ArrowRight'); await page.wait_for_timeout(400)
        print('after move badge', await page.evaluate("!!document.querySelector('[data-testid=preview-stream-check]')"))
        print('errors', errs)
        await b.close()
asyncio.run(main())
