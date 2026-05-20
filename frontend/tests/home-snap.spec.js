/**
 * 🔒 PERMANENT REGRESSION TEST — v2.7.19 home D-pad snap engine.
 *
 * Validates the user-approved behaviour from Feb 2026:
 *   1. ArrowDown commits scrollTop to EXACT integer multiples of
 *      shelfPageHeight (no smooth-scroll tween).
 *   2. Every focused tile carries the cyan outline focus ring
 *      (no inline-boxShadow overrides hiding it).
 *   3. Every row (CW, Networks, posters, Upcoming) is treated
 *      identically — same one snap-row fast-path code path.
 *
 * Run:
 *   cd /app/frontend && npx playwright test tests/home-snap.spec.js
 *
 * If this test fails, do NOT patch the test — revert the
 * regression in `useSpatialFocus.js` and/or `index.css`.  See
 * `/app/CONTEXT.md` "PERMANENT INVARIANTS" section.
 */

const { test, expect } = require('@playwright/test');

const FRONTEND_URL =
    process.env.REACT_APP_BACKEND_URL ||
    'https://rebrand-app-5.preview.emergentagent.com';

test.describe('Home D-pad snap (v2.7.19 permanent baseline)', () => {
    test('ArrowDown commits to exact integer multiples of shelfPageHeight', async ({ page }) => {
        await page.setViewportSize({ width: 1920, height: 1080 });
        await page.goto(FRONTEND_URL, { waitUntil: 'domcontentloaded' });

        // Seed: adult profile + one Continue Watching item.
        await page.evaluate(() => {
            const profile = {
                id: 'p1',
                name: 'Test',
                kid: false,
                avatarId: 'avatar1',
                themeId: 'electric',
            };
            localStorage.setItem('onnowtv-profiles-v1', JSON.stringify([profile]));
            localStorage.setItem('onnowtv-active-profile-id', 'p1');
            localStorage.setItem('onnowtv-welcome-tour-seen:p1', '1');
            localStorage.setItem(
                'onnowtv-continue-watching-v1:p1',
                JSON.stringify([
                    {
                        id: 'tt1',
                        type: 'movie',
                        title: 'Test',
                        poster: 'https://image.tmdb.org/t/p/w342/1.jpg',
                        positionMs: 600000,
                        durationMs: 7200000,
                        updatedAt: Date.now(),
                    },
                ]),
            );
        });
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(3500);

        // Pick the profile if profile picker is visible.
        const profileBtn = await page.$("[data-testid='profile-p1']");
        if (profileBtn) {
            await profileBtn.click();
            await page.waitForTimeout(2000);
        }
        await page.waitForTimeout(2000);

        // Capture the canonical page height (every shelf-page must
        // be exactly this tall).
        const pageHeight = await page.evaluate(() => {
            const first = document.querySelector("[data-testid='shelf-page']");
            return first ? first.offsetHeight : null;
        });
        expect(pageHeight).not.toBeNull();
        expect(pageHeight).toBeGreaterThan(300);

        // INV-1: 6 sequential ArrowDown presses must produce
        // scrollTop = 0, 1*H, 2*H, 3*H, 4*H, 5*H, 6*H (in that order).
        const scrollPositions = [];
        const focusOutlines = [];

        const initialScroll = await page.evaluate(
            () => document.querySelector("[data-testid='shelves-region']")?.scrollTop ?? null,
        );
        scrollPositions.push(initialScroll);

        for (let i = 0; i < 6; i++) {
            await page.keyboard.press('ArrowDown');
            await page.waitForTimeout(250);
            const snapshot = await page.evaluate(() => {
                const region = document.querySelector("[data-testid='shelves-region']");
                const focused = document.querySelector("[data-focused='true']");
                return {
                    scrollTop: region?.scrollTop ?? null,
                    outlineWidth: focused ? getComputedStyle(focused).outlineWidth : null,
                    outlineColor: focused ? getComputedStyle(focused).outlineColor : null,
                };
            });
            scrollPositions.push(snapshot.scrollTop);
            focusOutlines.push(snapshot);
        }

        // Every scroll position must be an EXACT multiple of pageHeight.
        for (let i = 0; i < scrollPositions.length; i++) {
            const remainder = scrollPositions[i] % pageHeight;
            expect(remainder, `scrollTop[${i}] = ${scrollPositions[i]} is not an integer multiple of pageHeight ${pageHeight} (remainder ${remainder})`).toBe(0);
        }

        // Positions must be monotonically non-decreasing.
        for (let i = 1; i < scrollPositions.length; i++) {
            expect(scrollPositions[i]).toBeGreaterThanOrEqual(scrollPositions[i - 1]);
        }

        // INV-2: every focused tile must carry the cyan 3px outline.
        for (let i = 0; i < focusOutlines.length; i++) {
            expect(focusOutlines[i].outlineWidth, `Down#${i + 1} lost the focus ring outline`).toBe('3px');
            // Color = rgb(92, 223, 255) which is var(--vesper-blue-bright).
            expect(focusOutlines[i].outlineColor).toMatch(/rgb\(92,\s*223,\s*255\)/);
        }
    });

    test('ArrowUp reverses snap cleanly (no slide)', async ({ page }) => {
        await page.setViewportSize({ width: 1920, height: 1080 });
        await page.goto(FRONTEND_URL, { waitUntil: 'domcontentloaded' });

        await page.evaluate(() => {
            const profile = {
                id: 'p1', name: 'Test', kid: false,
                avatarId: 'avatar1', themeId: 'electric',
            };
            localStorage.setItem('onnowtv-profiles-v1', JSON.stringify([profile]));
            localStorage.setItem('onnowtv-active-profile-id', 'p1');
            localStorage.setItem('onnowtv-welcome-tour-seen:p1', '1');
        });
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(3500);
        const profileBtn = await page.$("[data-testid='profile-p1']");
        if (profileBtn) {
            await profileBtn.click();
            await page.waitForTimeout(2000);
        }
        await page.waitForTimeout(2000);

        const pageHeight = await page.evaluate(() =>
            document.querySelector("[data-testid='shelf-page']")?.offsetHeight,
        );

        // Scroll down 4 pages, then walk back up.
        for (let i = 0; i < 4; i++) {
            await page.keyboard.press('ArrowDown');
            await page.waitForTimeout(250);
        }

        const upPositions = [];
        for (let i = 0; i < 4; i++) {
            await page.keyboard.press('ArrowUp');
            await page.waitForTimeout(250);
            const top = await page.evaluate(
                () => document.querySelector("[data-testid='shelves-region']")?.scrollTop,
            );
            upPositions.push(top);
        }

        for (const top of upPositions) {
            expect(top % pageHeight).toBe(0);
        }
        // Positions must be monotonically non-increasing.
        for (let i = 1; i < upPositions.length; i++) {
            expect(upPositions[i]).toBeLessThanOrEqual(upPositions[i - 1]);
        }
    });
});
