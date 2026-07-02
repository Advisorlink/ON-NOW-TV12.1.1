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

## ✅ NEW: Fully silent, zero-prompt updates (Device Owner) — WIRED UP

The launcher is now **Device-Owner capable**. Once a box is set as
Device Owner (one-time at setup), **every install and update happens
completely silently**:

- ❌ NO superuser / root prompt (it never calls `su` in this mode)
- ❌ NO "install from unknown sources" warning
- ❌ NO system "Do you want to install?" confirmation
- ❌ NO scary "if you don't know what this does, don't do it" text
  (that text belongs to Magisk's root prompt — device owner skips
  root entirely, so it never appears)
- ✅ The customer sees **nothing** — the new launcher just appears.

### How it works internally
When the launcher is Device Owner, the update flow uses Android's
`PackageInstaller` with `USER_ACTION_NOT_REQUIRED`. The OS trusts a
device-owner app to install packages without asking. Self-updates are
in-place (data/profiles kept) and the launcher relaunches itself into
the new version automatically.

---

## 📋 EXACTLY what to do per box (one-time setup)

Do this ONCE on each box, at setup, BEFORE handing it to the client.
Takes ~2 minutes.

### Step 1 — Start from a clean box
Device Owner can only be set when the box has **no accounts** on it.
- Brand-new box: fine as-is.
- Box already in use: **Settings → Device Preferences → Reset →
  Factory reset**, then during setup **SKIP signing into any Google
  account** (choose "Skip"/"Set up later"). Do not add any account.

### Step 2 — Turn on ADB (developer mode) on the box
1. **Settings → Device Preferences → About**.
2. Scroll to **Build** and click it **7 times** ("You are now a
   developer").
3. Go back to **Settings → Device Preferences → Developer options**.
4. Turn ON **USB debugging** (and **Network debugging / ADB over
   Wi-Fi** if the box has no USB, e.g. many TV sticks).

### Step 3 — Connect your computer to the box with ADB
On your computer (with the Android platform-tools / `adb` installed):

- **Over USB:** plug the box into your computer, then:
  ```
  adb devices
  ```
  (accept the "Allow USB debugging?" prompt on the TV).

- **Over Wi-Fi:** find the box IP in Developer options (or
  Settings → Network), then:
  ```
  adb connect 192.168.1.XX:5555
  ```
  (replace with the box's IP).

### Step 4 — Install the launcher (if not already on the box)
```
adb install ON-NOW-LAUNCHER.apk
```

### Step 5 — Make the launcher the Device Owner  ← the magic command
```
adb shell dpm set-device-owner tv.onnow.launcher/.admin.OnNowDeviceAdminReceiver
```
You should see: **"Success: Device owner set to package tv.onnow.launcher"**.

That's it. From now on, every launcher update (and every app update)
on that box installs **silently** — no prompts of any kind.

### Step 6 — Finish setup and hand it over
Set ON NOW as the default Home if the box asks, then give it to your
client. Future updates: they click "Update", it installs, done. No
prompts, no confusion.

---

## Troubleshooting Step 5

| Message | Meaning / fix |
|---|---|
| `Success: Device owner set…` | 🎉 Done. |
| `Not allowed to set the device owner because there are already several users on the device` | An account/user exists → factory reset and skip account setup (Step 1). |
| `Neither user … nor current process has android.permission.MANAGE_DEVICE_ADMINS` | Run the exact command from Step 5 (it's `adb shell dpm …`, run from your computer). |
| `Unknown admin: ComponentInfo{…}` | The launcher APK isn't installed yet, or you typed the receiver name wrong. Re-run Step 4, then Step 5 exactly. |
| `java.lang.SecurityException … device owner cannot be set` on Sony/TCL/Hisense retail TVs | Some retail-firmware TVs block this. Use the root/Magisk path below on those boxes instead. |

---

## If a box CAN'T be a Device Owner (retail TVs / already-in-field)

The launcher still self-updates fine on rooted boxes via the in-place
root path (see top of this doc). To silence the root prompt there:

**Magisk** → **Superuser** → **ON NOW TV V2** → set to **Grant** and
make sure it's **not** on a timeout. One toggle, done once per box.

