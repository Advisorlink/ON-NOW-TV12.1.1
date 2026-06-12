# Auth Testing Playbook — Vesper v2 (Xtream-credential login)

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
