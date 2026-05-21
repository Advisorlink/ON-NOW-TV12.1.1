"""
Generate 20 Dune-Part-Two mockups (10 overlays + 10 controls) all at the
fidelity of the ORIGINAL approved Dune mockups (see ./exoplayer-overlay-idle.html
and ./exoplayer-controls.html — those are the quality bar).

Each variant introduces ONE distinct design twist while keeping the
shared design language (Tailwind, Outfit + JetBrains Mono, Phosphor
icons, cyan #5DC8FF accent, navy #020610 base, glass-blur panels).
"""
from pathlib import Path
import html

OUT = Path("/app/frontend/public/design_mockups")
(OUT / "overlays").mkdir(parents=True, exist_ok=True)
(OUT / "controls").mkdir(parents=True, exist_ok=True)

# Clean slate — wipe old files
for p in (OUT / "overlays").glob("*.html"): p.unlink()
for p in (OUT / "controls").glob("*.html"): p.unlink()

DUNE_BG = "https://images.unsplash.com/photo-1682687220063-4742bd7fd538?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjY2NjV8MHwxfHNlYXJjaHwxfHxjaW5lbWF0aWMlMjBzY2klMjBmaSUyMG1vdmllJTIwbGFuZHNjYXBlfGVufDB8fHx8MTc3OTM1MjQ3MXww&ixlib=rb-4.1.0&q=85"

SYNOPSIS = "Paul Atreides unites with Chani and the Fremen while on a warpath of revenge against the conspirators who destroyed his family. Facing a choice between the love of his life and the fate of the known universe, he endeavors to prevent a terrible future only he can foresee."

HEAD = """<!DOCTYPE html><html lang="en" class="dark"><head><meta charset="UTF-8">
<title>{title}</title>
<script src="https://cdn.tailwindcss.com"></script>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@200;300;500;700;900&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<script src="https://unpkg.com/@phosphor-icons/web"></script>
<script>tailwind.config={{darkMode:'class',theme:{{extend:{{fontFamily:{{sans:['Outfit','sans-serif'],mono:['JetBrains Mono','monospace']}},colors:{{navy:{{900:'#020610',800:'#0A1322',700:'#0D121C'}},cyan:{{primary:'#5DC8FF',accent:'#7CF1F1'}}}}}}}}}}</script>
<style>
body{{margin:0;background:#000;color:#fff;overflow:hidden;font-family:'Outfit',sans-serif}}
.tv-container{{width:1920px;height:1080px;position:relative;transform-origin:top left;transform:scale(min(100vw/1920,100vh/1080))}}
.glass-panel{{background:rgba(10,19,34,0.4);backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);border:1px solid rgba(255,255,255,0.05)}}
.glass-card{{background:rgba(10,19,34,0.85);backdrop-filter:blur(40px);-webkit-backdrop-filter:blur(40px);border:1px solid rgba(93,200,255,0.2);border-radius:24px;box-shadow:0 24px 64px rgba(0,0,0,0.6),inset 0 1px 0 rgba(255,255,255,0.1)}}
.chip{{display:flex;align-items:center;justify-content:center;padding:4px 10px;border-radius:6px;font-family:'JetBrains Mono',monospace;font-size:14px;font-weight:700}}
.chip-outline{{border:1px solid rgba(255,255,255,0.2);color:rgba(255,255,255,0.8)}}
.chip-cyan{{background:rgba(93,200,255,0.15);border:1px solid rgba(93,200,255,0.4);color:#5DC8FF}}
.tv-btn{{display:flex;align-items:center;justify-content:center;width:64px;height:64px;border-radius:32px;background:rgba(255,255,255,0.05);border:2px solid transparent;transition:all .22s cubic-bezier(.4,0,.2,1);color:rgba(255,255,255,0.8);cursor:pointer}}
.tv-btn i{{font-size:32px}}
.tv-btn.active,.tv-btn:hover{{background:#5DC8FF;color:#020610;transform:scale(1.1);border-color:rgba(255,255,255,0.5);box-shadow:0 0 24px rgba(93,200,255,0.4)}}
.tv-btn-large{{width:88px;height:88px;border-radius:44px}}
.tv-btn-large i{{font-size:40px}}
.scrubber-track{{width:100%;height:8px;background:rgba(255,255,255,0.2);border-radius:4px;position:relative;cursor:pointer}}
.scrubber-fill{{height:100%;background:#5DC8FF;border-radius:4px;width:65%;position:relative}}
.scrubber-thumb{{position:absolute;right:-8px;top:50%;transform:translateY(-50%);width:24px;height:24px;background:#fff;border-radius:50%;box-shadow:0 0 12px rgba(93,200,255,0.8)}}
.fade-bottom{{background:linear-gradient(to top,rgba(2,6,16,0.92) 0%,rgba(2,6,16,0.55) 50%,transparent 100%)}}
.fade-left{{background:linear-gradient(to right,#020610 0%,rgba(2,6,16,0.92) 30%,transparent 65%)}}
.fade-right{{background:linear-gradient(to left,#020610 0%,rgba(2,6,16,0.92) 30%,transparent 65%)}}
.fade-top{{background:linear-gradient(to bottom,rgba(2,6,16,0.92) 0%,rgba(2,6,16,0.55) 50%,transparent 100%)}}
.fade-vignette{{background:radial-gradient(ellipse at center,transparent 30%,rgba(2,6,16,0.85) 100%)}}
{extra_css}
</style></head><body class="flex items-center justify-center min-h-screen bg-black">
<div class="tv-container relative overflow-hidden bg-navy-900">
<img src="{bg}" class="absolute inset-0 w-full h-full object-cover opacity-80" alt="bg">
"""

