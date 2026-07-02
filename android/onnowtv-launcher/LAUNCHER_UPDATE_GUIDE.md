# ON NOW Launcher — Self-Update: How it works & fleet setup

## What was broken
The launcher is the **Home app** and was updating itself by
**uninstall → reinstall**. That's fatal for a Home app:

- **"Shows the old launcher again"** — `pm install` swapped the APK on
  disk, but the **old launcher process kept running in memory**, so the
  version string stayed old. Nothing restarted it.
- **"Goes back to stock launcher / lost everything"** — `pm uninstall
  tv.onnow.launcher` kills the launcher process *and* the root shell
  that was meant to run the reinstall → reinstall never happens → box
  falls back to the stock Android home, profiles gone.

## The fix (v2.12.11) — in-place update, never uninstall
Self-update now does:

1. Copy the new APK to `/data/local/tmp/` (survives anything).
2. **`pm install -r`** — in-place upgrade. **Keeps all data/profiles.**
   The launcher package is **never removed**, so the box can never
   drop to the stock launcher.
3. `am force-stop tv.onnow.launcher` — kills the old running code.
4. Relaunch → the **new** code loads and the new version shows.

Because the launcher is the HOME app, even if the detached updater
shell is killed at force-stop, Android automatically relaunches HOME
and cold-starts the new APK — **the update self-heals**.

## Why this is safe for your fleet (verified)
- **Same signing key every build:** the repo commits a stable keystore
  (`app/onnow-launcher-debug.keystore`, v1+v2+v3 signing on). So
  `pm install -r` never hits a signature mismatch. ✅
- **versionCode always increases:** CI sets it to `1 + run number`
  (`.github/workflows/build-launcher.yml`). So an in-place update is
  always accepted — never a silent "same version" no-op. ✅

> ⚠️ Keep it that way: never change the keystore, and never reset the
> CI run counter. If you ever must rotate the key, that one build needs
> a manual uninstall+reinstall on each box (signature change).

## Superuser prompt — how to make it stop asking
The launcher now uses **one persistent root shell per boot**, so at
most **one** prompt per launcher start. To make it **never** prompt:

### Magisk (most common)
Magisk app → **Superuser** → find **ON NOW TV V2** →
set to **Grant** (toggle on). Then tap the ⚙ next to it and make sure
it is **not** set to a timeout. Done once per box → zero prompts ever.

### Provisioning tip
When you first sideload the launcher on a client box, open the update
once so Magisk shows the prompt, tap **Grant**, and confirm the toggle
stays on. From then on updates are silent.

## Future: fully silent, zero-prompt updates (Option B)
For a large fleet, the professional end-state is to remove root from
the equation entirely, one-time per box at setup:

- **Device Owner:** `adb shell dpm set-device-owner
  tv.onnow.launcher/.YourAdminReceiver` on a freshly-provisioned box.
  Then the launcher can install/update APKs **silently** with no root
  and no prompt (uses PackageInstaller under device-owner privilege).
- **or Privileged system app:** place the launcher in
  `/system/priv-app/` with `android.permission.INSTALL_PACKAGES`.

Both need one setup command per box (which you already touch when you
sideload). Ask the agent to wire this up when you're ready.
