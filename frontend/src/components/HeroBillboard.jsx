import React, { useEffect, useState } from 'react';
import { Play, Info, Plus } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import * as img from '@/lib/img';
import Host from '@/lib/host';

export default function HeroBillboard({ heroes, hiRes = false }) {
    const list = Array.isArray(heroes) ? heroes : [];
    const [idx, setIdx] = useState(0);
    const hero = list[idx] || list[0];
    const navigate = useNavigate();

    useEffect(() => {
        // Reset to first hero whenever the list changes (e.g. when live
        // data arrives after mock fallback was rendered).
        setIdx(0);
    }, [list]);

    useEffect(() => {
        if (list.length <= 1) return;
        // Slower rotation on cheap boxes so the GPU spends less time
        // blending crossfade frames.
        const period = (Host.isAndroid || Host.isLowEnd) ? 14000 : 9500;
        const t = setInterval(
            () => setIdx((i) => (i + 1) % list.length),
            period
        );
        return () => clearInterval(t);
    }, [list.length]);

    if (!hero) return null;

    const meta = [
        hero.year,
        hero.runtime,
        hero.rating,
        hero.genres?.length ? hero.genres.slice(0, 3).join(' · ') : null,
    ].filter(Boolean);

    const goToDetail = (autoplay = false) => {
        // Append `?autoplay=1` for the Play button so the Detail
        // page can auto-pick a 1080p stream when the user enabled
        // that setting (and silently fall back otherwise).
        const suffix = autoplay ? '?autoplay=1' : '';
        if (hero.routePath) navigate(hero.routePath + suffix);
        else navigate(`/title/${hero.id}${suffix}`);
    };

    /* Local Left/Right handler — keeps focus among the three hero
     * buttons instead of letting the global spatial-focus engine
     * bounce focus up to the back arrow / title or sideways into
     * the side nav rail.  Fixes user complaint v2.6.96: "If you're
     * at the buttons on the hero section and push right, it jumps
     * you back up — very frustrating". */
    const handleHeroBtnKey = (e) => {
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
        e.preventDefault();
        e.stopPropagation();
        const container = e.currentTarget.closest('[data-hero-actions]');
        if (!container) return;
        const buttons = Array.from(
            container.querySelectorAll('[data-focusable="true"]')
        );
        const idx = buttons.indexOf(e.currentTarget);
        if (idx < 0) return;
        const next = e.key === 'ArrowRight' ? idx + 1 : idx - 1;
        // Clamp to the row — DON'T let focus escape sideways.
        const clamped = Math.max(0, Math.min(buttons.length - 1, next));
        buttons[clamped]?.focus();
    };

    return (
        <section
            data-testid="hero-billboard"
            className="relative w-full overflow-hidden"
            /* v2.10.12 — Hero geometry redone, per user feedback
               on v2.10.9 ("just bring the image itself down, but
               put the text / buttons / covers back to where they
               were").
               - SECTION stays visually tall (clamp 520-720) so the
                 backdrop image keeps its "low / large" presence.
               - `margin-bottom: -1 * (taller-shorter)` pulls the
                 next sibling (the first shelf row) BACK UP to the
                 position it had before v2.10.9.  The shelf paints
                 over the section's bottom fade so the join stays
                 clean.
               - Content column (eyebrow + title + buttons) gets a
                 big paddingBottom so the CTA row sits where it
                 used to land in the old 540-px hero, not down at
                 the bottom of the new 720-px box. */
            style={{
                height: 'clamp(520px, 65vh, 720px)',
                marginBottom: 'clamp(-200px, -16vh, -140px)',
            }}
        >
            {list.map((h, i) => (
                <div
                    key={h.id}
                    aria-hidden={i !== idx}
                    className="absolute inset-0 transition-opacity duration-1000"
                    style={{ opacity: i === idx ? 1 : 0 }}
                >
                    <div
                        key={`${h.id}-${idx}`}
                        className={`absolute inset-0 bg-cover ${
                            i === idx && !Host.isAndroid && !Host.isLowEnd
                                ? 'vesper-kenburns'
                                : ''
                        }`}
                        style={{
                            backgroundImage: `url(${
                                hiRes
                                    ? img.heroBackdrop(h.backdrop)
                                    : img.backdrop(h.backdrop)
                            })`,
                            /* v2.6.85 — anchor faces in the upper
                               third of the image so heads aren't
                               cropped on the wider hero. */
                            backgroundPosition: 'center 30%',
                        }}
                    />
                </div>
            ))}

            <div
                className="absolute inset-0"
                style={{
                    /* Bottom of the hero needs to FULLY fade to
                       bg-0 since the next shelf row is going to
                       overlap us by ~180 px via the section's
                       negative margin-bottom.  The fade is steeper
                       than v2.10.9 (kicks in at 60 %) so the area
                       where shelves are about to draw is solid
                       bg-0, hiding any image bleed. */
                    background: `linear-gradient(180deg,
                        rgba(6,8,15,0.55) 0%,
                        rgba(6,8,15,0) 18%,
                        rgba(6,8,15,0) 38%,
                        rgba(6,8,15,0.55) 60%,
                        rgba(6,8,15,0.92) 78%,
                        var(--vesper-bg-0) 90%)`,
                    pointerEvents: 'none',
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(90deg,
                        rgba(6,8,15,0.94) 0%,
                        rgba(6,8,15,0.55) 28%,
                        rgba(6,8,15,0.05) 60%,
                        rgba(6,8,15,0) 100%)`,
                    pointerEvents: 'none',
                }}
            />
            {/* Subtle blue ambient glow */}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{
                    background:
                        'radial-gradient(ellipse 50% 60% at 80% 50%, rgba(var(--vesper-blue-rgb),0.10) 0%, transparent 70%)',
                }}
            />

            <div className="absolute inset-0 flex items-end">
                <div
                    key={hero.id}
                    className="relative z-10 max-w-[62vw] vesper-fade-up"
                    style={{
                        paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                        /* v2.10.12 — Big paddingBottom anchors the
                           text+CTA column ~180 px ABOVE the section's
                           visual bottom edge, matching where the
                           buttons used to land in the pre-v2.10.9
                           clamp(380-540) hero.  Combined with the
                           section's negative margin-bottom, the
                           CTAs and the first shelf row are now back
                           at their original screen positions while
                           the image still fills the larger top
                           portion of the page. */
                        paddingBottom: 'clamp(160px, 14vh, 210px)',
                    }}
                >
                    <div className="vesper-eyebrow mb-3">{hero.eyebrow}</div>
                    <h1
                        data-testid="hero-title"
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(44px, 5vw, 78px)',
                            letterSpacing: '-0.035em',
                            lineHeight: 0.94,
                        }}
                    >
                        {hero.title}
                    </h1>

                    {meta.length > 0 && (
                        <div className="flex items-center gap-3 mt-4 vesper-meta flex-wrap">
                            {meta.map((m, i) => (
                                <React.Fragment key={i}>
                                    {i > 0 && <Dot />}
                                    <span
                                        style={
                                            i === 0
                                                ? {
                                                      color: 'var(--vesper-blue)',
                                                      fontWeight: 500,
                                                  }
                                                : undefined
                                        }
                                    >
                                        {m}
                                    </span>
                                </React.Fragment>
                            ))}
                        </div>
                    )}

                    {hero.synopsis && (
                        <p
                            className="mt-4 max-w-[56ch]"
                            style={{
                                fontSize: 'clamp(14px, 1.1vw, 18px)',
                                lineHeight: 1.55,
                                fontWeight: 400,
                                color: 'var(--vesper-text-2)',
                                display: '-webkit-box',
                                WebkitLineClamp: 3,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                            }}
                        >
                            {hero.synopsis}
                        </p>
                    )}

                    <div className="flex items-center gap-3 mt-6" data-hero-actions>
                        <button
                            data-testid="hero-play-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => goToDetail(true)}
                            onKeyDown={(e) => handleHeroBtnKey(e)}
                            className="flex items-center gap-2 rounded-full font-sans font-semibold"
                            style={{
                                height: 'clamp(44px, 3.6vw, 52px)',
                                paddingLeft: 'clamp(20px, 1.6vw, 26px)',
                                paddingRight: 'clamp(20px, 1.6vw, 26px)',
                                fontSize: 'clamp(14px, 1.05vw, 17px)',
                                background:
                                    'linear-gradient(180deg, #ffffff 0%, #d8e6ee 100%)',
                                color: '#06080f',
                            }}
                        >
                            <Play size={18} strokeWidth={2.5} fill="#06080f" />
                            Play
                        </button>
                        <button
                            data-testid="hero-info-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => goToDetail(false)}
                            onKeyDown={(e) => handleHeroBtnKey(e)}
                            className="flex items-center gap-2 rounded-full font-sans font-medium"
                            style={{
                                height: 'clamp(44px, 3.6vw, 52px)',
                                paddingLeft: 'clamp(18px, 1.4vw, 22px)',
                                paddingRight: 'clamp(18px, 1.4vw, 22px)',
                                fontSize: 'clamp(14px, 1.05vw, 17px)',
                                background: 'rgba(255,255,255,0.08)',
                                color: 'var(--vesper-text)',
                                border: '1px solid rgba(255,255,255,0.16)',
                            }}
                        >
                            <Info size={16} strokeWidth={1.7} />
                            More Info
                        </button>
                        <button
                            data-testid="hero-list-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onKeyDown={(e) => handleHeroBtnKey(e)}
                            className="flex items-center gap-2 rounded-full font-sans font-medium"
                            style={{
                                height: 'clamp(44px, 3.6vw, 52px)',
                                paddingLeft: 'clamp(16px, 1.3vw, 20px)',
                                paddingRight: 'clamp(16px, 1.3vw, 20px)',
                                fontSize: 'clamp(14px, 1.05vw, 17px)',
                                background: 'transparent',
                                color: 'var(--vesper-text-2)',
                                border: '1px solid rgba(255,255,255,0.16)',
                            }}
                        >
                            <Plus size={16} strokeWidth={1.7} />
                            My List
                        </button>
                    </div>
                </div>
            </div>

            <div
                className="absolute right-12 flex items-center gap-2.5 z-10"
                style={{
                    /* v2.10.12 — Anchor the slide-position dots to
                       where the section's logical bottom WOULD be
                       (i.e. above the negative-margin overshoot)
                       so the shelf row never covers them. */
                    bottom: 'clamp(150px, 13vh, 200px)',
                }}
            >
                {list.map((_, i) => (
                    <span
                        key={i}
                        className="block transition-all duration-500"
                        style={{
                            width: i === idx ? 36 : 8,
                            height: 3,
                            borderRadius: 2,
                            background:
                                i === idx
                                    ? 'var(--vesper-blue)'
                                    : 'rgba(255,255,255,0.22)',
                            boxShadow:
                                i === idx
                                    ? '0 0 16px var(--vesper-blue-glow)'
                                    : 'none',
                        }}
                    />
                ))}
            </div>
        </section>
    );
}

const Dot = () => (
    <span
        className="inline-block w-1 h-1 rounded-full"
        style={{ background: 'rgba(255,255,255,0.32)' }}
    />
);
