import React, { useEffect, useState } from 'react';
import { Play, Info, Plus } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import * as img from '@/lib/img';
import Host from '@/lib/host';

export default function HeroBillboard({ heroes }) {
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

    return (
        <section
            data-testid="hero-billboard"
            className="relative w-full overflow-hidden"
            style={{ height: 'clamp(360px, 56vh, 620px)' }}
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
                        className={`absolute inset-0 bg-cover bg-center ${
                            i === idx && !Host.isAndroid && !Host.isLowEnd
                                ? 'vesper-kenburns'
                                : ''
                        }`}
                        style={{ backgroundImage: `url(${img.backdrop(h.backdrop)})` }}
                    />
                </div>
            ))}

            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(180deg,
                        rgba(6,8,15,0.55) 0%,
                        rgba(6,8,15,0) 28%,
                        rgba(6,8,15,0) 50%,
                        rgba(6,8,15,0.88) 88%,
                        var(--vesper-bg-0) 100%)`,
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
                    className="relative z-10 max-w-[58vw] vesper-fade-up"
                    style={{
                        paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                        paddingBottom: 'clamp(48px, 5vw, 96px)',
                    }}
                >
                    <div className="vesper-eyebrow mb-3">{hero.eyebrow}</div>
                    <h1
                        data-testid="hero-title"
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(36px, 4.2vw, 64px)',
                            letterSpacing: '-0.035em',
                            lineHeight: 0.95,
                        }}
                    >
                        {hero.title}
                    </h1>

                    {meta.length > 0 && (
                        <div className="flex items-center gap-3 mt-3 vesper-meta flex-wrap">
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
                            className="mt-3 max-w-[52ch]"
                            style={{
                                fontSize: 'clamp(13px, 1vw, 16px)',
                                lineHeight: 1.5,
                                fontWeight: 400,
                                color: 'var(--vesper-text-2)',
                                display: '-webkit-box',
                                WebkitLineClamp: 2,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                            }}
                        >
                            {hero.synopsis}
                        </p>
                    )}

                    <div className="flex items-center gap-3 mt-5">
                        <button
                            data-testid="hero-play-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => goToDetail(true)}
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

            <div className="absolute bottom-10 right-12 flex items-center gap-2.5 z-10">
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