BADGE_BUF = """
<div class="absolute top-12 left-14 flex items-center gap-3 z-50">
  <div class="chip chip-cyan uppercase tracking-widest text-xs">
    <i class="ph-fill ph-play text-cyan-accent mr-2"></i> Exoplayer
  </div>
</div>
<div class="absolute top-12 right-14 z-50">
  <div class="font-mono text-[13px] text-cyan-primary border border-cyan-primary/30 px-3 py-1.5 rounded bg-navy-900/80 backdrop-blur-sm tracking-wide">BUF 12s &middot; 6.2Mbps &middot; ExoPlayer</div>
</div>
"""

FOOT = "</div></body></html>"


def dune_logo(big=False):
    sz = 96 if big else 72
    return f'''<svg viewBox="0 0 460 140" class="drop-shadow-2xl" style="height:{sz+30}px">
  <text x="0" y="98" font-family="Outfit" font-weight="900" font-size="{sz}" fill="#fff" letter-spacing="-3">DUNE</text>
  <text x="{195 if not big else 256}" y="98" font-family="Outfit" font-weight="200" font-size="{sz}" fill="#fff" letter-spacing="-3">PART TWO</text>
</svg>'''


CHIPS = '''<div class="chip chip-cyan text-[11px] py-0.5 px-2">4K HDR</div>
<div class="chip chip-outline text-[11px] py-0.5 px-2">🇬🇧 ENG</div>
<div class="chip chip-outline text-[11px] py-0.5 px-2">ON NOW</div>
<div class="chip chip-outline text-[11px] py-0.5 px-2">24.5 GB</div>'''


def meta_strip(extra=""):
    return f'''<div class="flex items-center gap-3 mb-6 text-white/70 font-mono text-sm">
  <span>2024</span><span class="text-white/40">&bull;</span><span>2h 46m</span>
  <span class="text-white/40">&bull;</span>
  <span class="border border-white/30 px-1.5 rounded-sm text-xs">PG-13</span>
  <span class="text-white/40">&bull;</span>{CHIPS}{extra}
</div>'''


# ─────────────── 10 OVERLAY VARIANTS ───────────────

OVERLAYS = []

# O01 — Classic Left-Fade (matches the user-loved original)
OVERLAYS.append(("O01", "Classic Left-Fade", f'''
<div class="absolute inset-y-0 left-0 w-[45%] fade-left pointer-events-none"></div>
{BADGE_BUF}
<div class="absolute top-[45%] left-14 -translate-y-1/2 w-[40%] z-10">
  <div class="mb-6 w-[400px]">{dune_logo()}</div>
  <h1 class="text-5xl font-medium text-white mb-4 tracking-tight drop-shadow-lg">Dune: Part Two</h1>
  {meta_strip()}
  <p class="text-xl text-white/80 leading-relaxed drop-shadow-md line-clamp-3 w-11/12">{SYNOPSIS}</p>
</div>
'''))

# O02 — Bottom-Glass Block
OVERLAYS.append(("O02", "Bottom-Glass Block", f'''
{BADGE_BUF}
<div class="absolute inset-x-0 bottom-0 h-[42%] glass-panel border-t border-cyan-primary/15 z-10"></div>
<div class="absolute left-16 right-16 bottom-16 flex items-end gap-12 z-20">
  <div class="flex-shrink-0 w-[440px]">{dune_logo(big=False)}</div>
  <div class="flex-1 max-w-[700px]">
    <div class="chip chip-cyan inline-flex uppercase tracking-widest text-[10px] mb-3">Synopsis</div>
    <p class="text-lg text-white/85 leading-relaxed mb-5 line-clamp-3">{SYNOPSIS}</p>
    {meta_strip()}
  </div>
</div>
'''))

