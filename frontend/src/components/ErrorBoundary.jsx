import React from 'react';

/**
 * Top-level error boundary so an exception in ANY component does
 * not turn the entire WebView into a black screen.
 *
 * What the user was seeing:
 *   "It keeps just going to a black screen and just stopping, and
 *    then not being able to do anything.  I have to refresh the
 *    cache and the RAM."
 *
 * That's the classic React "unhandled render error" symptom — when
 * a thrown exception bubbles past the root <App>, React 18 unmounts
 * the entire tree.  The HK1 box can't recover from that without a
 * full reload because the document body is now empty.
 *
 * This boundary:
 *   1. Catches both thrown render errors and globally-unhandled
 *      promise rejections / window errors.
 *   2. Shows a friendly recovery screen WITH the error text so we
 *      can debug from a TV photo.
 *   3. Offers two recovery paths:
 *        a) "Try again"     — clears the boundary and re-renders.
 *        b) "Reset & reload" — clears caches that may be poisoning
 *           state (cache.* / shelves: / tab: / etc.) and reloads.
 */
export default class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props);
        this.state = { error: null, errorInfo: null };
        this.handleWindowError = this.handleWindowError.bind(this);
        this.handleRejection = this.handleRejection.bind(this);
    }

    static getDerivedStateFromError(error) {
        return { error };
    }

    componentDidCatch(error, errorInfo) {
        this.setState({ errorInfo });
        // eslint-disable-next-line no-console
        console.error('[ErrorBoundary] render crash:', error, errorInfo);
    }

    componentDidMount() {
        window.addEventListener('error', this.handleWindowError);
        window.addEventListener('unhandledrejection', this.handleRejection);
    }

    componentWillUnmount() {
        window.removeEventListener('error', this.handleWindowError);
        window.removeEventListener('unhandledrejection', this.handleRejection);
    }

    handleWindowError(e) {
        if (!e || !e.error) return;
        // eslint-disable-next-line no-console
        console.error('[ErrorBoundary] window error:', e.error);
        const msg = String(e.error?.message || e.error || '').toLowerCase();
        /* Escalate to the recovery UI for top-level boot failures
         * that mean React can't have mounted (or did mount and is
         * now in a half-broken state).  Common signals are
         * SyntaxError / TypeError thrown synchronously from a
         * bundle import or a corrupted JSON parse.  Resource load
         * errors (img/script with `error` event) have no `.error`
         * payload so they don't reach here. */
        if (
            msg.includes('chunk') ||
            msg.includes('module') ||
            msg.includes('cannot read') ||
            msg.includes('is not a function') ||
            msg.includes('json') ||
            msg.includes('parse')
        ) {
            this.setState({
                error: e.error,
                errorInfo: { componentStack: '(window error)' },
            });
        }
    }

    handleRejection(e) {
        const r = e?.reason;
        const msg = String(r?.message || r || '').toLowerCase();
        /* The YouTube IFrame API internally probes the Permissions
         * API, which rejects with "Permissions check failed" in
         * sandboxed/preview contexts — pure third-party noise. */
        if (msg.includes('permissions check failed')) {
            e.preventDefault?.();
            return;
        }
        // eslint-disable-next-line no-console
        console.error('[ErrorBoundary] unhandled rejection:', r);
        /* Don't escalate every rejection — only "looks like the boot
         * path is broken" ones.  Network/abort errors are common and
         * shouldn't replace the UI. */
        if (
            msg.includes('quotaexceeded') ||
            msg.includes('chunkloaderror') ||
            msg.includes('loading chunk')
        ) {
            this.setState({
                error: r instanceof Error ? r : new Error(String(r)),
                errorInfo: { componentStack: '(unhandled rejection)' },
            });
        }
    }

    handleTryAgain = () => {
        this.setState({ error: null, errorInfo: null });
    };

    handleResetAndReload = () => {
        // Wipe sessionStorage entirely (catalogue caches).  In
        // localStorage we wipe BOTH the legacy ephemeral caches
        // (shelves:, cache.*, etc.) AND the structured app state
        // (onnowtv-*, vesper-*).  Critically, if the user got
        // here because of a corrupted profile / library / EPG
        // blob, we must clear THAT — otherwise the reload
        // re-hydrates the same bad state and re-crashes.  This is
        // the "in-app uninstall replacement" so end users never
        // need to clear data via Android settings or uninstall.
        //
        // Profiles get re-created when the user picks one anyway,
        // so wiping them is safe — far better than a black screen.
        try {
            sessionStorage.clear();
        } catch (err) {
            /* ignore */
        }
        try {
            const PREFIXES = [
                'shelves:',
                'tab:',
                'heroes:',
                'networks:',
                'addons',
                'cache.',
                'onnowtv-',
                'onnowtv:',
                'vesper-',
                'vesper:',
            ];
            for (const k of Object.keys(localStorage)) {
                if (PREFIXES.some((p) => k.indexOf(p) === 0)) {
                    localStorage.removeItem(k);
                }
            }
        } catch (err) {
            /* ignore */
        }
        try {
            window.location.replace('/');
        } catch (err) {
            try { window.location.reload(); } catch (e2) { /* ignore */ }
        }
    };

    render() {
        if (!this.state.error) return this.props.children;

        const msg =
            (this.state.error && this.state.error.message) ||
            String(this.state.error) ||
            'Unknown error';

        return (
            <div
                data-testid="error-boundary"
                className="fixed inset-0 flex items-center justify-center"
                style={{
                    background:
                        'radial-gradient(circle at 50% 30%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 60%), var(--vesper-bg-0)',
                    color: 'var(--vesper-text)',
                    padding: 48,
                }}
            >
                <div
                    className="flex flex-col items-center"
                    style={{
                        maxWidth: 720,
                        textAlign: 'center',
                    }}
                >
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.32em',
                            color: '#FCA5A5',
                            textTransform: 'uppercase',
                            marginBottom: 14,
                        }}
                    >
                        Something went wrong
                    </div>
                    <h1
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(32px, 4vw, 56px)',
                            letterSpacing: '-0.025em',
                            lineHeight: 1.05,
                            marginBottom: 16,
                        }}
                    >
                        ON NOW T
                        <span
                            style={{
                                color: 'var(--vesper-blue-bright)',
                                textShadow:
                                    '0 0 10px rgba(var(--vesper-blue-rgb), 0.7)',
                            }}
                        >
                            V2
                        </span>{' '}
                        hit a snag
                    </h1>
                    <p
                        style={{
                            color: 'var(--vesper-text-2)',
                            fontSize: 16,
                            lineHeight: 1.5,
                            maxWidth: 480,
                            marginBottom: 20,
                        }}
                    >
                        The app caught the problem before it became a
                        black screen. Press <strong>Try again</strong> to
                        resume where you left off, or{' '}
                        <strong>Reset &amp; reload</strong> to clear
                        cached catalogues and start fresh.
                    </p>
                    <pre
                        style={{
                            maxWidth: '100%',
                            background: 'rgba(0,0,0,0.45)',
                            border: '1px solid rgba(255,255,255,0.08)',
                            color: 'var(--vesper-text-3)',
                            fontSize: 12,
                            padding: '10px 14px',
                            borderRadius: 8,
                            marginBottom: 26,
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-word',
                        }}
                    >
                        {msg}
                    </pre>
                    <div className="flex" style={{ gap: 14 }}>
                        <button
                            data-testid="err-try-again"
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus="true"
                            tabIndex={0}
                            onClick={this.handleTryAgain}
                            className="rounded-full font-sans font-semibold"
                            style={{
                                height: 52,
                                padding: '0 28px',
                                fontSize: 15,
                                background: 'var(--vesper-blue)',
                                color: 'var(--vesper-bg-0)',
                                border: 'none',
                                boxShadow:
                                    '0 8px 24px rgba(var(--vesper-blue-rgb),0.35)',
                            }}
                        >
                            Try again
                        </button>
                        <button
                            data-testid="err-reset"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={this.handleResetAndReload}
                            className="rounded-full font-sans font-semibold"
                            style={{
                                height: 52,
                                padding: '0 28px',
                                fontSize: 15,
                                background: 'rgba(255,255,255,0.10)',
                                color: 'var(--vesper-text)',
                                border: '1px solid rgba(255,255,255,0.16)',
                            }}
                        >
                            Reset &amp; reload
                        </button>
                    </div>
                </div>
            </div>
        );
    }
}
