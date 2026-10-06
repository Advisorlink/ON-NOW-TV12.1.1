/** The APK appends OnNowTV/version on phones too; it is NOT a TV signal. */
export function isHandheldInput({ userAgent = '', coarse = false, touchPoints = 0, touchEvents = false, width = 0 }) {
    const ua = userAgent.replace(/\s*OnNowTV\/[^\s]+/gi, '');
    if (/\bTV\b|SMART[- ]?TV|GoogleTV|AppleTV|HbbTV|NetCast|BRAVIA|Crkey|\bAFT\w*\b/i.test(ua)) return false;
    if (/Mobile|iPhone|iPad/i.test(ua)) return true;
    const touch = coarse || touchPoints > 0 || touchEvents;
    // Android tablet UAs omit Mobile, and landscape screens exceed 900px.
    if (/Android/i.test(ua) && touch) return true;
    if (/Macintosh/i.test(ua) && touchPoints > 1) return true;
    return width < 900 && touch;
}