# O03 — Right-Aligned Cinematic
OVERLAYS.append(("O03", "Right-Aligned Cinematic", f'''
<div class="absolute inset-y-0 right-0 w-[45%] fade-right pointer-events-none"></div>
{BADGE_BUF}
<div class="absolute top-[45%] right-14 -translate-y-1/2 w-[40%] text-right z-10 flex flex-col items-end">
  <div class="chip chip-cyan inline-flex uppercase tracking-[0.3em] text-[10px] mb-6">Now Playing</div>
  <div class="mb-6 w-[400px]">{dune_logo()}</div>
  <h1 class="text-4xl font-light text-white mb-4 tracking-tight drop-shadow-lg">Dune: Part Two</h1>
  <div class="flex flex-row-reverse items-center gap-3 mb-6 text-white/70 font-mono text-sm">{CHIPS}<span class="text-white/40">&bull;</span><span class="border border-white/30 px-1.5 rounded-sm text-xs">PG-13</span><span class="text-white/40">&bull;</span><span>2h 46m</span><span class="text-white/40">&bull;</span><span>2024</span></div>
  <p class="text-xl text-white/80 leading-relaxed drop-shadow-md line-clamp-3 max-w-[640px]">{SYNOPSIS}</p>
</div>
'''))

# O04 — Floating Glass Card
OVERLAYS.append(("O04", "Floating Glass Card", f'''
<div class="absolute inset-0 bg-navy-900/35 pointer-events-none"></div>
{BADGE_BUF}
<div class="absolute top-[45%] left-1/2 -translate-x-1/2 -translate-y-1/2 z-10">
  <div class="glass-card w-[920px] p-12">
    <div class="flex items-start gap-10">
      <div class="flex-shrink-0 w-[380px]">{dune_logo()}</div>
      <div class="flex-1">
        <h1 class="text-3xl font-medium text-white mb-3 tracking-tight">Dune: Part Two</h1>
        {meta_strip()}
        <p class="text-base text-white/80 leading-relaxed line-clamp-3">{SYNOPSIS}</p>
      </div>
    </div>
  </div>
</div>
'''))

# O05 — Top Banner + Synopsis Below
OVERLAYS.append(("O05", "Top Banner Hud", f'''
<div class="absolute inset-x-0 top-0 h-[28%] fade-top pointer-events-none"></div>
{BADGE_BUF}
<div class="absolute left-14 right-14 top-32 flex items-end justify-between gap-10 z-10">
  <div class="w-[460px]">{dune_logo()}</div>
  <div class="text-right max-w-[660px]">
    <div class="flex justify-end items-center gap-3 mb-4 text-white/70 font-mono text-sm">{CHIPS}</div>
    <p class="text-base text-white/80 leading-relaxed drop-shadow-md line-clamp-2">{SYNOPSIS}</p>
  </div>
</div>
'''))

# O06 — Vignette + Centred
OVERLAYS.append(("O06", "Vignette Centred", f'''
<div class="absolute inset-0 fade-vignette pointer-events-none"></div>
{BADGE_BUF}
<div class="absolute inset-0 flex flex-col items-center justify-center text-center z-10 px-32">
  <div class="chip chip-cyan inline-flex uppercase tracking-[0.4em] text-[10px] mb-8">Tonight's Feature</div>
  <div class="mb-6 w-[520px]">{dune_logo(big=True)}</div>
  <h1 class="text-2xl font-light text-white/90 mb-6 tracking-[0.25em] uppercase">Dune: Part Two</h1>
  <div class="flex items-center gap-3 mb-8 text-white/70 font-mono text-sm">{CHIPS}</div>
  <p class="text-lg text-white/75 leading-relaxed line-clamp-3 max-w-[820px]">{SYNOPSIS}</p>
</div>
'''))

