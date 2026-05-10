import React, { useEffect, useState } from 'react';
import { Play, Info, Plus } from 'lucide-react';
import { HEROES } from '@/data/mockCatalog';

export default function HeroBillboard() {
    const [idx, setIdx] = useState(0);
    const hero = HEROES[idx];

    useEffect(() => {
        const t = setInterval(
            () => setIdx((i) => (i + 1) % HEROES.length),
            9000
        );
        return () => clearInterval(t);
    }, []);

    return (
        <section
            data-testid="hero-billboard"
            className="relative w-full overflow-hidden"
            style={{ height: '82vh' }}
        >
            {/* Backdrops — crossfade */}
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

            {/* Layered scrims (cheap on slow GPUs — pure gradients) */}
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(180deg,
                        rgba(5,5,5,0.4) 0%,
                        rgba(5,5,5,0) 30%,
                        rgba(5,5,5,0) 50%,
                        rgba(5,5,5,0.85) 88%,
                        #050505 100%)`,
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(90deg,
                        rgba(5,5,5,0.95) 0%,
                        rgba(5,5,5,0.6) 28%,
                        rgba(5,5,5,0.1) 60%,
                        rgba(5,5,5,0) 100%)`,
                }}
            />
            <div
                className="absolute inset-0 pointer-events-none vesper-grain"
                style={{ opacity: 0.4 }}
            />

            {/* Content */}
            <div className="absolute inset-0 flex items-end pb-24">
                <div
                    key={hero.id}
                    className="relative z-10 max-w-[58vw] vesper-fade-up"
                    style={{ paddingLeft: '160px' }}
                >
                    <div className="vesper-eyebrow mb-6">{hero.eyebrow}</div>
                    <h1
                        data-testid="hero-title"
                        className="vesper-display"
                        style={{ fontSize: 'clamp(72px, 7.5vw, 116px)' }}
                    >
                        {hero.title}
                    </h1>

                    <div className="flex items-center gap-5 mt-5 vesper-meta">
                        <span className="text-vesper-copper font-medium">
                            {hero.year}
                        </span>
                        <Dot />
                        <span>{hero.runtime}</span>
                        <Dot />
                        <span
                            className="px-2 py-1 text-[14px] font-mono tracking-widest border"
                            style={{
                                borderColor: 'rgba(255,255,255,0.25)',
                                color: 'var(--vesper-text2)',
                            }}
                        >
                            {hero.rating}
                        </span>
                        <Dot />
                        <span>{hero.genres.join(' · ')}</span>
                    </div>

                    <p
                        className="mt-7 text-vesper-text2 max-w-[44ch]"
                        style={{
                            fontSize: 22,
                            lineHeight: 1.55,
                            fontWeight: 300,
                        }}
                    >
                        {hero.synopsis}
                    </p>

                    <div className="flex items-center gap-4 mt-9">
                        <button
                            data-testid="hero-play-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus="true"
                            tabIndex={0}
                            className="flex items-center gap-3 h-16 px-9 rounded-full font-sans font-semibold text-[22px]"
                            style={{
                                background: '#f8f9fa',
                                color: '#050505',
                            }}
                        >
                            <Play size={24} strokeWidth={2.5} fill="#050505" />
                            Play
                        </button>
                        <button
                            data-testid="hero-info-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            className="flex items-center gap-3 h-16 px-9 rounded-full font-sans font-medium text-[22px]"
                            style={{
                                background: 'rgba(255,255,255,0.08)',
                                color: 'var(--vesper-text)',
                                border: '1px solid rgba(255,255,255,0.18)',
                            }}
                        >
                            <Info size={22} strokeWidth={1.7} />
                            More Info
                        </button>
                        <button
                            data-testid="hero-list-button"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            className="flex items-center gap-3 h-16 px-7 rounded-full font-sans font-medium text-[22px]"
                            style={{
                                background: 'transparent',
                                color: 'var(--vesper-text2)',
                                border: '1px solid rgba(255,255,255,0.18)',
                            }}
                        >
                            <Plus size={22} strokeWidth={1.7} />
                            My List
                        </button>
                    </div>

                    <div className="flex items-center gap-3 mt-8 vesper-eyebrow">
                        <span style={{ color: 'var(--vesper-text3)' }}>
                            Available on
                        </span>
                        {hero.sources.map((s) => (
                            <span
                                key={s}
                                style={{
                                    color: 'var(--vesper-text2)',
                                    letterSpacing: '0.18em',
                                }}
                            >
                                {s}
                            </span>
                        ))}
                    </div>
                </div>
            </div>

            {/* Hero index dots */}
            <div className="absolute bottom-10 right-12 flex items-center gap-3 z-10">
                {HEROES.map((_, i) => (
                    <span
                        key={i}
                        className="block transition-all duration-500"
                        style={{
                            width: i === idx ? 36 : 8,
                            height: 4,
                            borderRadius: 2,
                            background:
                                i === idx
                                    ? 'var(--vesper-copper)'
                                    : 'rgba(255,255,255,0.25)',
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
        style={{ background: 'rgba(255,255,255,0.35)' }}
    />
);
