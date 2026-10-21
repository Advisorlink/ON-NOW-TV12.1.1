// Mirrors the native player's loading screen so pressing Play shows it
// at once instead of a blank dark layer while the stream resolves.
export const PlayerLaunchScreen = ({ meta }) => {
    const title = meta?.name || 'Loading…';
    const backdrop = meta?.background || '';
    const poster = meta?.poster || backdrop;
    const facts = [meta?.releaseInfo || meta?.year, meta?.runtime, meta?.imdbRating]
        .filter(Boolean)
        .join('  ·  ');
    return (
        <div
            data-testid="detail-autoplay-loader"
            className="absolute inset-0 z-50 overflow-hidden"
            style={{ background: '#06080F', pointerEvents: 'none' }}
        >
            {backdrop && (
                <img src={backdrop} alt="" className="absolute inset-0 w-full h-full object-cover" style={{ opacity: 0.55 }} />
            )}
            <div className="absolute inset-0" style={{ background: 'radial-gradient(circle, transparent 0%, #06080F 80%)' }} />
            <div className="absolute inset-0 flex items-center px-20 gap-12">
                {poster && (
                    <img
                        src={poster}
                        alt=""
                        data-testid="launch-screen-poster"
                        className="w-[220px] h-[330px] object-cover rounded-[14px] shrink-0"
                        style={{ background: '#101723', boxShadow: '0 24px 48px rgba(0,0,0,.6)' }}
                    />
                )}
                <div className="min-w-0">
                    <div className="font-mono text-xs tracking-[0.2em]" style={{ color: '#5DC8FF' }}>
                        NOW PLAYING · ON NOW TV V2
                    </div>
                    <div data-testid="launch-screen-title" className="mt-3.5 text-white font-bold text-[44px] leading-tight line-clamp-2">
                        {title}
                    </div>
                    {facts && <div className="mt-3.5 text-[15px]" style={{ color: '#8C96AA' }}>{facts}</div>}
                    <div data-testid="launch-screen-status" className="mt-7 font-mono text-[13px] tracking-[0.12em]" style={{ color: '#5DC8FF' }}>
                        ON NOW TV V2 is loading your program
                    </div>
                    <div className="mt-3.5 w-7 h-7 rounded-full border-2 border-[#5DC8FF] border-t-transparent vesper-spin" />
                </div>
            </div>
            <div className="absolute bottom-0 left-0 right-0 h-[2px]" style={{ background: '#5DC8FF' }} />
        </div>
    );
};