# O07 — Apple-TV Minimal
OVERLAYS.append(("O07", "Apple-TV Minimal", f'''
<div class="absolute inset-0 bg-navy-900/45 pointer-events-none"></div>
{BADGE_BUF}
<div class="absolute bottom-32 left-32 w-[640px] z-10">
  <div class="chip chip-cyan inline-flex uppercase tracking-[0.35em] text-[10px] mb-6 opacity-90">Apple TV+ &middot; Now Playing</div>
  <div class="mb-6 w-[380px]">{dune_logo()}</div>
  <h1 class="text-3xl font-light text-white mb-3 tracking-tight">Dune: Part Two</h1>
  <p class="text-base font-light text-white/75 leading-relaxed line-clamp-3 mb-6">{SYNOPSIS}</p>
  <div class="flex items-center gap-4 text-white/65 font-mono text-xs">
    <span>2024</span><span>&bull;</span><span>2h 46m</span><span>&bull;</span><span>PG-13</span><span>&bull;</span><span>4K HDR</span><span>&bull;</span><span>🇬🇧</span><span>&bull;</span><span>24.5 GB</span>
  </div>
</div>
'''))

# O08 — Vertical Sidebar
OVERLAYS.append(("O08", "Vertical Sidebar", f'''
<div class="absolute inset-y-0 left-0 w-[420px] bg-navy-900/85 backdrop-blur-2xl border-r border-cyan-primary/15 z-10"></div>
{BADGE_BUF}
<div class="absolute left-14 top-32 bottom-32 w-[340px] flex flex-col z-20">
  <div class="mb-8 w-full">{dune_logo()}</div>
  <h1 class="text-3xl font-medium text-white mb-3 tracking-tight">Dune: Part Two</h1>
  <div class="flex items-center gap-2 mb-4 text-white/70 font-mono text-xs">
    <span>2024</span><span class="text-white/40">&bull;</span><span>2h 46m</span><span class="text-white/40">&bull;</span><span class="border border-white/30 px-1 rounded-sm text-[10px]">PG-13</span>
  </div>
  <div class="flex flex-wrap gap-2 mb-6">{CHIPS}</div>
  <div class="chip chip-cyan inline-flex uppercase tracking-widest text-[10px] mb-3 self-start">Synopsis</div>
  <p class="text-sm text-white/80 leading-relaxed flex-1">{SYNOPSIS}</p>
</div>
'''))

# O09 — Diagonal Split
OVERLAYS.append(("O09", "Diagonal Split", f'''
<div class="absolute inset-0 pointer-events-none" style="background:linear-gradient(45deg,#020610 0%,rgba(2,6,16,0.85) 30%,transparent 60%);"></div>
{BADGE_BUF}
<div class="absolute left-14 bottom-20 w-[44%] z-10">
  <div class="chip chip-cyan inline-flex uppercase tracking-[0.35em] text-[10px] mb-6">Watching now</div>
  <div class="mb-6 w-[440px]">{dune_logo()}</div>
  <h1 class="text-5xl font-medium tracking-tight mb-4" style="background:linear-gradient(135deg,#fff,#5DC8FF);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;">Dune: Part Two</h1>
  {meta_strip()}
  <p class="text-lg text-white/80 leading-relaxed line-clamp-3">{SYNOPSIS}</p>
</div>
'''))

# O10 — Cyberpunk Cinematic
OVERLAYS.append(("O10", "Cyberpunk Cinematic", f'''
<div class="absolute inset-0 fade-left pointer-events-none"></div>
{BADGE_BUF}
<div class="absolute top-[45%] left-14 -translate-y-1/2 w-[42%] z-10">
  <div class="flex items-center gap-3 mb-6">
    <div class="h-px w-12 bg-cyan-accent"></div>
    <div class="font-mono text-[10px] text-cyan-accent uppercase tracking-[0.4em]">// Signal Lock</div>
    <div class="h-px flex-1 bg-cyan-accent/40"></div>
  </div>
  <div class="mb-6 w-[460px]" style="filter:drop-shadow(0 0 24px rgba(124,241,241,0.45))">{dune_logo()}</div>
  <h1 class="text-4xl font-light mb-4 tracking-[0.05em]" style="color:#7CF1F1;text-shadow:0 0 18px rgba(124,241,241,0.6);">DUNE: PART TWO</h1>
  <div class="flex items-center gap-3 mb-6 text-cyan-primary/90 font-mono text-sm">
    <span>2024</span><span class="opacity-50">//</span><span>2h 46m</span><span class="opacity-50">//</span>
    <span class="border border-cyan-primary/40 px-1.5 rounded-sm text-xs">PG-13</span>
  </div>
  <div class="flex flex-wrap gap-2 mb-6">{CHIPS}</div>
  <p class="text-lg text-white/80 leading-relaxed line-clamp-3 border-l-2 border-cyan-accent/50 pl-4">{SYNOPSIS}</p>
</div>
'''))


# ─────────────── 10 CONTROL DOCK VARIANTS ───────────────

