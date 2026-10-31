import { toast } from 'sonner';

/* "Speed up" = the TV box's Boost pop-up (closes background apps, clears picture memory). */
export function speedUp() {
    try { window.dispatchEvent(new CustomEvent('vesper:hide-trailer-preview')); } catch { /* ignore */ }
    if (typeof window.OnNowTV?.boostBox === 'function') {
        window.OnNowTV.boostBox();
        return true;
    }
    toast('Speed up runs on the TV box app — it frees memory so menus stay quick.');
    return false;
}
