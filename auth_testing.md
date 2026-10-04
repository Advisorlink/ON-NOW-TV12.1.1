# Auth Testing Playbook — Vesper v2

## 2026-10-04: native Live TV recovery
Existing AuthStore/provider preserved; playbook consulted, no new auth service or credentials. Verify loader Retry + Log in again and crash-screen recovery. Reset runs in MAIN process, clears task/credentials/provider caches but preserves favourites/collections/preferences. Retained loader children cancel, old progress/navigation ignored, session-generation guards reject old cache publishers. CrashActivity already uses :crash; skip ordinary player/EPG initialization there. Verify cache-promotion rollback with JVM tests and Kotlin syntax/type checks. No emulator means no claim of device E2E success or diagnosis of the user's unidentified crash. Only a real photo/log can confirm that crash cause. Do not use browser-cookie tests for native provider credentials.

## 2026-10-04: session-readiness UI regression
Integration playbook consulted. This change preserves the existing username/Bearer-token contract; no account, password, hashing, cookie, schema or provider changes. Historical notes below predate the `vesper_accounts` rename; use memory/test_credentials.md as current source of truth and the external URL from frontend/.env for all tests.

- Guest: no update notice before successful sign-in.
- Valid cached token: notice waits for /me verification and boot splash dismissal.
- Delayed rejected cached token: no early popup; a subsequent successful sign-in shows one.
- Cloud profile restore: release notes wait for Restore/Start fresh to resolve; do not stack dialogs or erase test profile backups.
- Dismissed release: route/profile changes, logout/login and reload do not duplicate it. Session claim is versioned and acquired synchronously on presentation (not on scheduling).
- Old response after logout/token replacement cannot mark a different session verified. Same-value React state updates do not trigger a second transition.
- Login failures retain existing behaviour. Existing credentials only; do not create users or test lockouts against the shared test account.

## Historical authentication checks

## Step 1: MongoDB Verification

```
mongosh
use <database_name>
db.xtream_accounts.find().limit(3).pretty()
db.xtream_accounts.count()
```

Verify:
- Each row has `dns`, `username`, `password` (plaintext — must remain plaintext since these are real Xtream Codes IPTV credentials that we forward to the IPTV server's `player_api.php`), `label`, `status`, `created_at`.
- Index exists on `xtream_accounts.username` (unique).
- Index exists on `login_attempts.identifier`.

## Step 2: API Testing

Login (LOCALHOST):
```
curl -X POST http://localhost:8001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"USER_FROM_SEED","password":"PASS_FROM_SEED"}'
```

Expected: `200` + JSON `{access_token, token_type:"bearer", account:{username, label, expires_at, status}}`.

Get current session:
```
TOKEN=...  # from login response
curl http://localhost:8001/api/auth/me -H "Authorization: Bearer $TOKEN"
```

Expected: `200` + the same `account` object as login.

Logout:
```
curl -X POST http://localhost:8001/api/auth/logout -H "Authorization: Bearer $TOKEN"
```

Admin endpoints (require `X-Admin-Key` header matching `ADMIN_KEY` env var):
```
curl http://localhost:8001/api/admin/accounts -H "X-Admin-Key: $ADMIN_KEY"
curl -X POST http://localhost:8001/api/admin/accounts \
  -H "X-Admin-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"dns":"http://example.com:8080","username":"new","password":"p","label":"New customer"}'
```

## Step 3: Brute Force Protection

5 wrong attempts on the same username should yield `429` for 15 min.
```
for i in 1 2 3 4 5 6; do
  curl -X POST http://localhost:8001/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"someuser","password":"wrong"}'
done
```

The 6th call should return `429`.

## Step 4: Frontend

- App without a valid JWT in `localStorage` (key: `vesper-auth-token-v1`) renders `<LoginScreen />` instead of the rest of the app.
- Successful login stores `vesper-auth-token-v1` in localStorage + navigates to `/profiles`.
- Wrong password shows inline error.
- "Sign out" in Settings clears the token and returns to the login screen.
