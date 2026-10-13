# V2 Sports Hub — device acceptance checklist

## What is implemented

The native app uses the approved Astra layout with V2 Live's red wordmark, rounded controls, blue D-pad focus and bundled Barlow Condensed typography. Scores, summary/play data and standings come from the existing `/api/sportshub` ESPN proxy. No sample games are embedded in the Android app.

The separate `/sportshub-astra/` browser route remains a **sample-data design preview**, not a web implementation of native playback. Its screenshots are not screenshots from an Android box.

## Install the signed update

1. Run the existing **Build ON NOW V2 Live TV APK** workflow after the changes are available in the repository.
2. Install the APK produced by that workflow over the existing app, retaining the existing signing chain and app data.
3. Do not substitute a locally debug-signed APK for the signed update: its certificate may not match the installed app.

The workflow now reuses the hosted runner's Android SDK 34 rather than replacing command-line tools. Missing packages have bounded retries, and SDK diagnostics are uploaded on failure. The user's screenshot did not contain the actual SDK error message, so a successful new CI run is still required to confirm the original remote failure is resolved.

## On-box checks still required

- Enter **V2 Sports Hub** from the guide. Confirm the branding, rounded card edges, focused blue ring and pale-backed team logos at the box's actual resolution.
- Navigate sport/league pills, Scores, World map, Following and Fields using the remote. Leave focus on one scorecard through a refresh; focus must stay on that match.
- Open a live MLB/NFL match with available play data. Step back/forward and return to the latest play. Probabilities must follow the selected play; missing provider values show unavailable rather than an invented 50/50 split.
- Follow/unfollow a team; return to Following and restart the app to confirm persistence.
- In an upcoming game with a matched guide channel, set and remove a kick-off reminder. Confirm the existing guide reminder banner fires; this is not a new push-notification service.
- Select a world-map marker or a sidebar match. Confirm the venue and game are consistent. Games without provider coordinates do not receive invented locations.
- Open a matched channel. Confirm the existing native player receives the chosen channel and alternative-channel queue. Check Back restores the Sports Hub.
- Interrupt the network and change sport while a request is in progress. Expect a retry/last-known-score message, not fake data. Leave the app to ensure polling stops, then resume.
- Check the 12-field gallery and player/standings sections, including a competition where the provider supplies no standings or plays.

## Known boundaries

- ESPN coverage differs by league; individual unavailable feeds are disclosed, and the all-sports request has a bounded deadline.
- Following/reminders use the app's existing stores. Existing account and IPTV integrations are not replaced.
- The separately reported Direct playback buffering/audio-selection problem is not fixed by this design task.
- Physical-device layout, playback, remote navigation and signed-update installation cannot be fully certified by browser or JVM tests.