import os

os.makedirs('/app/frontend/public/design_mockups/overlays', exist_ok=True)
os.makedirs('/app/frontend/public/design_mockups/controls', exist_ok=True)

head = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=1920, height=1080, initial-scale=1">
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://fonts.googleapis.com/css2?family=Anton&family=Bebas+Neue&family=Cinzel:wght@400;700;900&family=Cormorant+Garamond:wght@400;600;700&family=JetBrains+Mono:wght@400;700&family=Manrope:wght@300;400;600&family=Outfit:wght@200;400;700;900&family=Playfair+Display:wght@400;700;900&family=Unbounded:wght@400;700;900&display=swap" rel="stylesheet">
    <style>
        body { width: 1920px; height: 1080px; overflow: hidden; background: #000; color: #fff; margin:0; position:relative; }
        .glass { background: rgba(20,20,20,0.6); backdrop-filter: blur(24px); border: 1px solid rgba(255,255,255,0.08); }
        .glass-light { background: rgba(255,255,255,0.05); backdrop-filter: blur(16px); border: 1px solid rgba(255,255,255,0.15); }
        .text-shadow { text-shadow: 0 4px 20px rgba(0,0,0,0.8); }
        .text-shadow-glow { text-shadow: 0 0 20px currentColor; }
        ::-webkit-scrollbar { display: none; }
    </style>
</head>
<body class="antialiased">
"""

movies = [
    {
        "id": "01", "name": "pacific-rim",
        "bg": "https://images.unsplash.com/photo-1770742447743-198d97cc340b?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjA2ODl8MHwxfHNlYXJjaHwxfHxvY2VhbiUyMHN0b3JtJTIwZGFyayUyMG5pZ2h0fGVufDB8fHx8MTc3OTM1NDI3OHww&ixlib=rb-4.1.0&q=85",
        "title": "PACIFIC RIM", "font": "font-['Anton']", "overlay": "bg-gradient-to-t from-[#0A192F] via-[#0A192F]/60 to-transparent",
        "eyebrow": "JAEGER PROGRAM AUTHORIZED", "font_body": "font-['JetBrains_Mono']",
        "synopsis": "When legions of monstrous creatures, known as Kaiju, started rising from the sea, a war began that would take millions of lives and consume humanity's resources for years on end. To combat the Kaiju, a special type of weapon was devised: massive robots, called Jaegers.",
        "chips": ['2013', '132 MIN', 'PG-13', '4K MECHA-HDR', 'MKV · 82GB'], "accent": "text-[#00F0FF]",
        "chip_style": "border-l-4 border-[#00F0FF] bg-black/80 px-3 py-1.5 uppercase tracking-widest text-xs shadow-[0_0_10px_rgba(0,240,255,0.2)]",
        "logo_style": "text-8xl tracking-[0.05em] text-transparent bg-clip-text bg-gradient-to-b from-white to-[#00F0FF] drop-shadow-[0_0_15px_rgba(0,240,255,0.5)]",
        "controls_bg": "bg-[#050A15]/90 border-[#00F0FF]/30", "progress_color": "#00F0FF"
    },
    {
        "id": "02", "name": "blade-runner-2049",
        "bg": "https://images.pexels.com/photos/17195067/pexels-photo-17195067.jpeg",
        "title": "BLADE RUNNER 2049", "font": "font-['Outfit'] font-black tracking-[0.2em]", "overlay": "bg-gradient-to-r from-orange-950/90 via-orange-900/40 to-transparent",
        "eyebrow": "REPLICANT DETECTION INTERLOCK", "font_body": "font-['Manrope']",
        "synopsis": "Officer K, a new blade runner for the Los Angeles Police Department, unearths a long-buried secret that has the potential to plunge what's left of society into chaos. His discovery leads him on a quest to find Rick Deckard...",
        "chips": ['2017', '164 MIN', 'R', '4K DOLBY VISION', 'HEVC · 45GB'], "accent": "text-orange-400",
        "chip_style": "rounded-full bg-orange-950/60 border border-orange-500/40 px-4 py-1.5 uppercase text-xs tracking-wider backdrop-blur-md text-orange-200",
        "logo_style": "text-7xl text-orange-100 text-shadow-glow drop-shadow-[0_0_25px_rgba(255,100,0,0.8)]",
        "controls_bg": "glass border-orange-500/20", "progress_color": "#f97316"
    },
    {
        "id": "03", "name": "the-batman",
        "bg": "https://images.unsplash.com/photo-1757942410771-f53048100e3a?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjA2OTV8MHwxfHNlYXJjaHwxfHxkYXJrJTIwZ290aGljJTIwY2l0eSUyMG5pZ2h0fGVufDB8fHx8MTc3OTM1NDI3OHww&ixlib=rb-4.1.0&q=85",
        "title": "THE BATMAN", "font": "font-['Cinzel'] font-bold tracking-[0.2em]", "overlay": "bg-gradient-to-t from-red-950/95 via-black/90 to-black/30",
        "eyebrow": "VENGEANCE LIVES HERE", "font_body": "font-['Manrope']",
        "synopsis": "When a sadistic serial killer begins murdering key political figures in Gotham, Batman is forced to investigate the city's hidden corruption and question his family's involvement.",
        "chips": ['2022', '176 MIN', 'PG-13', '4K HDR10+', 'REMUX · 70GB'], "accent": "text-red-600",
        "chip_style": "border border-red-900/50 bg-black/80 px-4 py-1.5 uppercase text-xs tracking-[0.2em] text-red-200 shadow-[inset_0_0_10px_rgba(220,38,38,0.3)]",
        "logo_style": "text-8xl text-red-600 drop-shadow-[0_10px_30px_rgba(220,38,38,0.8)]",
        "controls_bg": "bg-black/95 border-red-900/30", "progress_color": "#dc2626"
    },
    {
        "id": "04", "name": "mad-max",
        "bg": "https://images.pexels.com/photos/31415633/pexels-photo-31415633.jpeg",
        "title": "MAD MAX: FURY ROAD", "font": "font-['Bebas_Neue'] tracking-wide", "overlay": "bg-gradient-to-r from-[#1A0B00]/95 via-[#331400]/80 to-transparent",
        "eyebrow": "WHAT A LOVELY DAY", "font_body": "font-['Outfit'] font-light",
        "synopsis": "In a post-apocalyptic wasteland, a woman rebels against a tyrannical ruler in search for her homeland with the aid of a group of female prisoners, a psychotic worshiper, and a drifter named Max.",
        "chips": ['2015', '120 MIN', 'R', '4K HDR', 'MP4 · 22GB'], "accent": "text-[#FF8C00]",
        "chip_style": "bg-[#FF8C00] text-black font-bold px-3 py-1 text-sm tracking-wider uppercase transform -skew-x-12",
        "logo_style": "text-9xl text-[#FFD700] drop-shadow-[4px_4px_0_rgba(200,50,0,1)] uppercase",
        "controls_bg": "bg-[#1A0B00]/90 border-[#FF8C00]/40 transform -skew-x-3", "progress_color": "#FF8C00"
    },
    {
        "id": "05", "name": "oppenheimer",
        "bg": "https://images.pexels.com/photos/35982145/pexels-photo-35982145.jpeg",
        "title": "OPPENHEIMER", "font": "font-['Cormorant_Garamond'] tracking-[0.3em] font-light", "overlay": "bg-gradient-to-t from-black via-black/90 to-transparent grayscale opacity-95",
        "eyebrow": "THE MAN WHO MOVED THE EARTH", "font_body": "font-['Manrope']",
        "synopsis": "The story of American scientist, J. Robert Oppenheimer, and his role in the development of the atomic bomb. A cinematic journey into the mind of genius and the profound moral consequences of creation.",
        "chips": ['2023', '180 MIN', 'R', 'IMAX 70MM 4K', 'ISO · 105GB'], "accent": "text-white",
        "chip_style": "border-b border-white/40 pb-1 px-2 text-xs uppercase tracking-[0.2em] font-light",
        "logo_style": "text-7xl text-white font-light text-shadow",
        "controls_bg": "bg-black/80 border-white/10 rounded-none", "progress_color": "#ffffff"
    },
    {
        "id": "06", "name": "top-gun",
        "bg": "https://images.unsplash.com/photo-1756630645698-3f03d728393c?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjY2NzV8MHwxfHNlYXJjaHwxfHxmaWdodGVyJTIwamV0JTIwZmx5aW5nJTIwc3Vuc2V0fGVufDB8fHx8MTc3OTM1NDI3OHww&ixlib=rb-4.1.0&q=85",
        "title": "TOP GUN: MAVERICK", "font": "font-['JetBrains_Mono'] font-bold tracking-tight", "overlay": "bg-gradient-to-r from-[#001A00]/95 via-[#003300]/70 to-transparent",
        "eyebrow": "MACH 10 CAPABLE", "font_body": "font-['Manrope']",
        "synopsis": "After thirty years, Maverick is still pushing the envelope as a top naval aviator, but must confront ghosts of his past when he leads TOP GUN's elite graduates on a mission that demands the ultimate sacrifice.",
        "chips": ['2022', '130 MIN', 'PG-13', 'IMAX ENHANCED', 'M2TS · 85GB'], "accent": "text-[#00FF00]",
        "chip_style": "border border-[#00FF00]/50 bg-[#00FF00]/10 px-3 py-1 text-xs tracking-widest text-[#00FF00] rounded-sm shadow-[0_0_10px_rgba(0,255,0,0.2)]",
        "logo_style": "text-7xl text-white font-black drop-shadow-[0_4px_10px_rgba(0,255,0,0.4)] italic",
        "controls_bg": "bg-[#001A00]/90 border-[#00FF00]/30", "progress_color": "#00FF00"
    },
    {
        "id": "07", "name": "inception",
        "bg": "https://images.unsplash.com/photo-1687637161528-5c7055db0bae?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjAzOTB8MHwxfHNlYXJjaHwxfHxzdXJyZWFsJTIwY2l0eSUyMGFyY2hpdGVjdHVyZXxlbnwwfHx8fDE3NzkzNTQyNzh8MA&ixlib=rb-4.1.0&q=85",
        "title": "INCEPTION", "font": "font-['Outfit'] font-black tracking-[0.5em]", "overlay": "bg-black/60 backdrop-blur-sm",
        "eyebrow": "DREAM IS REAL", "font_body": "font-['Manrope']",
        "synopsis": "A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O., but his tragic past may doom the project and his team.",
        "chips": ['2010', '148 MIN', 'PG-13', '4K UHD', 'MKV · 65GB'], "accent": "text-gray-300",
        "chip_style": "bg-white/10 px-4 py-2 text-xs tracking-widest uppercase rounded-lg border border-white/30 shadow-xl",
        "logo_style": "text-8xl text-white font-black drop-shadow-[0_20px_40px_rgba(0,0,0,0.8)]",
        "controls_bg": "bg-white/10 backdrop-blur-xl border-white/30", "progress_color": "#ffffff"
    },
    {
        "id": "08", "name": "interstellar",
        "bg": "https://images.pexels.com/photos/27730387/pexels-photo-27730387.jpeg",
        "title": "INTERSTELLAR", "font": "font-['Playfair_Display'] font-normal tracking-[0.4em]", "overlay": "bg-gradient-to-t from-black via-black/80 to-transparent",
        "eyebrow": "MANKIND WAS BORN ON EARTH. IT WAS NEVER MEANT TO DIE HERE.", "font_body": "font-['Outfit'] font-light",
        "synopsis": "A team of explorers travel through a wormhole in space in an attempt to ensure humanity's survival. They must confront the vastness of time and space, driven by love and desperation.",
        "chips": ['2014', '169 MIN', 'PG-13', 'IMAX HDR', 'MKV · 95GB'], "accent": "text-blue-200",
        "chip_style": "px-3 py-1 text-xs tracking-[0.3em] text-white/80 uppercase border border-white/20 rounded-full bg-white/5",
        "logo_style": "text-7xl text-white/95 drop-shadow-[0_0_40px_rgba(255,255,255,0.5)]",
        "controls_bg": "bg-[#020510]/80 border-white/10", "progress_color": "#bfdbfe"
    },
    {
        "id": "09", "name": "john-wick-4",
        "bg": "https://images.pexels.com/photos/18867525/pexels-photo-18867525.jpeg",
        "title": "JOHN WICK: CHAPTER 4", "font": "font-['Unbounded'] font-bold tracking-tight", "overlay": "bg-gradient-to-r from-[#110011]/95 via-[#220022]/70 to-transparent",
        "eyebrow": "BABA YAGA", "font_body": "font-['Manrope']",
        "synopsis": "John Wick uncovers a path to defeating The High Table. But before he can earn his freedom, Wick must face off against a new enemy with powerful alliances across the globe.",
        "chips": ['2023', '169 MIN', 'R', '4K DOLBY VISION', 'MP4 · 60GB'], "accent": "text-yellow-500",
        "chip_style": "border border-yellow-500/60 bg-black/80 text-yellow-500 px-4 py-1.5 text-xs tracking-widest font-bold shadow-[0_0_15px_rgba(234,179,8,0.2)]",
        "logo_style": "text-6xl text-white drop-shadow-[0_0_25px_rgba(255,0,128,0.8)]",
        "controls_bg": "bg-[#110011]/90 border-yellow-500/30", "progress_color": "#eab308"
    },
    {
        "id": "10", "name": "avatar-2",
        "bg": "https://images.unsplash.com/photo-1744366071461-983202aa41c5?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NTYxNzV8MHwxfHNlYXJjaHwxfHxiaW9sdW1pbmVzY2VudCUyMHVuZGVyd2F0ZXIlMjBibHVlfGVufDB8fHx8MTc3OTM1NDI3OHww&ixlib=rb-4.1.0&q=85",
        "title": "AVATAR: THE WAY OF WATER", "font": "font-['Outfit'] font-black tracking-wide", "overlay": "bg-gradient-to-t from-[#001133]/95 via-[#002266]/70 to-transparent",
        "eyebrow": "RETURN TO PANDORA", "font_body": "font-['Manrope']",
        "synopsis": "Jake Sully lives with his newfound family formed on the extrasolar moon Pandora. Once a familiar threat returns to finish what was previously started, Jake must work with Neytiri and the army of the Na'vi race to protect their home.",
        "chips": ['2022', '192 MIN', 'PG-13', '4K 3D HDR', 'REMUX · 120GB'], "accent": "text-cyan-300",
        "chip_style": "rounded-[50%_20%_/_10%_40%] bg-cyan-900/60 border border-cyan-400/60 px-4 py-2 text-xs tracking-wider text-cyan-100 backdrop-blur-md shadow-[0_5px_15px_rgba(0,200,255,0.3)]",
        "logo_style": "text-7xl text-transparent bg-clip-text bg-gradient-to-b from-cyan-100 to-blue-500 drop-shadow-[0_10px_20px_rgba(0,200,255,0.6)]",
        "controls_bg": "bg-[#001133]/80 border-cyan-400/30", "progress_color": "#67e8f9"
    }
]

def generate_overlay(m):
    chips_html = ''.join([f'<span class="{m["chip_style"]}">{c}</span>' for c in m["chips"]])
    return head + f'''
    <img src="{m["bg"]}" class="absolute inset-0 w-full h-full object-cover scale-105 filter saturate-110 brightness-90" />
    <div class="absolute inset-0 {m["overlay"]} z-10"></div>
    
    <!-- Top HUD Elements -->
    <div class="absolute top-12 left-14 z-50">
        <div class="flex items-center space-x-4 bg-black/60 backdrop-blur-xl px-5 py-2.5 rounded border border-white/10 {m["font_body"]} shadow-2xl">
            <div class="w-2.5 h-2.5 rounded-full bg-red-500 animate-pulse shadow-[0_0_10px_rgba(239,68,68,0.8)]"></div>
            <span class="text-white/90 text-[15px] font-bold tracking-widest">▶︎ EXOPLAYER</span>
        </div>
    </div>
    <div class="absolute top-12 right-14 z-50">
        <div class="flex items-center space-x-5 glass-light px-6 py-2.5 rounded {m["font_body"]}">
            <span class="text-white/70 text-sm tracking-widest font-semibold">BUF 12s &middot; 6.2Mbps &middot; ExoPlayer</span>
            <div class="flex space-x-1.5 items-end h-4">
                <div class="w-1 h-2 bg-white/40"></div>
                <div class="w-1 h-3 bg-white/60"></div>
                <div class="w-1 h-4 bg-white animate-pulse shadow-[0_0_8px_rgba(255,255,255,0.8)]"></div>
            </div>
        </div>
    </div>

    <!-- Main Content -->
    <div class="absolute bottom-24 left-20 w-[55%] z-40 {m["font_body"]}">
        <p class="text-[13px] {m["accent"]} tracking-[0.4em] mb-5 uppercase font-bold text-shadow-glow">{m["eyebrow"]}</p>
        <h1 class="{m["font"]} {m["logo_style"]} leading-[1.1] mb-8">{m["title"]}</h1>
        
        <div class="flex flex-wrap items-center gap-4 mb-8">
            {chips_html}
        </div>
        
        <p class="text-[22px] text-white/80 leading-relaxed font-light line-clamp-3 max-w-3xl text-shadow">
            {m["synopsis"]}
        </p>
    </div>
</body>
</html>
'''

def generate_controls(m):
    return head + f'''
    <img src="{m["bg"]}" class="absolute inset-0 w-full h-full object-cover opacity-50 blur-[30px] scale-110 saturate-150" />
    <div class="absolute inset-0 bg-black/50 z-0"></div>
    
    <!-- Controls Layout -->
    <div class="absolute bottom-16 left-1/2 transform -translate-x-1/2 w-[85%] max-w-[1500px] z-50">
        <div class="flex flex-col space-y-8 p-10 {m.get("controls_bg", "glass")} rounded-3xl {m.get("controls_border", "border border-white/10")} shadow-[0_30px_60px_rgba(0,0,0,0.8)] {m["font_body"]}">
            
            <!-- Movie Info Mini -->
            <div class="flex justify-between items-end mb-2">
                <div>
                    <h3 class="{m["font"]} text-4xl text-white mb-2 tracking-wide drop-shadow-lg">{m["title"]}</h3>
                    <p class="{m["accent"]} text-[15px] tracking-[0.3em] uppercase font-bold text-shadow-glow">{m["eyebrow"]}</p>
                </div>
                <div class="text-right">
                    <p class="text-white text-3xl font-light tracking-wider drop-shadow-md">01:42:15 <span class="text-white/40 text-2xl">/ 02:28:00</span></p>
                </div>
            </div>

            <!-- Progress Bar -->
            <div class="relative w-full h-4 bg-black/60 rounded-full overflow-hidden cursor-pointer shadow-[inset_0_2px_8px_rgba(0,0,0,0.8)]">
                <div class="absolute top-0 left-0 h-full w-[65%] bg-white/20"></div>
                <div class="absolute top-0 left-0 h-full w-[60%] rounded-full shadow-[0_0_20px_currentColor]" style="background: {m.get("progress_color", "#fff")}; color: {m.get("progress_color", "#fff")};"></div>
            </div>

            <!-- Action Buttons -->
            <div class="flex justify-between items-center pt-2">
                <div class="flex items-center space-x-8">
                    <button class="w-14 h-14 flex items-center justify-center rounded-full hover:bg-white/10 transition text-white">
                        <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12.066 11.2a1 1 0 000 1.6l5.334 4A1 1 0 0019 16V8a1 1 0 00-1.6-.8l-5.333 4zM4.066 11.2a1 1 0 000 1.6l5.334 4A1 1 0 0011 16V8a1 1 0 00-1.6-.8l-5.334 4z"></path></svg>
                    </button>
                    <button class="w-20 h-20 flex items-center justify-center rounded-full bg-white text-black hover:scale-105 transition shadow-[0_0_30px_rgba(255,255,255,0.3)]">
                        <svg class="w-10 h-10 ml-2" fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"></path></svg>
                    </button>
                    <button class="w-14 h-14 flex items-center justify-center rounded-full hover:bg-white/10 transition text-white">
                        <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11.933 12.8a1 1 0 000-1.6L6.6 7.2A1 1 0 005 8v8a1 1 0 001.6.8l5.333-4zM19.933 12.8a1 1 0 000-1.6l-5.334-4A1 1 0 0013 8v8a1 1 0 001.6.8l5.334-4z"></path></svg>
                    </button>
                </div>
                
                <div class="flex items-center space-x-8 text-white/70">
                    <button class="hover:text-white transition"><svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"></path></svg></button>
                    <button class="hover:text-white transition"><svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path></svg></button>
                    <button class="hover:text-white transition"><svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4"></path></svg></button>
                </div>
            </div>
        </div>
    </div>
</body>
</html>
'''

for m in movies:
    with open(f'/app/frontend/public/design_mockups/overlays/{m["id"]}-{m["name"]}.html', 'w') as f:
        f.write(generate_overlay(m))
    with open(f'/app/frontend/public/design_mockups/controls/{m["id"]}-{m["name"]}.html', 'w') as f:
        f.write(generate_controls(m))

# Generate Index
index_html = head + '''
<div class="p-16 min-h-screen bg-[#050505] overflow-auto">
    <h1 class="text-6xl font-['Outfit'] font-black text-white mb-16 tracking-[0.2em] text-center drop-shadow-2xl">EXOPLAYER PREMIUM MOCKUPS</h1>
    <div class="grid grid-cols-2 gap-16 max-w-[1800px] mx-auto">
'''
for m in movies:
    index_html += f'''
        <div class="flex flex-col space-y-6">
            <h2 class="text-3xl font-['JetBrains_Mono'] text-white/70 uppercase tracking-widest">{m["title"]}</h2>
            <div class="relative w-full aspect-video border border-white/20 rounded-2xl overflow-hidden hover:border-white/60 transition duration-500 shadow-2xl">
                <iframe src="./overlays/{m["id"]}-{m["name"]}.html" class="w-[1920px] h-[1080px] absolute top-0 left-0" style="transform: scale(calc(100% / 1920 * 100)); transform-origin: top left; pointer-events: none;"></iframe>
                <a href="./overlays/{m["id"]}-{m["name"]}.html" target="_blank" class="absolute inset-0 z-10 flex items-center justify-center bg-black/70 opacity-0 hover:opacity-100 transition duration-300 backdrop-blur-sm">
                    <span class="bg-white text-black px-8 py-4 rounded-full font-bold tracking-widest text-lg">VIEW OVERLAY (1920x1080)</span>
                </a>
            </div>
            <div class="relative w-full aspect-video border border-white/20 rounded-2xl overflow-hidden hover:border-white/60 transition duration-500 shadow-2xl">
                <iframe src="./controls/{m["id"]}-{m["name"]}.html" class="w-[1920px] h-[1080px] absolute top-0 left-0" style="transform: scale(calc(100% / 1920 * 100)); transform-origin: top left; pointer-events: none;"></iframe>
                <a href="./controls/{m["id"]}-{m["name"]}.html" target="_blank" class="absolute inset-0 z-10 flex items-center justify-center bg-black/70 opacity-0 hover:opacity-100 transition duration-300 backdrop-blur-sm">
                    <span class="bg-white text-black px-8 py-4 rounded-full font-bold tracking-widest text-lg">VIEW CONTROLS (1920x1080)</span>
                </a>
            </div>
        </div>
    '''
index_html += '''
    </div>
</div>
</body></html>
'''
with open('/app/frontend/public/design_mockups/index.html', 'w') as f:
    f.write(index_html)

print("Successfully generated 21 high-fidelity mockups.")
