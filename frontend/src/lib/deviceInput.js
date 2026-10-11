/** The APK appends OnNowTV/version on phones too; it is NOT a TV signal. */
const TV_UA_RE = /\bTV\b|SMART[- ]?TV|GoogleTV|AppleTV|HbbTV|NetCast|BRAVIA|Crkey|\bAFT\w*\b/i;

/**
 * @param inApk      true when the OnNowTV WebView bridge is present.
 * @param nativeTv   bridge `isTelevision()` result; null on APKs that predate it.
 * @param inputSeen  'touch' | 'key' | null — the first real input this device produced.
 */
export function isHandheldInput({
    userAgent = '', coarse = false, touchPoints = 0, touchEvents = false, width = 0, height = 0,
    inApk = false, nativeTv = null, inputSeen = null,
}) {
    // " VesperTV" is stamped by the APK on leanback/television hosts — the
    // only reliable TV signal: cheap boxes advertise a touchscreen and
    // Chromium-Android always reports a coarse pointer + touch events.
    if (nativeTv === true || /\bVesperTV\b/.test(userAgent)) return false;
    const ua = userAgent.replace(/\s*OnNowTV\/[^\s]+/gi, '');
    if (TV_UA_RE.test(ua)) return false;
    const touch = coarse || touchPoints > 0 || touchEvents;
    if (inApk && nativeTv !== true && nativeTv !== false) {
        // Older APK with no isTelevision(): a cheap box and a tablet look
        // identical here (Android, touch flags, sometimes even "Mobile"),
        // so only trust proof — a finger on the glass, a remote key, or a
        // portrait screen.  Undecided landscape stays on the TV layout.
        if (inputSeen === 'touch') return true;
        if (inputSeen === 'key') return false;
        return touch && height > width;
    }
    if (/Mobile|iPhone|iPad/i.test(ua)) return true;
    // Android tablet UAs omit Mobile, and landscape screens exceed 900px.
    if (/Android/i.test(ua) && touch) return true;
    if (/Macintosh/i.test(ua) && touchPoints > 1) return true;
    return width < 900 && touch;
}
