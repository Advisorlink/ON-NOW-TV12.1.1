# Shared login helper snippet (paste into browser_automation scripts)
B="https://rebrand-app-5.preview.emergentagent.com"
async def login():
    await page.goto(B+"/?e2e=1")
    try:
        await page.wait_for_selector('[data-testid="login-username-input"]', timeout=15000)
        await page.fill('[data-testid="login-username-input"]','testuser')
        await page.fill('[data-testid="login-password-input"]','testpass123')
        await page.keyboard.press("Enter"); await asyncio.sleep(4)
    except Exception as e: print("login form?", e)
    await page.evaluate("""() => {const p={id:'p-test-1',name:'Test',avatarId:'a1',kids:false,createdAt:Date.now()};
      localStorage.setItem('onnowtv-profiles-v1:testuser', JSON.stringify([p]));
      localStorage.setItem('onnowtv-active-profile-v1:testuser','p-test-1');}""")
    await page.goto(B+"/?e2e=1"); await asyncio.sleep(5)
    sk = page.get_by_role("button", name="Skip")
    if await sk.count(): await sk.first.click(); await asyncio.sleep(1)
    await page.wait_for_selector('[data-testid="top-nav"]', timeout=20000)
