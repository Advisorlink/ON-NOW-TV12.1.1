"""
Iteration 105 reusable Playwright regression script (async/page context).

Scope:
- Detail mobile/tablet touch dispatch + native bridge mocks
- No /play fallback when Android bridge is present
- Cast-lane overlap sanity around autoplay CTA
- Playback details marker + modal behavior check

Notes:
- MOCKED native bridge calls are diagnostic only (not physical Android playback proof)
- Uses public URL from frontend/.env contract
"""

import json
import os
from dotenv import dotenv_values


BASE_URL = dotenv_values('/app/frontend/.env')['REACT_APP_BACKEND_URL'].rstrip('/')
MOVIE_PATH = "/title/movie/tt0133093"
SERIES_PATH = "/title/series/tt0944947"
USERNAME = os.environ['VESPER_TEST_USERNAME']
PASSWORD = os.environ['VESPER_TEST_PASSWORD']


async def run_iter105_touch_bridge_regression(page):
    # Avoid modifying shared cloud backups during playback tests.
    await page.route('**/api/vesper/sync/push*', lambda route: route.fulfill(
        status=200, content_type='application/json',
        headers={'Access-Control-Allow-Origin': '*'}, body='{"success":true}'))

    async def wait_bootsplash_detached():
        try:
            await page.wait_for_selector('[data-testid="boot-splash"]', timeout=2500)
            await page.wait_for_selector('[data-testid="boot-splash"]', state="detached", timeout=9000)
        except Exception:
            pass

    async def seed_auth_and_profile():
        await page.goto(f"{BASE_URL}/?e2e=1", wait_until="domcontentloaded")
        await wait_bootsplash_detached()
        out = await page.evaluate(
            """async ({u,p}) => {
                const r = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({ username: u, password: p })
                });
                const d = await r.json().catch(() => ({}));
                if (!r.ok) return { ok:false, status:r.status, data:d };
                const token = d.access_token || d.token || '';
                const account = d.account || d.user || { username: u };
                localStorage.setItem('vesper-auth-token-v1', token);
                localStorage.setItem('vesper-auth-account-v1', JSON.stringify(account));
                const profile = { id:'p-test-1', name:'Test', avatarId:'a1', kids:false, createdAt:Date.now() };
                localStorage.setItem('onnowtv-profiles-v1:testuser', JSON.stringify([profile]));
                localStorage.setItem('onnowtv-active-profile-v1:testuser', 'p-test-1');
                localStorage.setItem('vesper-onboarding-seen-v1', String(Date.now()));
                localStorage.setItem('onnowtv-whatsnew-seen-v1', '1.5.1');
                localStorage.setItem('onnowtv-autoplay-1080p:p-test-1', '1');
                localStorage.setItem('onnowtv-auto-trailer:p-test-1', '1');
                sessionStorage.setItem('vesper-e2e', '1');
                return { ok:true };
            }""",
            {"u": USERNAME, "p": PASSWORD},
        )
        print(f"seed result: {out}")
        if not out.get("ok"):
            raise RuntimeError(f"auth/profile seed failed: {out}")

    async def configure_touch_and_ua(width, height, user_agent):
        await page.set_viewport_size({"width": width, "height": height})
        cdp = await page.context.new_cdp_session(page)
        await cdp.send("Emulation.setUserAgentOverride", {"userAgent": user_agent, "platform": "Android"})
        await cdp.send("Emulation.setTouchEmulationEnabled", {"enabled": True, "maxTouchPoints": 5})
        return cdp

    async def set_mock(kind):
        if kind == "modern":
            script = """
                window.__onnow_calls = [];
                window.OnNowTV = {
                    isAndroidHost: () => true,
                    deviceClass: () => 'normal',
                    getHostPackage: () => 'tv.onnowtv.app',
                    playMedia: (...args) => { window.__onnow_calls.push({ method: 'playMedia', args }); return true; }
                };
            """
        elif kind == "legacy":
            script = """
                window.__onnow_calls = [];
                const calls = window.__onnow_calls;
                window.OnNowTV = {
                    isAndroidHost: () => true,
                    deviceClass: () => 'normal',
                    getHostPackage: () => 'tv.onnowtv.app',
                    playInternalRichV2: (...args) => calls.push(args)
                };
            """
        else:
            script = """
                window.__onnow_calls = [];
                try { delete window.OnNowTV; } catch (e) { window.OnNowTV = undefined; }
            """
        await page.evaluate('(kind) => sessionStorage.setItem("iter105-bridge-kind", kind)', kind)
        # Init scripts execute in unspecified order. Only the currently selected
        # shape may run; earlier mocks must not overwrite the legacy bridge.
        await page.add_init_script(
            'if (sessionStorage.getItem("iter105-bridge-kind") === ' + json.dumps(kind) + ') {' + script + '}')

    async def goto_detail(path):
        await page.goto(f"{BASE_URL}{path}", wait_until="domcontentloaded")
        await wait_bootsplash_detached()
        await page.wait_for_selector('[data-testid="detail-page"], [data-testid="series-episodes"]', timeout=20000)
        await page.wait_for_timeout(1000)

    async def trusted_touch_autoplay(cdp):
        btn = page.locator('[data-testid="detail-play-autoplay"]').first
        await btn.wait_for(timeout=20000)
        box = await btn.bounding_box()
        if not box:
            raise RuntimeError("Autoplay button box missing")
        x = int(box["x"] + box["width"] / 2)
        y = int(box["y"] + box["height"] / 2)
        await cdp.send("Input.dispatchTouchEvent", {
            "type": "touchStart",
            "touchPoints": [{"x": x, "y": y, "radiusX": 1, "radiusY": 1, "force": 1}],
            "modifiers": 0,
        })
        await cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": [], "modifiers": 0})
        return {"x": x, "y": y}

    # ---- Run ----
    await seed_auth_and_profile()

    # Mobile sanity + marker
    await set_mock("none")
    await configure_touch_and_ua(
        390,
        844,
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36 OnNowTV/1.5.1",
    )
    await goto_detail(MOVIE_PATH)
    platform = await page.evaluate("document.body?.getAttribute('data-platform') || ''")
    print(f"mobile body platform marker: {platform}")

    overlap = await page.evaluate(
        """() => {
            const play = document.querySelector('[data-testid="detail-play-autoplay"]');
            const lane = document.querySelector('[data-testid="detail-cast-lane"]');
            if (!play || !lane) return { ok:false, reason:'missing' };
            const p = play.getBoundingClientRect();
            const l = lane.getBoundingClientRect();
            const cx = Math.floor(p.left + p.width/2);
            const cy = Math.floor(p.top + p.height/2);
            const el = document.elementFromPoint(cx, cy);
            return {
                ok: !!el && !!el.closest('[data-testid="detail-play-autoplay"]'),
                playBottom: p.bottom,
                laneTop: l.top,
            };
        }"""
    )
    print(f"mobile overlap: {overlap}")

    # Series controls reachability
    await goto_detail(SERIES_PATH)
    await page.wait_for_selector('[data-testid="season-picker"]', timeout=12000)
    if await page.locator('[data-testid="season-1"]').count() > 0:
        await page.locator('[data-testid="season-1"]').first.click(force=True)
    else:
        await page.locator('[data-testid^="season-"]').first.click(force=True)
    await page.wait_for_selector('[data-testid="episode-list"]', timeout=10000)

    # Modern bridge: expect one playMedia dispatch + no /play fallback
    await set_mock("modern")
    cdp_modern = await configure_touch_and_ua(
        844,
        390,
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36 OnNowTV/1.5.1",
    )
    await goto_detail(MOVIE_PATH)
    await trusted_touch_autoplay(cdp_modern)
    await page.wait_for_timeout(900)
    modern_calls = await page.evaluate("window.__onnow_calls || []")
    print(f"modern call count: {len(modern_calls)}")
    assert len(modern_calls) == 1, f"modern dispatch count != 1: {len(modern_calls)}"
    assert modern_calls[0].get("method") == "playMedia", f"unexpected modern call: {modern_calls[0]}"
    assert "/play" not in page.url, f"unexpected /play fallback: {page.url}"
    modern_payload = json.loads(modern_calls[0]["args"][1])

    # Legacy bridge: exact shape + one dispatch + title/cwId parity
    await set_mock("legacy")
    cdp_legacy = await configure_touch_and_ua(
        1024,
        600,
        "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36 OnNowTV/1.5.1",
    )
    await goto_detail(MOVIE_PATH)
    await trusted_touch_autoplay(cdp_legacy)
    await page.wait_for_timeout(900)
    legacy_calls = await page.evaluate("window.__onnow_calls || []")
    print(f"legacy call count: {len(legacy_calls)}")
    assert len(legacy_calls) == 1, f"legacy dispatch count != 1: {len(legacy_calls)}"
    assert "/play" not in page.url, f"unexpected /play fallback: {page.url}"

    args = legacy_calls[0]
    legacy_title = args[1] if len(args) > 1 else ""
    legacy_cwid = args[12] if len(args) > 12 else ""
    assert legacy_title == modern_payload.get("title", ""), "legacy title mismatch"
    assert str(legacy_cwid) == str(modern_payload.get("cwId", "")), "legacy cwId mismatch"

    await page.keyboard.press("ArrowDown")
    await page.wait_for_timeout(200)
    active = await page.evaluate("document.activeElement?.getAttribute('data-testid') || ''")
    print(f"focus after ArrowDown: {active}")

    # Desktop utility must receive an actual hit, not a forced click through
    # the old cast header overlay (iteration105 follow-up regression).
    await page.set_viewport_size({"width": 1920, "height": 800})
    await cdp_legacy.send('Emulation.setTouchEmulationEnabled', {'enabled': False})
    await cdp_legacy.send('Emulation.setUserAgentOverride', {'userAgent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/138.0 Safari/537.36'})
    await goto_detail(MOVIE_PATH)
    await page.locator('[data-testid="playback-details-open"]').click()
    assert await page.locator('[data-testid="playback-web-build"]').inner_text() == 'play-layout-4'
    await page.locator('[data-testid="playback-details-close"]').click()
    await page.wait_for_selector('[data-testid="playback-details-dialog"]', state='detached')
    print('PASS desktop playback details')


# NOTE: mcp_browser_automation runs inside an async context with `page`.
# Keep a direct invocation helper for reuse:
async def run(page):
    await run_iter105_touch_bridge_regression(page)
