import asyncio, os, sys
sys.path.insert(0, '/app/tests')
os.environ.setdefault('VESPER_TEST_USERNAME', 'testuser'); os.environ.setdefault('VESPER_TEST_PASSWORD', 'testpass123')
from playwright.async_api import async_playwright
import iter105_touch_bridge_regression_playwright as t
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        page = await (await b.new_context()).new_page()
        await t.run(page)
        await b.close()
asyncio.run(main())
