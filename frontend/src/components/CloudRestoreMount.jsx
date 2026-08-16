/**
 * CloudRestoreMount — thin bridge between AuthContext's
 * `cloudRestore.snapshot` state and the beautiful
 * <CloudRestoreDialog>.  Kept in its own file so App.js doesn't
 * have to import the dialog directly (App.js is already huge).
 *
 * v2.16.18.
 */

import React from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { isTriviaApp } from '@/lib/profiles';
import CloudRestoreDialog from '@/components/CloudRestoreDialog';

export default function CloudRestoreMount() {
    const { cloudRestore } = useAuth();
    const snap = cloudRestore?.snapshot;
    if (!snap) return null;
    // v2.19.2 — NEVER offer Vesper profile restore inside the Trivia
    // app (TV screen or phone controller).  Trivia has no profiles —
    // it's a party game, not a movie/TV product.
    if (isTriviaApp()) return null;
    return (
        <CloudRestoreDialog
            snapshot={snap}
            onDismiss={cloudRestore.dismiss}
        />
    );
}
