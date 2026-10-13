"""What's new tour: auto-show on boot (new version), Settings entry, Next/Skip stepping, Back closes."""
import asyncio, sys
sys.path.insert(0, '/app/tests'); from lib_seed import *
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(); page = await b.new_page(viewport={'width': 1920, 'height': 1080})
        errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
        await page.goto(f'{BASE}/?e2e=1', wait_until='domcontentloaded'); await page.wait_for_timeout(1500)
        await page.evaluate(SEED)
        await page.evaluate("localStorage.removeItem('onnowtv-whatsnew-seen-v1'); sessionStorage.removeItem('vesper-release-presented')")
        await page.goto(f'{BASE}/', wait_until='domcontentloaded')
        try:
            await page.wait_for_selector('[data-testid="whats-new-modal"]', timeout=20000); auto = True
        except Exception: auto = False
        print('auto-shown on boot:', auto)
        if auto:
            await page.wait_for_timeout(600)
            await page.screenshot(path='/tmp/wn1.jpg', quality=45, type='jpeg')
            print('counter/version', await page.evaluate("document.querySelector('[data-testid=whats-new-version]').textContent")); print('step1', await page.evaluate("document.querySelector('[data-testid=whats-new-heading]').textContent"), await page.evaluate("document.querySelector('[data-testid=whats-new-step-counter]').textContent"))
            print('focused', await page.evaluate("document.activeElement.dataset.testid"))
            await page.screenshot(path='/tmp/wn_ch1.jpg', quality=45, type='jpeg')
            await page.keyboard.press('Enter'); await page.wait_for_timeout(500)
            await page.screenshot(path='/tmp/wn_ch2.jpg', quality=45, type='jpeg')
            await page.keyboard.press('Enter'); await page.wait_for_timeout(500)
            await page.keyboard.press('Enter'); await page.wait_for_timeout(500)
            print('step4', await page.evaluate("document.querySelector('[data-testid=whats-new-heading]').textContent"), await page.evaluate("document.querySelector('[data-testid=whats-new-step-counter]').textContent"))
            await page.screenshot(path='/tmp/wn2.jpg', quality=45, type='jpeg')
            await page.keyboard.press('ArrowLeft'); await page.wait_for_timeout(200)
            print('after left focused', await page.evaluate("document.activeElement.dataset.testid"))
            await page.keyboard.press('Enter'); await page.wait_for_timeout(600)
            print('skip closed', not await page.evaluate("!!document.querySelector('[data-testid=whats-new-modal]')"), 'seen', await page.evaluate("localStorage.getItem('onnowtv-whatsnew-seen-v1')"))
        # Settings entry
        await page.goto(f'{BASE}/settings', wait_until='domcontentloaded'); await page.wait_for_timeout(2500)
        await page.evaluate("document.querySelector('[data-testid=\"settings-nav-whats-new\"]')?.click() || [...document.querySelectorAll('button')].find(b=>/What.s new/.test(b.textContent))?.click()")
        await page.wait_for_timeout(600)
        print('settings section visible', await page.evaluate("!document.querySelector('[data-settings-section=whats-new]').hidden"))
        await page.screenshot(path='/tmp/wn3.jpg', quality=45, type='jpeg')
        await page.evaluate("document.querySelector('[data-testid=\"whats-new-open-1.5.2\"]').click()"); await page.wait_for_timeout(600)
        print('tour from settings', await page.evaluate("!!document.querySelector('[data-testid=whats-new-modal]')"))
        for _ in range(8):
            await page.keyboard.press('Enter'); await page.wait_for_timeout(250)
        print('after 8 Enter closed', not await page.evaluate("!!document.querySelector('[data-testid=whats-new-modal]')"), 'focus back', await page.evaluate("document.activeElement.dataset.testid"))
        print('errors', errs)
        await b.close()
asyncio.run(main())
