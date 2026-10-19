import asyncio
import hashlib
import json
from pathlib import Path

from playwright.async_api import async_playwright, expect


def _read_env(path: Path, key: str) -> str:
    prefix = f"{key}="
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or not line.startswith(prefix):
            continue
        value = line.split("=", 1)[1].strip().strip('"').strip("'")
        if value:
            return value
    raise RuntimeError(f"Missing {key} in {path}")


async def _download_and_hash(page, save_path: Path) -> str:
    async with page.expect_download(timeout=120000) as dl_info:
        await page.get_by_test_id("download-apk-button").click()
    download = await dl_info.value
    await download.save_as(str(save_path))
    return hashlib.sha256(save_path.read_bytes()).hexdigest()


async def run() -> int:
    base_url = _read_env(Path("/app/frontend/.env"), "REACT_APP_BACKEND_URL").rstrip("/")
    token = _read_env(Path("/app/backend/.env"), "APK_DOWNLOAD_CAPABILITY_TOKEN")
    local_apk = Path("/app/android/vesper-native/app/build/outputs/apk/debug/app-debug.apk")
    if not local_apk.exists():
        raise RuntimeError("Local debug APK missing; assembleDebug before running this test")
    local_sha = hashlib.sha256(local_apk.read_bytes()).hexdigest()

    out_dir = Path("/app/test_reports/artifacts_iter127_download_browser")
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []

    async def step(name, fn):
        try:
            await fn()
            results.append({"step": name, "status": "PASS"})
            print(f"PASS: {name}")
        except Exception as exc:  # noqa: BLE001
            detail = str(exc).replace(token, "[redacted]")
            results.append({"step": name, "status": "FAIL", "error": detail})
            print(f"FAIL: {name} -> {detail}")

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)

        # Real-browser valid flow: click download, reload, click again, compare bytes to local APK.
        context = await browser.new_context(accept_downloads=True)
        page = await context.new_page()
        await page.set_viewport_size({"width": 1920, "height": 800})

        async def valid_click_and_reload_hash_match():
            await page.goto(f"{base_url}/api/download/vesper-native#{token}", wait_until="domcontentloaded")
            await page.wait_for_selector('[data-testid="download-apk-button"]', timeout=20000)
            assert not await page.get_by_test_id("download-apk-button").is_disabled()
            assert "#" not in page.url and "?" not in page.url

            first_sha = await _download_and_hash(page, out_dir / "download_first.apk")
            assert first_sha == local_sha

            await page.reload(wait_until="domcontentloaded")
            assert not await page.get_by_test_id("download-apk-button").is_disabled()
            await page.set_viewport_size({"width": 390, "height": 844})
            second_sha = await _download_and_hash(page, out_dir / "download_second.apk")
            assert second_sha == local_sha
            await page.goto(f"{base_url}/api/", wait_until="domcontentloaded")
            await page.go_back(wait_until="domcontentloaded")
            assert not await page.get_by_test_id("download-apk-button").is_disabled()
            assert "#" not in page.url

        await step("real_valid_download_before_and_after_reload", valid_click_and_reload_hash_match)

        async def no_cap_disabled_same_tab_and_new_tab():
            p1 = await context.new_page()
            await p1.goto(f"{base_url}/api/download/vesper-native", wait_until="domcontentloaded")
            await p1.wait_for_selector('[data-testid="download-apk-button"]', timeout=15000)
            assert await p1.get_by_test_id("download-apk-button").is_disabled()
            await p1.close()

            c2 = await browser.new_context(accept_downloads=True)
            p2 = await c2.new_page()
            await p2.goto(f"{base_url}/api/download/vesper-native", wait_until="domcontentloaded")
            await p2.wait_for_selector('[data-testid="download-apk-button"]', timeout=15000)
            assert await p2.get_by_test_id("download-apk-button").is_disabled()
            await c2.close()

        await step("no_cap_disabled_and_fresh_context_disabled", no_cap_disabled_same_tab_and_new_tab)

        async def invalid_token_404_clears_retained_access():
            invalid = "A" * 32
            p3 = await context.new_page()
            await p3.goto(f"{base_url}/api/download/vesper-native#{invalid}", wait_until="domcontentloaded")
            await p3.wait_for_selector('[data-testid="download-apk-button"]', timeout=15000)
            assert not await p3.get_by_test_id("download-apk-button").is_disabled()
            async with p3.expect_response(lambda r: r.url.endswith('/api/download/vesper-native-apk')) as response_info:
                await p3.get_by_test_id("download-apk-button").click()
            assert (await response_info.value).status == 404
            await expect(p3.get_by_test_id("download-status")).to_contain_text("Download unavailable")
            status = await p3.get_by_test_id("download-status").inner_text()
            assert "Download unavailable" in status
            retained = await p3.evaluate("() => sessionStorage.getItem('vesper-native-download-v1')")
            assert retained is None
            await p3.close()

        await step("invalid_or_revoked_404_forgets_access", invalid_token_404_clears_retained_access)

        async def newer_fragment_precedence_hashchange_restore():
            p4 = await context.new_page()
            await p4.goto(f"{base_url}/api/download/vesper-native#BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", wait_until="domcontentloaded")
            await p4.wait_for_timeout(200)
            await p4.evaluate("() => { location.hash = '#CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC'; }")
            await p4.wait_for_timeout(300)
            rec = await p4.evaluate("() => JSON.parse(sessionStorage.getItem('vesper-native-download-v1') || 'null')")
            assert rec and rec.get("token") == "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"

            await p4.evaluate("value => { location.hash = value; }", token)
            await p4.wait_for_timeout(300)
            assert not await p4.get_by_test_id("download-apk-button").is_disabled()
            sha = await _download_and_hash(p4, out_dir / "download_replaced_capability.apk")
            assert sha == local_sha
            await p4.close()

        await step("newer_fragment_precedence_and_hashchange_restore", newer_fragment_precedence_hashchange_restore)

        async def malformed_expired_storage_cleared_and_expired_memory_click_blocked():
            c3 = await browser.new_context(accept_downloads=True)
            p5 = await c3.new_page()
            await p5.goto(f"{base_url}/api/download/vesper-native", wait_until="domcontentloaded")
            await p5.evaluate(
                """() => {
                    sessionStorage.setItem('vesper-native-download-v1', '{bad-json');
                }"""
            )
            await p5.reload(wait_until="domcontentloaded")
            assert await p5.get_by_test_id("download-apk-button").is_disabled()

            await p5.evaluate(
                """() => {
                    sessionStorage.setItem('vesper-native-download-v1', JSON.stringify({
                        token: 'DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD',
                        expiresAt: Date.now() - 1000
                    }));
                }"""
            )
            await p5.reload(wait_until="domcontentloaded")
            assert await p5.get_by_test_id("download-apk-button").is_disabled()
            cleared = await p5.evaluate("() => sessionStorage.getItem('vesper-native-download-v1')")
            assert cleared is None

            await p5.goto(f"{base_url}/api/download/vesper-native#{token}", wait_until="domcontentloaded")
            await p5.evaluate("() => { const realNow = Date.now; Date.now = () => realNow() + (16 * 60 * 1000); }")
            await p5.get_by_test_id("download-apk-button").click()
            await p5.wait_for_timeout(300)
            txt = await p5.get_by_test_id("download-status").inner_text()
            assert "expired" in txt.lower()
            await c3.close()

        await step("malformed_or_expired_storage_and_expired_memory_click", malformed_expired_storage_cleared_and_expired_memory_click_blocked)

        async def unavailable_session_storage_still_allows_current_fragment_download():
            c4 = await browser.new_context(accept_downloads=True)
            await c4.add_init_script(
                """(() => {
                    const fail = () => { throw new Error('blocked'); };
                    Storage.prototype.setItem = fail;
                    Storage.prototype.getItem = fail;
                    Storage.prototype.removeItem = fail;
                })();"""
            )
            p6 = await c4.new_page()
            await p6.goto(f"{base_url}/api/download/vesper-native#{token}", wait_until="domcontentloaded")
            await p6.wait_for_selector('[data-testid="download-apk-button"]', timeout=15000)
            assert await p6.evaluate("() => { try { sessionStorage.getItem('probe'); return false; } catch (_) { return true; } }")
            assert not await p6.get_by_test_id("download-apk-button").is_disabled()
            sha = await _download_and_hash(p6, out_dir / "download_storage_blocked.apk")
            assert sha == local_sha
            await c4.close()

        await step("session_storage_unavailable_still_downloads_for_current_fragment", unavailable_session_storage_still_allows_current_fragment_download)

        async def transient_network_failure_retains_retry_and_pending_click_not_duplicated():
            c5 = await browser.new_context(accept_downloads=True)
            p7 = await c5.new_page()
            await p7.goto(f"{base_url}/api/download/vesper-native#{token}", wait_until="domcontentloaded")
            await p7.wait_for_selector('[data-testid="download-apk-button"]', timeout=15000)

            first_fail = {"done": False}
            async def fail_once(route):
                if route.request.url.endswith("/api/download/vesper-native-apk") and not first_fail["done"]:
                    first_fail["done"] = True
                    await route.abort()
                else:
                    await route.continue_()

            await p7.route("**/api/download/vesper-native-apk", fail_once)
            await p7.get_by_test_id("download-apk-button").click()
            await expect(p7.get_by_test_id("download-status")).to_contain_text("interrupted")
            status = await p7.get_by_test_id("download-status").inner_text()
            assert "interrupted" in status.lower()
            assert not await p7.get_by_test_id("download-apk-button").is_disabled()
            await p7.unroute("**/api/download/vesper-native-apk", fail_once)

            hit_counter = {"n": 0}
            async def slow_once(route):
                if route.request.url.endswith("/api/download/vesper-native-apk"):
                    hit_counter["n"] += 1
                    await asyncio.sleep(1.2)
                await route.continue_()

            await p7.route("**/api/download/vesper-native-apk", slow_once)
            dl_task = asyncio.create_task(_download_and_hash(p7, out_dir / "download_pending_once.apk"))
            await expect(p7.get_by_test_id("download-apk-button")).to_be_disabled()
            await p7.get_by_test_id("download-apk-button").dispatch_event("click")
            pending_sha = await dl_task
            assert pending_sha == local_sha
            assert hit_counter["n"] == 1
            await c5.close()

        await step("transient_network_failure_retry_and_pending_no_duplicate", transient_network_failure_retains_retry_and_pending_click_not_duplicated)

        await context.close()
        await browser.close()

    Path("/app/test_reports/artifacts_iter127_download_browser/result.json").write_text(
        json.dumps(results, indent=2), encoding="utf-8"
    )
    return 0 if all(r["status"] == "PASS" for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run()))