CONTROLS = []
SCRUB = '''<div class="flex-1 scrubber-track"><div class="scrubber-fill"><div class="scrubber-thumb"></div></div></div>'''
TIMES_LEFT = '<span class="font-mono text-lg font-bold text-cyan-primary tracking-wider w-24 text-right">01:48:22</span>'
TIMES_RIGHT = '<span class="font-mono text-lg text-white/60 tracking-wider w-24">02:46:00</span>'

def btn(icon, klass=""):
    return f'<button class="tv-btn {klass}"><i class="{icon}"></i></button>'

ALL_BTNS_LEFT = btn("ph ph-speaker-high") + btn("ph ph-subtitles") + btn("ph ph-plugs")
ALL_BTNS_CENTER = btn("ph-fill ph-clock-counter-clockwise") + btn("ph-fill ph-pause", "tv-btn-large active") + btn("ph-fill ph-clock-clockwise")
ALL_BTNS_RIGHT = btn("ph ph-gear-six") + btn("ph ph-screencast") + btn("ph ph-corners-out")

# C01 — Classic Bottom Dock (the original loved one)
CONTROLS.append(("C01", "Classic Bottom Dock", f'''
<div class="absolute inset-x-0 bottom-0 h-[380px] fade-bottom z-30 flex flex-col justify-end px-16 pb-16">
  <div class="flex items-center gap-6 mb-10">{TIMES_LEFT}{SCRUB}{TIMES_RIGHT}</div>
  <div class="flex items-center justify-between">
    <div class="flex items-center gap-4">{ALL_BTNS_LEFT}</div>
    <div class="flex items-center gap-6 absolute left-1/2 -translate-x-1/2">{ALL_BTNS_CENTER}</div>
    <div class="flex items-center gap-4">{ALL_BTNS_RIGHT}</div>
  </div>
</div>
'''))

# C02 — Floating Glass Pill
CONTROLS.append(("C02", "Floating Glass Pill", f'''
<div class="absolute left-1/2 bottom-16 -translate-x-1/2 z-30">
  <div class="glass-card rounded-full flex items-center gap-3 px-8 py-4" style="border-radius:999px;">
    {ALL_BTNS_LEFT}
    <div class="w-px h-10 bg-white/15 mx-2"></div>
    {ALL_BTNS_CENTER}
    <div class="w-px h-10 bg-white/15 mx-2"></div>
    {ALL_BTNS_RIGHT}
  </div>
  <div class="mt-4 flex items-center gap-4 px-4">
    <span class="font-mono text-sm font-bold text-cyan-primary">01:48:22</span>
    <div class="w-[420px] scrubber-track">{SCRUB.replace('flex-1 ', '')}</div>
    <span class="font-mono text-sm text-white/60">02:46:00</span>
  </div>
</div>
'''))

# C03 — Apple TV Heavy Blur
CONTROLS.append(("C03", "Apple TV Heavy Blur", f'''
<div class="absolute inset-x-0 bottom-0 h-[28%] z-30" style="background:rgba(0,0,0,0.45);backdrop-filter:blur(32px);-webkit-backdrop-filter:blur(32px);border-top:1px solid rgba(255,255,255,0.08);">
  <div class="absolute inset-x-20 top-8">
    <div class="h-0.5 bg-white/20 relative rounded-full">
      <div class="h-full w-[65%] bg-white rounded-full"></div>
    </div>
    <div class="flex justify-between mt-3 font-mono text-sm">
      <span class="text-white/85">01:48:22</span>
      <span class="text-white/55">−02:46:00</span>
    </div>
  </div>
  <div class="absolute inset-x-0 bottom-6 flex items-center justify-center gap-12">
    {btn("ph ph-speaker-high")}{btn("ph-fill ph-clock-counter-clockwise")}{btn("ph-fill ph-pause","tv-btn-large active")}{btn("ph-fill ph-clock-clockwise")}{btn("ph ph-subtitles")}
  </div>
</div>
'''))

# C04 — Netflix Flat Split
CONTROLS.append(("C04", "Netflix Flat Split", f'''
<div class="absolute inset-x-0 bottom-0 h-[300px] fade-bottom z-30 px-12 pb-12">
  <div class="absolute inset-x-12 top-12">{SCRUB.replace('flex-1 ', '')}</div>
  <div class="absolute inset-x-12 bottom-12 flex items-center justify-between">
    <div class="flex items-center gap-3">
      {btn("ph-fill ph-pause", "tv-btn-large active")}{btn("ph-fill ph-clock-counter-clockwise")}{btn("ph-fill ph-clock-clockwise")}{btn("ph ph-speaker-high")}
      <div class="flex flex-col ml-4">
        <span class="text-white text-lg font-medium leading-tight">Dune: Part Two</span>
        <span class="font-mono text-xs text-white/55">01:48:22 / 02:46:00</span>
      </div>
    </div>
    <div class="flex items-center gap-3">{btn("ph ph-subtitles")}{btn("ph ph-gear-six")}{btn("ph ph-corners-out")}</div>
  </div>
</div>
'''))

