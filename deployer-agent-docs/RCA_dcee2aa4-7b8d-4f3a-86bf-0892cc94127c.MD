# RCA — GORILLA DIRECT still returns [] on onnowhub.com

## Problem statement
After deploying a fix to `/app/backend/vod_test_provider.py` (`_allowed()` now reads the JWT `username` claim, e.g. TRAV26, instead of looking it up in the empty `vesper_accounts` collection), `GET /api/vod/test-direct?type=movie&id=tt1517268` on **onnowhub.com** still returns `{"streams":[]}`. User asks: (1) is the new code live behind onnowhub.com, (2) are the VOD_TEST_* secrets set there, (3) which deployment/DB does onnowhub.com route to, and whether deploy job `6af57209-07c7-4d48-aa55-416a765a6731` finished.

## What this agent is scoped to
This agent is scoped (server-side, via X-Deploy-Job-ID) to deployment **`rebrand-app-5`** (`d7778c47-823d-43d9-8fac-3917c65b0a91`), default URL **https://rebrand-app-5.emergent.host** — the "ON NOW TV V2" app, Cloudflare frontend, tier_0.

## Evidence

### 1. onnowhub.com is NOT this deployment
- `list_domains` and `check_domain_status` on this deployment both return an **empty domain list**. No custom domain (including onnowhub.com) is attached to `rebrand-app-5`. Its only URL is the default `rebrand-app-5.emergent.host`.
- User-observed behavioural proof of divergence: `POST /api/auth/login` with TRAV26/TRAV26 **succeeds on onnowhub.com** (JWT sub=va_956c765af1be4735, username=TRAV26) but **fails on rebrand-app-5.emergent.host** with "Invalid username or password". Different auth result ⇒ **different database**. onnowhub.com is a separate deployment/instance with its own Mongo (one where TRAV26 exists); rebrand-app-5's DB has an empty/different `vesper_accounts` (matches the earlier RCA that found it empty).

### 2. The job ID referenced does not belong to this deployment
- Every run in this deployment's history carries `job_id = f5950344-6ef3-4470-83bf-5f38190e7141`. The job the user quoted, **`6af57209-07c7-4d48-aa55-416a765a6731`, appears on none of this deployment's runs.** I cannot inspect that job — it is not scoped to this agent.

### 3. State of THIS deployment (rebrand-app-5)
- Most recent run `dcee2aa4-7b8d-4f3a-86bf-0892cc94127c` (type redeploy, job f5950344): **status success, completed 2026-10-31 13:28:20 UTC** — finished and live. `preview_health_check` on `/health` → **200**, serving the real "ON NOW TV V2" app (not nginx default).
- Secrets on the active run: `VOD_TEST_BASE`, `VOD_TEST_USERNAME`, `VOD_TEST_PASSWORD`, `VOD_TEST_ACCOUNTS`, `VOD_TEST_LABEL`, `JWT_SECRET` are all present and **non-empty** here.
- **Code version UNVERIFIED on this deployment:** the ephemeral sandbox never received the source (`/app/scratch` stayed empty; the source archive exceeds the sandbox memory limit and the load terminated). I therefore could not open `vod_test_provider.py` to confirm the `_allowed()` JWT-username change is in this image.

## Root cause
The fix and the deploy job you are tracking do not apply to the deployment that actually serves **onnowhub.com**. onnowhub.com is a **separate deployment with a separate database** (proven by the TRAV26 login succeeding there but failing on rebrand-app-5, and by rebrand-app-5 having zero custom domains). Job `6af57209-...` is not a run of `rebrand-app-5`. So even a correct fix landing on `rebrand-app-5` would not change onnowhub.com's behaviour — and I have no scope to inspect onnowhub.com's code version, secrets, or DB from here.

## Answers to the three questions
1. **Is the new code live behind onnowhub.com?** Cannot be determined from this agent — onnowhub.com is not this deployment and is out of scope. On `rebrand-app-5` the latest deploy is live, but the `_allowed()` change itself is UNVERIFIED (sandbox source load failed).
2. **Are VOD_TEST_* set on that same deployment?** On `rebrand-app-5` yes — all five VOD_TEST_* are set and non-empty. For the deployment behind onnowhub.com, unknown (out of scope).
3. **Which deployment/DB does onnowhub.com route to?** Not `rebrand-app-5` (no domain link, different DB). It is a distinct deployment that this agent is not scoped to.

## Suggested fixes
- Identify the deployment that actually serves onnowhub.com (its Deployment panel will list onnowhub.com under Domains and will have its own job ID). Confirm job `6af57209-...` belongs to THAT deployment, deploy the `vod_test_provider.py` fix there, and verify `VOD_TEST_*` are set on that deployment. Re-run the deployer scoped to that deployment to verify the live code and secrets.
- If onnowhub.com was intended to point at `rebrand-app-5`, attach it in that deployment's Domains tab — but note the two instances currently use different databases, so account/catalogue data will differ.
