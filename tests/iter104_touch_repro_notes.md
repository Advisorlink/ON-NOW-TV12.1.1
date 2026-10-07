# Iteration 104 — Touch blocker reproduction recipe (no app code changes)

## Scope
- Public URL only: `REACT_APP_BACKEND_URL`
- Title: Matrix `tt0133093`
- Repro focus: trusted CDP touch on Detail autoplay/play CTA across portrait/landscape.

## Backend smoke used before UI run
- `pytest /app/backend/tests/test_iter104_metadata_streams_smoke.py -v --junitxml=/app/test_reports/pytest/iter104_metadata_streams_smoke.xml`

## UI reproduction essentials
1. Login via `/api/auth/login` with `testuser/testpass123`.
2. Seed `vesper-auth-token-v1`, `vesper-auth-account-v1`, profile keys, and `sessionStorage['vesper-e2e']='1'`.
3. Force `onnowtv-autoplay-1080p:p-test-1=1` and `onnowtv-auto-trailer:p-test-1=1`.
4. Open `/title/movie/tt0133093`.
5. Read geometry for:
   - `[data-testid="detail-play-autoplay"]`
   - `[data-testid="detail-cast-lane"]`
   - `document.elementFromPoint(buttonCenterX, buttonCenterY)`
6. Dispatch trusted touch through CDP:
   - `Input.dispatchTouchEvent(type='touchStart'...)`
   - `Input.dispatchTouchEvent(type='touchEnd'...)`
7. Record whether events hit button vs cast lane and whether URL moves to `/play`.

## Observed blocker dimensions
- **Pass**: 390x844, 1280x800 (button receives pointer/click, transitions to `/play`)
- **Fail**: 844x390, 1024x600 (center hit resolves to cast-lane subtree; lane gets pointer/click; URL stays on detail)

## MOCKED bridge verification (for diagnosis only)
- Modern mock: `OnNowTV.playMedia`
- Legacy mock: `OnNowTV.playInternalRichV2`
- Result: in failing landscape sizes, **zero native bridge calls** because touch never reaches CTA.
