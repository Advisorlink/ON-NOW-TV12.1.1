// Pixel copy of the native player's LoadingScreen (PlayerOverlay.kt) so
// Play shows one continuous loading screen until the movie starts.
export const PlayerLaunchScreen = ({ meta }) => {
    const title = meta?.name || 'Loading…';
    const backdrop = meta?.background || '';
    const poster = meta?.poster || backdrop;
    const synopsis = meta?.description || '';
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
            <div className="absolute inset-0 flex items-center px-6 gap-6 sm:px-20 sm:gap-12">
                {poster && (
                    <img
                        src={poster}
                        alt=""
                        data-testid="launch-screen-poster"
                        className="hidden sm:block w-[220px] h-[330px] object-cover rounded-[14px] shrink-0"
                        style={{ background: '#101723', boxShadow: '0 24px 48px rgba(0,0,0,.6)' }}
                    />
                )}
                <div className="min-w-0 flex-1">
                    <div className="font-mono text-xs tracking-[0.216em]" style={{ color: '#5DC8FF' }}>
                        NOW PLAYING · ON NOW TV V2
                    </div>
                    <div data-testid="launch-screen-title" className="mt-3.5 text-white font-bold text-3xl sm:text-[44px] leading-tight line-clamp-2">
                        {title}
                    </div>
                    {facts && <div className="mt-3.5 text-[15px] truncate" style={{ color: '#A8B5C7' }}>{facts}</div>}
                    {synopsis && (
                        <div className="mt-[18px] text-[15px] leading-[22px] line-clamp-3" style={{ color: 'rgba(199,207,219,0.8)' }}>
                            {synopsis}
                        </div>
                    )}
                    <div data-testid="launch-screen-status" className="mt-7 font-mono text-[13px] tracking-[0.123em]" style={{ color: '#5DC8FF' }}>
                        ON NOW TV V2 is loading your program
                    </div>
                    <div className="mt-3.5 flex gap-2.5" data-testid="launch-screen-dots">
                        {[0, 1, 2].map((i) => (
                            <span
                                key={i}
                                className="w-2 h-2 rounded-full vesper-launch-dot"
                                style={{ animationDelay: `${i * 0.8}s` }}
                            />
                        ))}
                    </div>
                </div>
            </div>
            <div className="absolute bottom-0 left-0 right-0 h-[2px]" style={{ background: '#5DC8FF' }} />
        </div>
    );
};