# C05 — Bento Corner Cluster
CONTROLS.append(("C05", "Bento Corner Cluster", f'''
<div class="absolute right-12 bottom-12 z-30">
  <div class="glass-card p-6 grid gap-4" style="grid-template-columns:auto 1fr;">
    <div class="flex flex-col items-center gap-2 py-2 px-3 bg-white/5 rounded-2xl">
      <div class="w-2 h-44 bg-white/15 rounded-full relative overflow-hidden"><div class="absolute bottom-0 w-full bg-cyan-primary rounded-full" style="height:65%;box-shadow:0 0 10px rgba(93,200,255,.6)"></div></div>
      <span class="font-mono text-[10px] text-white/60">65%</span>
    </div>
    <div class="flex flex-col gap-3 min-w-[320px]">
      <div class="flex items-center justify-center gap-3">{ALL_BTNS_CENTER}</div>
      <div class="flex items-center justify-between font-mono text-xs">
        <span class="text-cyan-primary">01:48:22</span><span class="text-white/55">02:46:00</span>
      </div>
      <div class="flex items-center justify-center gap-2 pt-2 border-t border-white/8">{btn("ph ph-speaker-high")}{btn("ph ph-subtitles")}{btn("ph ph-gear-six")}{btn("ph ph-corners-out")}</div>
    </div>
  </div>
</div>
'''))

# C06 — Cyberpunk Neon Deck
CONTROLS.append(("C06", "Cyberpunk Neon Deck", f'''
<div class="absolute inset-x-0 bottom-0 h-[260px] z-30" style="background:linear-gradient(to top,#020610,transparent);border-top:1px solid rgba(124,241,241,0.35);">
  <div class="absolute inset-x-16 top-10">
    <div class="flex items-center justify-between font-mono text-xs mb-3" style="color:#7CF1F1;text-shadow:0 0 8px rgba(124,241,241,0.6);letter-spacing:0.18em;">
      <span>[ 01:48:22 ]</span><span>// 65% //</span><span>[ 02:46:00 ]</span>
    </div>
    <div class="h-[3px] bg-cyan-accent/20 relative" style="clip-path:polygon(0 0,100% 0,calc(100% - 8px) 100%,0 100%);">
      <div class="h-full bg-cyan-accent" style="width:65%;box-shadow:0 0 14px rgba(124,241,241,0.8);"></div>
    </div>
  </div>
  <div class="absolute inset-x-0 bottom-8 flex items-center justify-center gap-8">
    {btn("ph ph-speaker-high")}{btn("ph ph-subtitles")}{btn("ph-fill ph-clock-counter-clockwise")}{btn("ph-fill ph-pause","tv-btn-large active")}{btn("ph-fill ph-clock-clockwise")}{btn("ph ph-gear-six")}{btn("ph ph-corners-out")}
  </div>
</div>
'''))

# C07 — Radial Arc
CONTROLS.append(("C07", "Radial Arc", f'''
<div class="absolute inset-x-0 bottom-0 h-[280px] fade-bottom z-30">
  <div class="absolute inset-x-32 top-14">{SCRUB.replace('flex-1 ', '')}</div>
  <div class="absolute inset-x-0 bottom-8 flex items-end justify-center gap-8">
    <div class="flex flex-col items-center mb-8">{btn("ph ph-speaker-high")}</div>
    <div class="flex flex-col items-center mb-4">{btn("ph ph-subtitles")}</div>
    <div class="flex flex-col items-center">{btn("ph-fill ph-clock-counter-clockwise","tv-btn-large")}</div>
    <div class="flex flex-col items-center">{btn("ph-fill ph-pause","tv-btn-large active")}<span class="mt-3 font-mono text-xs text-cyan-primary">01:48:22</span></div>
    <div class="flex flex-col items-center">{btn("ph-fill ph-clock-clockwise","tv-btn-large")}</div>
    <div class="flex flex-col items-center mb-4">{btn("ph ph-gear-six")}</div>
    <div class="flex flex-col items-center mb-8">{btn("ph ph-corners-out")}</div>
  </div>
</div>
'''))

