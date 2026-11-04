import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { RadioTower, RefreshCw, EyeOff, Eye, Lock, Unlock } from 'lucide-react';
import { getActiveProvider } from '@/lib/xtream';
import { loadCategories, loadChannels } from '@/lib/liveCache';
import { preloadLiveTv } from '@/lib/liveAccount';
import { getLivePrefs, toggleInList } from '@/lib/livePrefs';

const rowStyle = {
    background: 'rgba(255,255,255,0.04)',
    border: '1px solid rgba(255,255,255,0.08)',
    borderRadius: 10,
    padding: '10px 14px',
    marginBottom: 8,
};

function ActionRow({ testid, icon: Icon, title, description, onClick, busy }) {
    return (
        <button type="button" data-testid={testid} data-focusable="true" onClick={onClick} disabled={busy}
            className="vesper-glass rounded-2xl flex items-center gap-4 w-full text-left"
            style={{ padding: '18px 22px', marginBottom: 14, cursor: 'pointer', color: 'inherit', opacity: busy ? 0.6 : 1 }}>
            <div className="flex items-center justify-center shrink-0" style={{ width: 44, height: 44, borderRadius: '50%', background: 'rgba(var(--vesper-blue-rgb),0.16)', border: '1px solid rgba(var(--vesper-blue-rgb),0.45)' }}>
                <Icon size={20} style={{ color: 'var(--vesper-blue-bright)' }} className={busy ? 'animate-spin' : ''} />
            </div>
            <div style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 700, fontSize: 16 }}>{title}</div>
                <div style={{ opacity: 0.7, fontSize: 13, marginTop: 4 }}>{description}</div>
            </div>
        </button>
    );
}

function Pill({ active, icon: Icon, label, onClick, testid }) {
    return (
        <button type="button" data-testid={testid} data-focusable="true" onClick={onClick}
            className="flex items-center gap-1.5 shrink-0"
            style={{
                borderRadius: 999, padding: '6px 12px', fontSize: 12, fontWeight: 700,
                background: active ? 'var(--vesper-blue)' : 'rgba(255,255,255,0.08)',
                color: active ? '#04101e' : 'var(--vesper-text)', border: 'none', cursor: 'pointer',
            }}>
            <Icon size={13} /> {label}
        </button>
    );
}

export default function LiveTvSettingsSection() {
    const navigate = useNavigate();
    const provider = getActiveProvider();
    const [prefs, setPrefs] = useState(getLivePrefs);
    const [tick, setTick] = useState(0);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        const on = () => { setPrefs(getLivePrefs()); setTick((t) => t + 1); };
        window.addEventListener('reborn:live-prefs', on);
        window.addEventListener('reborn:live-preloaded', on);
        return () => { window.removeEventListener('reborn:live-prefs', on); window.removeEventListener('reborn:live-preloaded', on); };
    }, []);

    const groups = useMemo(() => {
        if (!provider) return [];
        const cats = loadCategories(provider.id) || [];
        const chans = loadChannels(provider.id) || {};
        return cats.map((c) => ({ id: String(c.category_id), name: c.category_name, count: (chans[c.category_id] || chans[String(c.category_id)] || []).length }));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider?.id, tick]);

    const hidden = new Set(prefs.hiddenGroups.map(String));
    const locked = new Set(prefs.lockedGroups.map(String));

    const refresh = async () => {
        setBusy(true);
        await preloadLiveTv(provider, { force: true });
        setBusy(false);
    };

    return (
        <div data-testid="livetv-web-settings">
            <ActionRow testid="livetv-open-guide" icon={RadioTower} title="Open the channel guide" description="Jump straight into Live TV." onClick={() => navigate('/live')} />
            <ActionRow testid="livetv-refresh" icon={RefreshCw} busy={busy} title="Refresh channels and guide" description={provider ? 'Re-download the channel list and programme guide from your provider.' : 'Sign in again to connect Live TV to your account.'} onClick={refresh} />

            <div style={{ margin: '22px 0 10px', fontSize: 11, letterSpacing: '0.18em', textTransform: 'uppercase', color: 'var(--vesper-text-2)', fontWeight: 700 }}>
                Channel groups · {groups.length}
            </div>
            <div style={{ fontSize: 12, color: 'var(--vesper-text-2)', marginBottom: 12, maxWidth: '70ch' }}>
                Hidden groups disappear from the guide. Locked groups ask for your profile PIN and are never shown on Kids profiles.
            </div>
            {groups.length === 0 && (
                <div data-testid="livetv-groups-empty" style={{ ...rowStyle, color: 'var(--vesper-text-2)', fontSize: 13 }}>
                    {busy ? 'Loading your channel groups…' : 'No channel groups loaded yet. Tap Refresh above.'}
                </div>
            )}
            {groups.map((g) => (
                <div key={g.id} data-testid={`live-group-${g.id}`} className="flex items-center justify-between gap-3" style={{ ...rowStyle, opacity: hidden.has(g.id) ? 0.55 : 1 }}>
                    <div className="min-w-0">
                        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--vesper-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{g.name}</div>
                        <div style={{ fontSize: 11, color: 'var(--vesper-text-2)', marginTop: 2 }}>{g.count} channels</div>
                    </div>
                    <div className="flex gap-2 shrink-0">
                        <Pill testid={`live-group-hide-${g.id}`} active={hidden.has(g.id)} icon={hidden.has(g.id) ? EyeOff : Eye} label={hidden.has(g.id) ? 'Hidden' : 'Shown'} onClick={() => toggleInList('hiddenGroups', g.id)} />
                        <Pill testid={`live-group-lock-${g.id}`} active={locked.has(g.id)} icon={locked.has(g.id) ? Lock : Unlock} label={locked.has(g.id) ? 'PIN locked' : 'Unlocked'} onClick={() => toggleInList('lockedGroups', g.id)} />
                    </div>
                </div>
            ))}
        </div>
    );
}
