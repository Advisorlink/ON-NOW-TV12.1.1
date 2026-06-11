/**
 * <UpdateGate/> — DISABLED.
 *
 * v2.10.4 — User requested all automatic update popups be removed.
 * This component is still imported by App.js and rendered in the
 * tree, but now no-ops so the modal never appears.  The native
 * `window.OnNowTV.installApk` / `openExternal` bridges remain in
 * place on the Android side so a future manual / settings-driven
 * update flow can be reintroduced without re-shipping.
 */
import React from 'react';

export default function UpdateGate() {
    return null;
}