# C08 — Mac OS Glass Dock
CONTROLS.append(("C08", "MacOS Glass Dock", f'''
<div class="absolute left-1/2 bottom-12 -translate-x-1/2 z-30">
  <div class="glass-card flex items-center gap-3 px-8 py-4">
    {ALL_BTNS_LEFT}
    <div class="w-px h-10 bg-white/15 mx-2"></div>
    {ALL_BTNS_CENTER}
    <div class="w-px h-10 bg-white/15 mx-2"></div>
    {ALL_BTNS_RIGHT}
  </div>
  <div class="mt-3 flex items-center gap-3 px-2">
    <span class="font-mono text-sm font-bold text-cyan-primary">01:48:22</span>
    <div class="w-[420px] scrubber-track">{SCRUB.replace('flex-1 ', '')}</div>
    <span class="font-mono text-sm text-white/60">02:46:00</span>
  </div>
</div>
'''))

# C09 — Vertical Left Column
CONTROLS.append(("C09", "Vertical Left Column", f'''
<div class="absolute left-0 inset-y-0 w-[120px] z-30" style="background:rgba(10,19,34,0.78);backdrop-filter:blur(24px);border-right:1px solid rgba(93,200,255,0.18);">
  <div class="absolute inset-x-0 top-1/2 -translate-y-1/2 flex flex-col items-center gap-4">
    {btn("ph ph-speaker-high")}{btn("ph ph-subtitles")}
    <div class="my-2 w-10 h-px bg-white/15"></div>
    {btn("ph-fill ph-clock-counter-clockwise")}{btn("ph-fill ph-pause","tv-btn-large active")}{btn("ph-fill ph-clock-clockwise")}
    <div class="my-2 w-10 h-px bg-white/15"></div>
    {btn("ph ph-gear-six")}{btn("ph ph-corners-out")}
  </div>
</div>
<div class="absolute left-[140px] top-1/2 -translate-y-1/2 h-[60%] w-[6px] bg-white/15 rounded-full overflow-hidden z-30">
  <div class="absolute bottom-0 w-full bg-cyan-primary rounded-full" style="height:65%;box-shadow:0 0 12px rgba(93,200,255,.6);"></div>
</div>
<div class="absolute left-[170px] bottom-12 z-30 font-mono">
  <div class="text-sm font-bold text-cyan-primary">01:48:22</div>
  <div class="text-xs text-white/55 mt-1">02:46:00</div>
</div>
'''))

# C10 — Minimalist Hover Reveal
CONTROLS.append(("C10", "Minimalist Hover", f'''
<div class="absolute inset-x-0 bottom-0 h-0.5 bg-white/10 z-30">
  <div class="h-full w-[65%] bg-white"></div>
</div>
<div class="absolute left-1/2 bottom-20 -translate-x-1/2 z-30">
  <div class="w-[110px] h-[110px] rounded-full flex items-center justify-center" style="background:rgba(255,255,255,0.10);backdrop-filter:blur(20px);border:1px solid rgba(255,255,255,0.30);">
    <i class="ph-fill ph-pause text-white" style="font-size:54px"></i>
  </div>
</div>
<div class="absolute left-1/2 bottom-44 -translate-x-1/2 flex items-center gap-8 opacity-50 z-30">
  <i class="ph ph-speaker-high text-white text-2xl"></i>
  <i class="ph ph-subtitles text-white text-2xl"></i>
  <i class="ph-fill ph-clock-counter-clockwise text-white text-2xl"></i>
  <i class="ph-fill ph-clock-clockwise text-white text-2xl"></i>
  <i class="ph ph-gear-six text-white text-2xl"></i>
  <i class="ph ph-corners-out text-white text-2xl"></i>
</div>
<div class="absolute left-1/2 bottom-8 -translate-x-1/2 font-mono text-xs text-white/70 tracking-widest z-30">01:48:22 / 02:46:00</div>
'''))


def write_overlay(idx_label, name, body):
    fname = f"{idx_label.lower()}-{name.lower().replace(' ', '-')}.html"
    title = f"O — {name}"
    head = HEAD.format(title=title, extra_css="", bg=DUNE_BG)
    (OUT / "overlays" / fname).write_text(head + body + FOOT)
    return fname


def write_control(idx_label, name, body):
    fname = f"{idx_label.lower()}-{name.lower().replace(' ', '-')}.html"
    title = f"C — {name}"
    head = HEAD.format(title=title, extra_css="", bg=DUNE_BG)
    # Controls reuse the same backend badge + buf hud
    (OUT / "controls" / fname).write_text(head + BADGE_BUF + body + FOOT)
    return fname


