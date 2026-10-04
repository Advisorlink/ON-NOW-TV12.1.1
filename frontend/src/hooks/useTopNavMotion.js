import { useEffect, useRef } from 'react';
import { paceDpad } from '@/lib/dpadPacer';

/** Fixed geometry, one sliding highlight, and sibling-only horizontal moves. */
export default function useTopNavMotion() {
    const barRef = useRef(null);
    const markerRef = useRef(null);
    const selected = useRef(null);

    const follow = (button) => {
        const bar = barRef.current;
        const marker = markerRef.current;
        if (!button || button.parentElement !== bar || !marker) return;
        selected.current = button;
        document.querySelectorAll('[data-focused="true"]').forEach((el) => {
            if (el !== button) el.removeAttribute('data-focused');
        });
        button.setAttribute('data-focused', 'true');
        // offsetLeft is stable: labels never mount/unmount or resize buttons.
        marker.style.transform = `translate3d(${button.offsetLeft}px, ${button.offsetTop}px, 0)`;
        marker.style.opacity = '1';
    };

    const onKeyDown = (event) => {
        if (event.target.parentElement !== barRef.current) return;
        if (!['ArrowLeft', 'ArrowRight', 'ArrowUp'].includes(event.key)) return;
        event.preventDefault();
        event.stopPropagation();
        if (event.key === 'ArrowUp' || !paceDpad(event)) return;
        const property = event.key === 'ArrowRight' ? 'nextElementSibling' : 'previousElementSibling';
        let next = event.target[property];
        while (next && !next.matches('button[data-focusable="true"]')) next = next[property];
        next?.focus({ preventScroll: true });
    };

    const onBlur = (event) => {
        if (!barRef.current?.contains(event.relatedTarget) && markerRef.current) markerRef.current.style.opacity = '0';
    };

    useEffect(() => {
        const resize = () => {
            if (selected.current === document.activeElement) follow(selected.current);
        };
        window.addEventListener('resize', resize);
        return () => window.removeEventListener('resize', resize);
    }, []);

    return { barRef, markerRef, follow, onKeyDown, onBlur };
}