// A release may be presented once per session, even if auth/profile changes
// remount the UI before dismissal. Version the session marker too.
import { APP_VERSION } from '@/lib/appVersion';

const SEEN_KEY = 'onnowtv-whatsnew-seen-v1';
const SESSION_KEY = 'vesper-release-presented';
let presented = false;

export function releaseWasPresented() {
    if (presented) return true;
    try {
        return localStorage.getItem(SEEN_KEY) === APP_VERSION ||
            sessionStorage.getItem(SESSION_KEY) === APP_VERSION;
    } catch { return false; }
}

export function claimReleaseNotice() {
    if (releaseWasPresented()) return false;
    presented = true;
    try {
        sessionStorage.setItem(SESSION_KEY, APP_VERSION);
        sessionStorage.setItem('vesper-whatsnew-shown', '1');
    } catch { /* in-memory claim still prevents remount duplicates */ }
    return true;
}

export function dismissReleaseNotice() {
    try {
        localStorage.setItem(SEEN_KEY, APP_VERSION);
        localStorage.setItem('vesper-onboarding-seen-v1', String(Date.now()));
    } catch { /* session/in-memory claim remains */ }
}