overlay_files = [(lab, name, write_overlay(lab, name, body)) for lab, name, body in OVERLAYS]
control_files = [(lab, name, write_control(lab, name, body)) for lab, name, body in CONTROLS]

print(f"✓ Wrote {len(overlay_files)} overlay variants")
print(f"✓ Wrote {len(control_files)} control variants")


# ─────────────── INDEX GALLERY (scroll-grid) ───────────────

def card(label, name, href):
    return f'''<a href="{href}" target="_blank" class="card group">
  <div class="card-frame"><iframe src="{href}" scrolling="no" tabindex="-1"></iframe></div>
  <div class="card-meta">
    <span class="num">{label}</span>
    <span class="card-title">{name}</span>
  </div>
</a>'''

ov_cards = "\n".join(card(l, n, f"overlays/{f}") for l, n, f in overlay_files)
ct_cards = "\n".join(card(l, n, f"controls/{f}") for l, n, f in control_files)

INDEX = f'''<!DOCTYPE html><html><head><meta charset="utf-8">
<title>ExoPlayer mockups · 10 × 2 · Dune Part Two</title>
<script src="https://cdn.tailwindcss.com"></script>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@200;300;400;700;900&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
body {{ margin:0; background:#020610; color:#fff; font-family:'Outfit',sans-serif; min-height:100vh; padding:56px 64px 96px; }}
h1 {{ font-weight:200; font-size:48px; letter-spacing:-0.03em; margin-bottom:8px; }}
h2 {{ font-weight:300; font-size:28px; letter-spacing:-0.02em; margin:64px 0 24px; }}
.eyebrow {{ font-family:'JetBrains Mono',monospace; font-size:11px; letter-spacing:0.32em; text-transform:uppercase; color:#7CF1F1; margin-bottom:16px; }}
.subtitle {{ color:#94A3B8; font-size:15px; max-width:680px; line-height:1.55; }}
.grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(440px,1fr)); gap:28px; }}
.card {{ display:block; text-decoration:none; color:inherit; border-radius:18px; overflow:hidden;
  background:#0A1322; border:1px solid rgba(255,255,255,0.07);
  transition:transform .25s ease, border-color .25s ease, box-shadow .25s ease; }}
.card:hover {{ transform:translateY(-4px); border-color:rgba(93,200,255,0.45);
  box-shadow:0 22px 60px rgba(93,200,255,0.18); }}
.card-frame {{ position:relative; width:100%; aspect-ratio:16/9; overflow:hidden; background:#000; }}
.card-frame iframe {{ position:absolute; left:0; top:0; width:1920px; height:1080px;
  transform:scale(calc(440 / 1920)); transform-origin:top left; border:0; pointer-events:none; }}
.card-meta {{ display:flex; align-items:center; gap:14px; padding:14px 18px;
  background:rgba(2,6,16,0.6); border-top:1px solid rgba(93,200,255,0.15); }}
.num {{ font-family:'JetBrains Mono',monospace; font-weight:700;
  font-size:11px; color:#7CF1F1; letter-spacing:0.18em; }}
.card-title {{ font-weight:400; font-size:15px; }}
.pickrow {{ display:flex; align-items:center; gap:14px; margin-top:14px;
  font-family:'JetBrains Mono',monospace; font-size:11px; color:#94A3B8;
  letter-spacing:0.18em; text-transform:uppercase; }}
.pickrow b {{ color:#7CF1F1; }}
</style></head><body>
<div class="eyebrow">▶︎ EXOPLAYER · DESIGN GALLERY · 2.7.39</div>
<h1>ExoPlayer overlay &amp; control dock</h1>
<p class="subtitle">10 overlay variants &times; 10 control-dock variants, all on the Dune Part Two backdrop.
Pick one of each and tell me — I'll build it into the Kotlin ExoPlayerActivity overlay.</p>
<div class="pickrow"><span>How to choose:</span><b>O01 + C02</b><span>// reply with overlay # + control #</span></div>

<h2>Overlay variants <span class="eyebrow ml-3" style="display:inline; vertical-align:middle;">SYNOPSIS &middot; LOGO &middot; CHIPS</span></h2>
<div class="grid">{ov_cards}</div>

<h2>Control dock variants <span class="eyebrow ml-3" style="display:inline; vertical-align:middle;">PLAY &middot; SCRUB &middot; LANGUAGE &middot; SETTINGS</span></h2>
<div class="grid">{ct_cards}</div>
</body></html>'''
(OUT / "index.html").write_text(INDEX)
print(f"✓ Index written: {OUT / 'index.html'}")
