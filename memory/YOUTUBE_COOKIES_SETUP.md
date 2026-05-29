# YouTube Cookies Setup — for ON NOW TV Tunes full-track playback

This file walks through the **safe** way to feed YouTube cookies to
the music backend so it can resolve full tracks from YouTube.

> 🚨 **Safety first.**  Read the "Threat model" section before doing
> anything.  Done right this is very safe.  Done wrong it could
> compromise your real Google account.

---

## Threat model — what you need to know

YouTube's anti-bot system protects against scrapers.  When we feed
your cookies to `yt-dlp` we're proving to YouTube that requests are
coming from a real signed-in account.  Two risks:

1. **Account ban.**  If YouTube detects the cookies being used at
   scale they may suspend the Google account they belong to.
2. **Account theft (if cookies leak).**  Whoever has your cookies
   can do anything on YouTube that you can — read watch history,
   subscriptions, etc.  They CANNOT change your password, drain
   payment methods, or read Gmail (those need 2FA-fresh sessions).

**Both risks are eliminated by using a fresh, dedicated Google
account.**  Five minutes to create:

  1. Open an incognito/private browser window.
  2. Go to https://accounts.google.com/signup
  3. Sign up with a brand new email like `onnowtv-music-001@gmail.com`
     (or whatever pattern you want).
  4. **DO NOT** add a phone number, recovery email, or payment method.
     Plain account, no personal info, no 2FA.
  5. Once created, visit https://www.youtube.com and click around a
     bit (watch 30 seconds of a music video) so YouTube treats it
     as a real user.
  6. That's it.  If this account ever gets banned, it doesn't matter.

> Recommendation: create **3 such accounts** so the backend can
> rotate cookies across them.  Spreads the request load and means
> a single account ban doesn't take Tunes offline.

---

## Step 1 — Install the cookie exporter (Chrome / Firefox / Edge)

Open this link IN THE SAME BROWSER where you just signed into the
throwaway Google account:

  → **Get cookies.txt LOCALLY** extension
  → https://chromewebstore.google.com/detail/get-cookiestxt-locally/cclelndahbckbenkjhflpdbgdldlbecc

  (Firefox version: https://addons.mozilla.org/firefox/addon/cookies-txt/)

  Why "LOCALLY"?  This specific extension keeps the cookies on
  YOUR device — it never uploads them to a third party.  The
  similar "Get cookies.txt" extension (no "LOCALLY") was caught
  exfiltrating cookies in 2023.  **Use only the LOCALLY one.**

---

## Step 2 — Export your YouTube cookies

1. Make sure you're signed into the throwaway Google account.
2. Open https://www.youtube.com
3. Click the extension's icon (puzzle-piece menu → "Get cookies.txt LOCALLY")
4. Click **"Current Site → Export"** (or the equivalent button).
5. Save the file.  Default filename is `youtube.com_cookies.txt`.
6. (If you created 3 accounts, repeat in 3 incognito windows so you
   get 3 separate cookie files.)

---

## Step 3 — Send the file to me

Paste the file's contents into the chat.  I'll:

  • Upload it to the VPS at `/opt/onnowtv/backend/youtube-cookies/account-N.txt`
  • Restart the backend so `yt-dlp` picks them up.
  • Verify with a curl test (Adele - Hello → real Adele full track).

The cookies live ONLY on:

  • Your local Get-cookies.txt-LOCALLY extension cache
  • The VPS file at `/opt/onnowtv/backend/youtube-cookies/`
  • This chat (which is logged in your Emergent session — they're
    visible only to you)

They are NEVER:

  • Committed to GitHub (added to `.gitignore`)
  • Sent to third parties
  • Logged anywhere

---

## Step 4 — Rotation (every ~30 days)

YouTube cookies expire after ~30 days of inactivity, or sooner if
YouTube flags suspicious activity.  When tracks suddenly start
falling back to 30-s previews, that's the cue to re-export.

The 3-account rotation means one expired cookie file doesn't break
the whole service — the resolver just skips to the next file.

---

## What I'll do once you send me the cookies

  1. Save them to `/opt/onnowtv/backend/youtube-cookies/account-1.txt`
     etc., chmod 600, owner-only readable.
  2. Update `music_api.py` so the resolver uses yt-dlp with
     `--cookies` pointing at one of those files (rotated round-robin).
  3. Add JioSaavn back as a fallback only (in case YouTube ever
     fails for a specific track — JioSaavn rarely has it, but
     belt-and-braces).
  4. Add `cookies/` to `.gitignore` so a casual `Save to GitHub`
     never publishes them.
  5. Live-verify with a handful of mainstream tracks (Adele,
     Drake, Taylor Swift, BTS, Coldplay).
  6. CHANGELOG entry, PRD updated.

---

## Stremio-style music addons — landscape brief

You asked "are there Stremio-like addons for music?" — here's the
honest state of the world:

**Yes — but they're called "Subsonic-compatible servers".**
Subsonic is the de-facto open music-server protocol (analogous to
how Stremio's addon API is the de-facto movie-addon protocol).
Dozens of compatible clients (Symfonium, Sonixd, Substreamer,
Tempo) and servers (Navidrome, Airsonic-advanced, Funkwhale)
exist.  But — and this is the same trap — these all stream content
from a server **you've already uploaded music to**.  Not a
discovery network.

Closest things to "Stremio-for-music" that are actually
discovery-networks:

  • **Spotube** (open-source app) — uses Spotify Web API + YouTube
    Music streams.  Exactly the architecture we're building.  Hits
    the same YouTube bot-check problem we just solved.
  • **YouTube Music via NewPipe Extractor** — a Java library that
    wraps YouTube's internal API.  Same bot-check applies on
    datacenter IPs.
  • **SoundCloud Go (unofficial)** — there's a community-maintained
    SoundCloud client_id that some apps use to stream user uploads.
    Catalog is mostly indie / remixes / DJ sets — similar to Audius.

**Recommendation for ON NOW TV Tunes:**
The cookie-fed yt-dlp approach we're shipping IS the same approach
Spotube uses.  We're going to be the "music Stremio" — Deezer
catalog UX + YouTube full streams + Radio Browser + iTunes
Podcasts.  As an enhancement, I can later add Subsonic-protocol
support so power-users with Navidrome / Plex Music / Jellyfin
libraries can connect their own collections — same pattern Vesper
does for Jellyfin.  Just say "add Subsonic" when you're ready.
