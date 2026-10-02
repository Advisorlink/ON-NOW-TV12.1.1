import React from 'react';
import HeroBillboard from '@/components/HeroBillboard';
import useFocusHero from '@/hooks/useFocusHero';

// Keep focus-follow state out of Home/Collection/Anime: changing the title
// must not reconcile every shelf, image and navigation item on the page.
export const FocusHeroBillboard = React.memo(function FocusHeroBillboard({ heroes }) {
    const focusHero = useFocusHero();
    return <HeroBillboard heroes={heroes} override={focusHero} />;
});