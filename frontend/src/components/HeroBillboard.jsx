import React, { useEffect, useState } from 'react';
import { Play, Info, Plus } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { HEROES } from '@/data/mockCatalog';

export default function HeroBillboard() {
    const [idx, setIdx] = useState(0);
    const hero = HEROES[idx];
    const navigate = useNavigate();

    useEffect(() => {
        const t = setInterval(
            () => setIdx((i) => (i + 1) % HEROES.length),
            9500
        );
        return () => clearInterval(t);
    }, []);

    return (
        <section
            data-testid="hero-billboard"
            className="relative w-full overflow-hidden"
            style={{ height: '82vh' }}
        >
            {HEROES.map((h, i) => (
                <div
                    key={h.id}
                    aria-hidden={i !== idx}
                    className="absolute inset-0 transition-opacity duration-1000"
                    style={{ opacity: i === idx ? 1 : 0 }}
                >
                    <div
                        key={`${h.id}-${idx}`}
                        className={`absolute inset-0 bg-cover bg-center ${
                            i === idx ? 'vesper-kenburns' : ''
                        }`}
                        style={{ backgroundImage: `url(${h.backdrop})` }}
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
                        'radial-gradient(ellipse 50% 60% at 80% 50%, rgba(93,200,255,0.10) 0%, transparent 70%)',
                }}
            />

            <div className="absolute inset-0 flex items-end pb-24">
                <div
                    key={hero.id}
                    className="relative z-10 max-w-[58vw] vesper-fade-up"
                    style={{ paddingLeft: '180px' }}
                >
                    <div className="vesper-eyebrow mb-5">{hero.eyebrow}</div>
                    <h1
                        data-testid="hero-title"
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(56px, 6.4vw, 96px)',
                            letterSpacing: '-0.035em',
                            lineHeight: 0.95,
                        }}
                    >
                        {hero.title}
                    </h1>

                    <div className="flex items-center gap-4 mt-6 vesper-meta flex-wrap">
                        <span
                            style={{
                                color: 'var(--vesper-blue)',
                                fontWeight: 500,
                            }}
                        >
                            {hero.year}
                        </span>
                        <Dot />
                        <span>{hero.runtime}</span>
                        <Dot />
                        <span
                            className="px-2 py-1 vesper-mono"
                            style={{
                                fontSize: 12,
                                border: '1px solid rgba(255,255,255,0.2)',
                                borderRadius: 4,
                                color: 'var(--vesper-text-2)',
                                letterSpacing: '0.18em',
                            }}
                        >
                            {hero.rating}
                        </span>
                        <Dot />
                        <span>{hero.genres.join(' · ')}</span>
                    </div>

                    <p
                        className="mt-6 max-w-[46ch]"
                        style={{
                            fontSize: 20,
                            lineHeight: 1.55,
                            fontWeight: 400,
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        {hero.synopsis}
                    </p>

                    <div className="flex items-center gap-3 mt-9">
                        <button
                            data-testid="hero-play-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus="true"
                            tabIndex={0}
                            onClick={() => navigate(`/title/${hero.id}`)}
                            className="flex items-center gap-3 h-14 px-8 rounded-full font-sans font-semibold text-[19px]"
                            style={{
                                background:
                                    'linear-gradient(180deg, #ffffff 0%, #d8e6ee 100%)',
                                color: '#06080f',
                            }}
                        >
                            <Play size={22} strokeWidth={2.5} fill="#06080f" />
                            Play
                        </button>
                        <button
                            data-testid="hero-info-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => navigate(`/title/${hero.id}`)}
                            className="flex items-center gap-3 h-14 px-7 rounded-full font-sans font-medium text-[19px]"
                            style={{
                                background: 'rgba(255,255,255,0.08)',
                                color: 'var(--vesper-text)',
                                border: '1px solid rgba(255,255,255,0.16)',
                            }}
                        >
                            <Info size={20} strokeWidth={1.7} />
                            More Info
                        </button>
                        <button
                            data-testid="hero-list-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            className="flex items-center gap-3 h-14 px-6 rounded-full font-sans font-medium text-[19px]"
                            style={{
                                background: 'transparent',
                                color: 'var(--vesper-text-2)',
                                border: '1px solid rgba(255,255,255,0.16)',
                            }}
                        >
                            <Plus size={20} strokeWidth={1.7} />
                            My List
                        </button>
                    </div>

                    <div className="flex items-center gap-3 mt-7 vesper-eyebrow flex-wrap">
                        <span style={{ color: 'var(--vesper-text-3)' }}>
                            On
                        </span>
                        {hero.sources.map((s) => (
                            <span
                                key={s}
                                className="px-2.5 py-1 rounded-md"
                                style={{
                                    color: 'var(--vesper-blue-bright)',
                                    background: 'rgba(93,200,255,0.08)',
                                    border: '1px solid rgba(93,200,255,0.25)',
                                    letterSpacing: '0.16em',
                                    fontSize: 11,
                                }}
                            >
                                {s}
                            </span>
                        ))}
                    </div>
                </div>
            </div>

            <div className="absolute bottom-10 right-12 flex items-center gap-2.5 z-10">
                {HEROES.map((_, i) => (
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
