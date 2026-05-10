import React, { useEffect, useState } from 'react';
import { Plus, Trash2, Loader2, ExternalLink, CheckCircle2 } from 'lucide-react';
import SideNav from '@/components/SideNav';
import FullscreenButton from '@/components/FullscreenButton';
import OnScreenKeyboard from '@/components/OnScreenKeyboard';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import { Vesper } from '@/lib/api';

export default function Sources() {
    useSpatialFocus();
    const { addons, loading, install, remove, refresh } = useAddons();
    const [suggested, setSuggested] = useState([]);
    const [showOSK, setShowOSK] = useState(false);
    const [oskValue, setOskValue] = useState('https://');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState(null);
    const [success, setSuccess] = useState(null);

    useEffect(() => {
        Vesper.suggestedAddons()
            .then((d) => setSuggested(d?.suggested || []))
            .catch(() => {});
    }, []);

    const doInstall = async (url) => {
        setBusy(true);
        setError(null);
        setSuccess(null);
        try {
            const res = await install(url);
            setSuccess(`Installed: ${res?.addon?.name || url}`);
            setShowOSK(false);
            setOskValue('https://');
            setTimeout(() => setSuccess(null), 4000);
        } catch (e) {
            setError(
                e?.userFacing
                    ? e.message
                    : e?.response?.data?.detail ||
                          e?.message ||
                          'Install failed — check the URL'
            );
        } finally {
            setBusy(false);
        }
    };

    return (
        <div
            data-testid="sources-page"
            className="relative w-screen h-[100dvh] min-h-screen overflow-hidden"
        >
            <SideNav />
            <FullscreenButton />

            <main
                className="absolute inset-0 overflow-y-auto"
                style={{ paddingLeft: 180, paddingRight: 80, paddingTop: 80, paddingBottom: 80 }}
            >
                <header className="mb-12">
                    <div className="vesper-eyebrow mb-4">Sources · Stremio Addons</div>
                    <h1
                        className="vesper-display"
                        style={{ fontSize: 'clamp(56px, 5.6vw, 80px)' }}
                    >
                        Add a source.
                    </h1>
                    <p
                        style={{
                            fontSize: 20,
                            color: 'var(--vesper-text-2)',
                            maxWidth: 720,
                            marginTop: 14,
                        }}
                    >
                        Paste any Stremio addon manifest URL and Vesper will pull its
                        catalogues straight onto your home screen. Plex &amp; Jellyfin
                        connectors are coming next.
                    </p>
                </header>

                {/* Action row */}
                <div className="flex items-center gap-4 mb-10 flex-wrap">
                    <button
                        data-testid="add-addon-button"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={() => setShowOSK((v) => !v)}
                        className="flex items-center gap-3 h-14 px-7 rounded-full font-sans font-semibold text-[18px]"
                        style={{
                            background: 'var(--vesper-blue)',
                            color: 'var(--vesper-bg-0)',
                        }}
                    >
                        <Plus size={20} strokeWidth={2.4} />
                        Add by URL
                    </button>
                    <button
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={refresh}
                        className="h-14 px-6 rounded-full font-sans font-medium text-[18px]"
                        style={{
                            background: 'rgba(255,255,255,0.06)',
                            color: 'var(--vesper-text-2)',
                            border: '1px solid rgba(255,255,255,0.12)',
                        }}
                    >
                        Refresh
                    </button>
                </div>

                {error && (
                    <div
                        data-testid="install-error"
                        className="rounded-xl px-5 py-4 mb-6"
                        style={{
                            background: 'rgba(255,80,80,0.08)',
                            border: '1px solid rgba(255,80,80,0.35)',
                            color: '#ffb5b5',
                            fontSize: 17,
                        }}
                    >
                        {error}
                    </div>
                )}
                {success && (
                    <div
                        data-testid="install-success"
                        className="rounded-xl px-5 py-4 mb-6 flex items-center gap-3"
                        style={{
                            background: 'rgba(93,200,255,0.08)',
                            border: '1px solid rgba(93,200,255,0.35)',
                            color: 'var(--vesper-blue-bright)',
                            fontSize: 17,
                        }}
                    >
                        <CheckCircle2 size={20} /> {success}
                    </div>
                )}

                {showOSK && (
                    <div className="mb-12 vesper-fade-up">
                        <OnScreenKeyboard
                            value={oskValue}
                            placeholder="https://your-addon.example.com/manifest.json"
                            onChange={setOskValue}
                            onSubmit={(v) => doInstall(v)}
                            submitLabel={busy ? 'Installing…' : 'Install'}
                        />
                    </div>
                )}

                {/* Installed */}
                <section className="mb-14">
                    <h2
                        className="vesper-display mb-6"
                        style={{ fontSize: 32, letterSpacing: '-0.02em' }}
                    >
                        Installed
                        <span
                            className="ml-3 vesper-mono"
                            style={{
                                fontSize: 13,
                                letterSpacing: '0.22em',
                                color: 'var(--vesper-text-3)',
                                textTransform: 'uppercase',
                            }}
                        >
                            {addons.length} active
                        </span>
                    </h2>

                    {loading ? (
                        <div
                            className="flex items-center gap-3"
                            style={{ color: 'var(--vesper-text-2)' }}
                        >
                            <Loader2 className="vesper-spin" size={20} /> Loading…
                        </div>
                    ) : addons.length === 0 ? (
                        <div
                            className="rounded-2xl p-8 vesper-glass"
                            style={{ color: 'var(--vesper-text-2)', fontSize: 17 }}
                        >
                            No addons yet. Tap{' '}
                            <span style={{ color: 'var(--vesper-blue)' }}>Add by URL</span>{' '}
                            above, or pick a suggested source below.
                        </div>
                    ) : (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            {addons.map((a) => (
                                <article
                                    key={a.id}
                                    data-testid={`addon-card-${a.id}`}
                                    className="vesper-glass rounded-2xl p-6 flex items-start justify-between gap-4"
                                >
                                    <div className="min-w-0">
                                        <div
                                            className="vesper-display truncate"
                                            style={{
                                                fontSize: 26,
                                                letterSpacing: '-0.02em',
                                            }}
                                        >
                                            {a.name}
                                        </div>
                                        <div
                                            className="vesper-eyebrow mt-1"
                                            style={{
                                                color: 'var(--vesper-blue)',
                                                fontSize: 11,
                                            }}
                                        >
                                            v{a.version} · {(a.types || []).join(' · ') || 'misc'}
                                        </div>
                                        <p
                                            className="mt-3 text-[16px]"
                                            style={{ color: 'var(--vesper-text-2)' }}
                                        >
                                            {a.description || '—'}
                                        </p>
                                        <div
                                            className="mt-3 vesper-mono truncate"
                                            style={{
                                                fontSize: 12,
                                                color: 'var(--vesper-text-3)',
                                            }}
                                        >
                                            {a.url}
                                        </div>
                                    </div>
                                    <button
                                        data-testid={`remove-addon-${a.id}`}
                                        data-focusable="true"
                                        data-focus-style="quiet"
                                        tabIndex={0}
                                        onClick={() => remove(a.id)}
                                        aria-label="Remove"
                                        className="shrink-0 flex items-center justify-center rounded-full"
                                        style={{
                                            width: 48,
                                            height: 48,
                                            background: 'rgba(255,255,255,0.04)',
                                            color: 'var(--vesper-text-2)',
                                            border: '1px solid rgba(255,255,255,0.1)',
                                        }}
                                    >
                                        <Trash2 size={18} strokeWidth={1.6} />
                                    </button>
                                </article>
                            ))}
                        </div>
                    )}
                </section>

                {/* Suggested */}
                <section>
                    <h2
                        className="vesper-display mb-6"
                        style={{ fontSize: 32, letterSpacing: '-0.02em' }}
                    >
                        Suggested
                    </h2>
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        {suggested.map((s) => {
                            const installed = addons.some(
                                (a) => a.url === s.url.replace('/manifest.json', '')
                            );
                            return (
                                <article
                                    key={s.url}
                                    className="vesper-glass rounded-2xl p-6 flex flex-col"
                                >
                                    <div
                                        className="vesper-eyebrow mb-2"
                                        style={{ fontSize: 11 }}
                                    >
                                        Stremio · Public
                                    </div>
                                    <div
                                        className="vesper-display"
                                        style={{ fontSize: 24, letterSpacing: '-0.02em' }}
                                    >
                                        {s.name}
                                    </div>
                                    <p
                                        className="mt-2 mb-4 text-[15px]"
                                        style={{
                                            color: 'var(--vesper-text-2)',
                                            flex: 1,
                                        }}
                                    >
                                        {s.description}
                                    </p>
                                    <button
                                        data-testid={`install-suggested-${s.name}`}
                                        data-focusable="true"
                                        data-focus-style="pill"
                                        tabIndex={0}
                                        disabled={installed || busy}
                                        onClick={() => doInstall(s.url)}
                                        className="mt-auto flex items-center justify-center gap-2 h-12 px-5 rounded-full font-sans font-medium text-[16px]"
                                        style={{
                                            background: installed
                                                ? 'rgba(93,200,255,0.1)'
                                                : 'rgba(255,255,255,0.06)',
                                            color: installed
                                                ? 'var(--vesper-blue-bright)'
                                                : 'var(--vesper-text)',
                                            border: '1px solid rgba(255,255,255,0.12)',
                                            opacity: installed ? 0.7 : 1,
                                        }}
                                    >
                                        {installed ? (
                                            <>
                                                <CheckCircle2 size={16} /> Installed
                                            </>
                                        ) : (
                                            <>
                                                <ExternalLink size={16} /> Install
                                            </>
                                        )}
                                    </button>
                                </article>
                            );
                        })}
                    </div>
                </section>
            </main>
        </div>
    );
}
