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
import CloudRestoreDialog from '@/components/CloudRestoreDialog';

export default function CloudRestoreMount() {
    const { cloudRestore } = useAuth();
    const snap = cloudRestore?.snapshot;
    if (!snap) return null;
    return (
        <CloudRestoreDialog
            snapshot={snap}
            onDismiss={cloudRestore.dismiss}
        />
    );
}
