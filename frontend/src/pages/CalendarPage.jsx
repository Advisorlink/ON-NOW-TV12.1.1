import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import LibraryCalendar from '@/components/LibraryCalendar';
import { listFavouritesByType } from '@/lib/library';

/**
 * /calendar — standalone full-page release calendar.
 *
 * v2.13.4 — Promoted out of the Library page (where it lived as an
 * overlay behind a "Calendar" button) to its own rail entry.  Reuses
 * LibraryCalendar, which already renders full-screen; BACK / Esc
 * returns Home.
 */
export default function CalendarPage() {
    const navigate = useNavigate();
    const [tv, setTv] = useState(() => listFavouritesByType('series'));

    useEffect(() => {
        const sync = () => setTv(listFavouritesByType('series'));
        window.addEventListener('vesper:library-change', sync);
        return () => window.removeEventListener('vesper:library-change', sync);
    }, []);

    return (
        <div data-testid="calendar-page">
            <LibraryCalendar tvFavourites={tv} onClose={() => navigate('/')} />
        </div>
    );
}
