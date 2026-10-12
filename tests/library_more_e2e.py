import asyncio, re, json, sys
sys.path.insert(0,'/app/tests'); from lib_seed import *
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(); page = await b.new_page(viewport={'width':1920,'height':1080})
        errs=[]; page.on('pageerror', lambda e: errs.append(str(e)))
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.evaluate(f"localStorage.setItem('vesper-library:p-test-1', JSON.stringify({json.dumps(LIB)}))")
        # 1. Collection show more
        await page.goto(f'{BASE}/collections/warner-bros', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid="shelf-more-collection-warner-bros-movies"]', timeout=40000); await page.wait_for_timeout(1500)
        hdr = lambda: page.evaluate("document.querySelector('[data-testid=shelf-collection-warner-bros-movies] header span.vesper-mono').textContent")
        print('header', await hdr())
        more = await page.query_selector('[data-testid="shelf-more-collection-warner-bros-movies"]')
        await more.focus(); await page.wait_for_timeout(500)
        await page.screenshot(path='/tmp/more1.jpg', quality=40, type='jpeg')
        for i in range(4):
            m = await page.query_selector('[data-testid="shelf-more-collection-warner-bros-movies"]')
            if not m: print('no more tile'); break
            await m.focus(); await page.wait_for_timeout(200)
            await page.keyboard.press('Enter'); await page.wait_for_timeout(1800)
            print('after click', i, await hdr(), 'focused idx', await page.evaluate("(()=>{const c=[...document.querySelectorAll('[data-testid=shelf-row-collection-warner-bros-movies] > [data-shelf-cell]')]; return c.findIndex(x=>x.contains(document.activeElement))})()"), 'more label', await page.evaluate("document.querySelector('[data-testid=shelf-more-collection-warner-bros-movies]')?.innerText.replace(/\\n/g,' ')"))
        await page.screenshot(path='/tmp/more2.jpg', quality=40, type='jpeg')
        # 2. Home rows have show more
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid^="shelf-row-"]', timeout=30000); await page.wait_for_timeout(5000)
        print('home more tiles', await page.evaluate("[...document.querySelectorAll('[data-testid^=shelf-more-]')].map(e=>e.dataset.testid)"))
        # 3. preview-add event on a poster opens the modal
        await page.evaluate("const t=document.querySelector('[data-testid^=poster-]'); t.focus(); t.dispatchEvent(new CustomEvent('vesper:preview-add'))")
        await page.wait_for_timeout(1500)
        print('modal after preview-add', await page.evaluate("document.querySelector('[data-testid=add-to-list-modal]')?.innerText.split('\\n').slice(0,3)"))
        await page.keyboard.press('Escape'); await page.wait_for_timeout(500)
        # 4. Watch later long press → confirm
        await page.goto(f'{BASE}/library', wait_until='domcontentloaded')
        await page.wait_for_selector('[data-testid="watch-later-movie-tt0468569"]', timeout=20000); await page.wait_for_timeout(2000)
        t = await page.query_selector('[data-testid="watch-later-movie-tt0468569"]'); await t.focus()
        await page.keyboard.down('Enter'); await page.wait_for_timeout(900); await page.keyboard.up('Enter'); await page.wait_for_timeout(500)
        print('confirm visible', await page.evaluate("!!document.querySelector('[data-testid=watch-later-remove-confirm-movie-tt0468569]')"), 'url', page.url.split('/')[-1])
        await page.screenshot(path='/tmp/more3.jpg', quality=40, type='jpeg')
        # short press should play (navigate)
        await page.keyboard.press('Escape'); await page.wait_for_timeout(400)
        t = await page.query_selector('[data-testid="watch-later-movie-tt0468569"]'); await t.focus(); await page.keyboard.press('Enter'); await page.wait_for_timeout(1500)
        print('after short press url', page.url)
        print('errors', errs)
        await b.close()
asyncio.run(main())
