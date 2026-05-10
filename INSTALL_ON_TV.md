# Get ON NOW TV V2 onto your HK1 box

> **❗ Required first**: install **VLC for Android** (or MX Player) on
> your HK1 box from the Play Store *before* you start watching.  The
> ON NOW TV V2 app hands video playback off to it for hardware
> decoding — that's how we get sound, smooth playback, and codec
> support that the WebView itself can't deliver.
>
> Open Play Store on the HK1 → search **"VLC"** → Install.  60 seconds,
> one-time.

You have **3 paths** ranked from fastest to most polished.  Pick whichever
suits — they're all using the same live web app, just wrapped differently.

---

## ⚡ Path 1 — TV browser (60 seconds, zero build)

Best for: testing the app right now without waiting for anything.

1. On the HK1 box, open the **Play Store** (or Aptoide TV)
2. Install **TV Bro** (free, designed for D-pad navigation)
   - Alternative: Puffin TV Browser, Smart TV Browser, Mises Browser
3. Open it, paste:  https://rebrand-app-5.preview.emergentagent.com
4. Bookmark the page

✅  Full ON NOW TV V2 — networks, episodes, subtitles, all of it.
⚠️  Audio won't autoplay until you click Play once (Chrome rule).

---

## 📱 Path 2 — Install as a Web App (PWA, ~2 minutes)

Best for: quasi-native feel without a real APK.  Looks and behaves like
an installed app, has its own home-screen icon, runs full-screen.

1. On HK1, open **Chrome** (preinstalled on most Android TV boxes)
2. Visit  https://rebrand-app-5.preview.emergentagent.com
3. Open the menu (⋮) → **Add to Home screen** *or* **Install app**
4. The HK1 launcher now has an "ON NOW TV V2" icon — launching it
   opens fullscreen with no browser chrome and no URL bar.

✅  Runs full-screen with our logo as the icon.
✅  Works offline for the shell (network needed for content).
⚠️  Same audio-autoplay caveat as Path 1.

---

## 🎯 Path 3 — Real APK via GitHub Actions (5 minutes, one-time setup)

Best for: a true sideloaded Android app where audio autoplays, the
app appears in Settings → Apps, and you control versioning.

### One-time setup (5 min)

1. In Emergent's chat input, click **"Save to Github"** to push this
   project to your GitHub account.
2. Go to **github.com/<your-user>/<this-repo>** → **Actions** tab.
3. The workflow `Build ON NOW TV V2 APK` runs automatically on the
   first push.  Wait ~3 minutes for the green check.
4. Click the run → scroll to **Artifacts** → download
   `onnowtv-v2-debug.apk`.

   *Or* go to the repo's **Releases** tab and grab the APK from the
   "apk-latest" release.

### Sideload onto the HK1 (60 sec)

1. On the HK1, install **Downloader by AFTVnews** from the Play Store.
2. In Downloader, type the **direct URL of the APK file** (the GitHub
   Releases page gives you a permalink — long-press the APK download
   button, copy link, paste it).
3. Tap GO → Downloader downloads + opens the installer → **Install**.
4. Allow "Install from unknown sources" if prompted.
5. ON NOW TV V2 now appears in the HK1's app launcher.

Every time you push code, GitHub Actions rebuilds and updates the
"apk-latest" release — re-run Downloader with the same URL to update.

✅  Real APK, real package id (`tv.onnowtv.app`).
✅  Audio autoplay works (WebView is permissive).
✅  No Chrome chrome, no URL bar, no notifications nag.
✅  Survives reboots.

---

## Which one should I pick?

| Situation | Recommended path |
|-----------|------------------|
| "I want to play with it RIGHT NOW" | Path 1 (TV Bro) |
| "I want it to look like a real app, no GitHub setup" | Path 2 (PWA) |
| "I want a polished, audio-working APK to keep" | Path 3 (GitHub Actions) |

All three point at the same live web app
(`https://rebrand-app-5.preview.emergentagent.com`), so addons,
subtitles, network catalogues, and watch state are identical across
them.
