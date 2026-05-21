"""
Generates 10 overlay + 10 control-dock + index + README design mockups.
Run: python3 generate.py
Output: /app/frontend/public/design_mockups/{overlays,controls}/...
"""
from pathlib import Path
import json, html

ROOT = Path(__file__).parent
OUT = Path("/app/frontend/public/design_mockups")
(OUT / "overlays").mkdir(parents=True, exist_ok=True)
(OUT / "controls").mkdir(parents=True, exist_ok=True)

spec = json.loads((Path("/app/design_guidelines.json")).read_text())
MOVIES = spec["movies_data"]
OVL = spec["overlay_variants"]
CTL = spec["control_variants"]

PALETTE = {
    "bg0": "#020610", "bg1": "#0A1322", "bg2": "#0D121C",
    "cyan": "#5DC8FF", "cyan_bright": "#7CF1F1",
    "muted": "#94A3B8",
}

# ─── Shared CSS used by every mockup ────────────────────────────────
SHARED_CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { width:100vw; height:100vh; overflow:hidden; background:#020610;
  font-family:'Manrope', system-ui, -apple-system, sans-serif;
  color:#fff; -webkit-font-smoothing:antialiased; }
.stage { position:fixed; inset:0; }
.bg-img { position:absolute; inset:0; width:100%; height:100%;
  object-fit:cover; filter: brightness(0.85); }
.mono { font-family:'JetBrains Mono', 'SF Mono', Menlo, monospace;
  letter-spacing:0.18em; text-transform:uppercase; }
.eyebrow { font-family:'JetBrains Mono', monospace;
  font-size:10px; font-weight:700; letter-spacing:0.32em;
  text-transform:uppercase; color:#7CF1F1; }
.chip { display:inline-flex; align-items:center; gap:4px;
  font-family:'JetBrains Mono', monospace;
  font-size:11px; font-weight:700; letter-spacing:0.14em;
  padding:5px 10px; border-radius:999px; white-space:nowrap; }
.synopsis { font-size:17px; line-height:1.6; color:rgba(255,255,255,0.86);
  font-weight:300; max-width:580px;
  display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical;
  overflow:hidden; }
.title { font-family:'Outfit', 'Manrope', sans-serif;
  font-weight:300; font-size:64px; line-height:1.0;
  letter-spacing:-0.03em; color:#fff; }
.meta-row { display:flex; flex-wrap:wrap; align-items:center;
  gap:10px; margin-top:14px; }
.backend-badge { position:absolute; top:30px; left:30px;
  font-family:'JetBrains Mono', monospace; font-weight:700;
  font-size:11px; letter-spacing:0.22em;
  padding:8px 14px; border-radius:999px;
  background:rgba(13,18,28,0.8); border:1px solid rgba(124,241,241,0.5);
  color:#7CF1F1; backdrop-filter:blur(12px); }
.buf-hud { position:absolute; top:30px; right:30px;
  font-family:'JetBrains Mono', monospace; font-weight:700;
  font-size:11px; letter-spacing:0.18em;
  padding:8px 14px; border-radius:6px;
  background:rgba(10,19,34,0.7); border:1px solid rgba(93,200,255,0.35);
  color:#5DC8FF; backdrop-filter:blur(12px); }
.logo-fake { font-family:'Outfit', sans-serif; font-weight:900;
  font-size:42px; letter-spacing:-0.02em; line-height:1;
  display:inline-flex; gap:6px; align-items:baseline; }
.logo-fake .sub { font-weight:300; }
""".strip()


def chip_block(quality, lang_flag, addon, size, *, style="default"):
    """Return HTML for the metadata-chip row.  `style` controls colour."""
    cyan_chip   = "background:rgba(93,200,255,0.18); color:#5DC8FF; border:1px solid rgba(93,200,255,0.4);"
    white_chip  = "background:rgba(255,255,255,0.08); color:#fff; border:1px solid rgba(255,255,255,0.18);"
    gold_chip   = "background:rgba(255,210,138,0.12); color:#ffd28a; border:1px solid rgba(255,210,138,0.3);"
    neon_chip   = "background:rgba(124,241,241,0.14); color:#7CF1F1; border:1px solid rgba(124,241,241,0.4);"

    if style == "neon":
        q_style, lang_style, addon_style, size_style = neon_chip, white_chip, neon_chip, white_chip
    elif style == "warm":
        q_style, lang_style, addon_style, size_style = gold_chip, white_chip, cyan_chip, gold_chip
    elif style == "minimal":
        q_style = lang_style = addon_style = size_style = white_chip
    else:
        q_style, lang_style, addon_style, size_style = cyan_chip, white_chip, white_chip, gold_chip

    return f'''
    <span class="chip" style="{q_style}">{quality}</span>
    <span class="chip" style="{lang_style}">{lang_flag} ENG</span>
    <span class="chip" style="{addon_style}">{addon}</span>
    <span class="chip" style="{size_style}">{size}</span>
    '''

# ─── 10 OVERLAY templates ────────────────────────────────────────────

def overlay_html(idx, movie, variant):
    """Generate one overlay HTML.  Each idx (0..9) gets a distinct layout."""
    title = movie["title"]
    syn = movie["synopsis"]
    year = movie["year"]; rt = movie["runtime"]; rating = movie["rating"]
    size = movie["size"]; bg = movie["backdrop_url"]
    name = variant["name"]
    quality = "4K HDR" if "Avatar" in title or "Dune" in title or "Top Gun" in title else "1080p"
    addon = "ON NOW" if idx % 3 == 0 else "TORRENTIO" if idx % 3 == 1 else "PLEXIO"

    # Distinct layout per index
    if idx == 0:  # Left-Fade Classic
        body = f'''
        <div style="position:absolute; inset:0; background:linear-gradient(90deg, #020610 0%, rgba(2,6,16,0.85) 30%, transparent 65%);"></div>
        <div style="position:absolute; left:80px; bottom:80px; max-width:600px;">
          <div class="eyebrow" style="margin-bottom:24px;">▶︎ Now Playing</div>
          <div class="logo-fake" style="margin-bottom:18px; color:#fff;">{title.split()[0].upper()}<span class="sub">{" ".join(title.split()[1:])}</span></div>
          <div class="title">{title}</div>
          <div class="synopsis" style="margin-top:18px;">{html.escape(syn)}</div>
          <div class="meta-row">
            <span class="mono" style="font-size:12px; color:#94A3B8;">{year} · {rt} · {rating}</span>
            {chip_block(quality, "🇬🇧", addon, size)}
          </div>
        </div>
        '''
    elif idx == 1:  # Right-Fade Cyberpunk
        body = f'''
        <div style="position:absolute; inset:0; background:linear-gradient(-90deg, #0D121C 0%, rgba(13,18,28,0.85) 35%, transparent 70%);"></div>
        <div style="position:absolute; right:80px; bottom:80px; max-width:560px; text-align:right;">
          <div class="eyebrow" style="margin-bottom:24px; color:#7CF1F1; text-shadow:0 0 8px rgba(124,241,241,0.5);">// SIGNAL LOCK //</div>
          <div class="logo-fake mono" style="margin-bottom:18px; color:#7CF1F1; font-size:54px; text-shadow:0 0 24px rgba(124,241,241,0.6);">{title.upper()}</div>
          <div class="synopsis" style="margin-left:auto;">{html.escape(syn)}</div>
          <div class="meta-row" style="justify-content:flex-end;">
            <span class="mono" style="font-size:11px; color:#7CF1F1;">{year} · {rt} · {rating}</span>
            {chip_block(quality, "🇬🇧", addon, size, style="neon")}
          </div>
        </div>
        '''
    elif idx == 2:  # Vignette Center
        body = f'''
        <div style="position:absolute; inset:0; background:radial-gradient(circle at center, transparent 0%, rgba(2,6,16,0.35) 50%, #020610 100%);"></div>
        <div style="position:absolute; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; padding:0 120px;">
          <div class="eyebrow" style="margin-bottom:32px;">Tonight's Feature</div>
          <div class="logo-fake" style="margin-bottom:24px; font-size:56px; color:#fff;">{title.upper()}</div>
          <div class="title" style="font-size:48px; margin-bottom:24px;">{title}</div>
          <div class="synopsis" style="text-align:center; max-width:680px; margin:0 auto;">{html.escape(syn)}</div>
          <div class="meta-row" style="justify-content:center; margin-top:28px;">
            <span class="mono" style="font-size:12px; color:#94A3B8;">{year} · {rt} · {rating}</span>
            {chip_block(quality, "🇬🇧", addon, size)}
          </div>
        </div>
        '''
    elif idx == 3:  # Top-Fade Glassmorphism
        body = f'''
        <div style="position:absolute; top:0; left:0; right:0; height:50vh; background:linear-gradient(180deg, rgba(0,0,0,0.85) 0%, rgba(2,6,16,0.4) 60%, transparent 100%); backdrop-filter:blur(4px);"></div>
        <div style="position:absolute; left:80px; top:120px; max-width:680px;">
          <div class="eyebrow" style="margin-bottom:18px;">Currently watching</div>
          <div class="logo-fake" style="margin-bottom:14px; font-size:48px;">{title.upper()}</div>
          <div class="title" style="font-size:40px;">{title}</div>
          <div class="synopsis" style="margin-top:16px;">{html.escape(syn)}</div>
          <div class="meta-row">
            <span class="mono" style="font-size:11px; color:#94A3B8;">{year} · {rt} · {rating}</span>
            {chip_block(quality, "🇬🇧", addon, size, style="minimal")}
          </div>
        </div>
        '''
    elif idx == 4:  # Bottom-Anchored Block
        body = f'''
        <div style="position:absolute; left:0; right:0; bottom:0; height:38vh; background:rgba(10,19,34,0.78); backdrop-filter:blur(28px); border-top:1px solid rgba(93,200,255,0.18);"></div>
        <div style="position:absolute; left:80px; bottom:90px; right:80px; display:flex; gap:60px; align-items:flex-end;">
          <div class="logo-fake" style="font-size:72px; flex:0 0 auto;">{title.split()[0].upper()}<span class="sub" style="font-size:32px;">{" ".join(title.split()[1:])}</span></div>
          <div style="flex:1 1 auto; max-width:680px;">
            <div class="eyebrow" style="margin-bottom:10px;">Synopsis</div>
            <div class="synopsis" style="font-size:16px;">{html.escape(syn)}</div>
            <div class="meta-row">
              <span class="mono" style="font-size:11px; color:#94A3B8;">{year} · {rt} · {rating}</span>
              {chip_block(quality, "🇬🇧", addon, size)}
            </div>
          </div>
        </div>
        '''
    elif idx == 5:  # Diagonal Split
        body = f'''
        <div style="position:absolute; inset:0; background:linear-gradient(45deg, #020610 0%, rgba(2,6,16,0.7) 25%, transparent 55%);"></div>
        <div style="position:absolute; left:80px; bottom:100px; max-width:600px;">
          <div class="eyebrow" style="margin-bottom:28px; color:#5DC8FF;">Watching now</div>
          <div class="logo-fake" style="margin-bottom:20px; background:linear-gradient(135deg, #ffffff, #5DC8FF); -webkit-background-clip:text; -webkit-text-fill-color:transparent; background-clip:text;">{title.upper()}</div>
          <div class="title" style="font-size:52px; background:linear-gradient(135deg, #ffffff, #94A3B8); -webkit-background-clip:text; -webkit-text-fill-color:transparent;">{title}</div>
          <div class="synopsis" style="margin-top:18px;">{html.escape(syn)}</div>
          <div class="meta-row">
            <span class="mono" style="font-size:12px; color:#94A3B8;">{year} · {rt} · {rating}</span>
            {chip_block(quality, "🇬🇧", addon, size)}
          </div>
        </div>
        '''
    elif idx == 6:  # Apple TV Style
        body = f'''
        <div style="position:absolute; inset:0; background:rgba(0,0,0,0.35);"></div>
        <div style="position:absolute; left:120px; bottom:140px; max-width:540px;">
          <div class="eyebrow" style="margin-bottom:22px; opacity:0.7; color:#fff;">Apple TV+ · Now Playing</div>
          <div class="logo-fake" style="margin-bottom:20px; font-weight:200; font-size:38px; opacity:0.95;">{title.upper()}</div>
          <div class="title" style="font-size:42px; font-weight:200;">{title}</div>
          <div class="synopsis" style="margin-top:16px; font-weight:200; font-size:15px;">{html.escape(syn)}</div>
          <div class="meta-row" style="gap:16px;">
            <span style="font-size:13px; opacity:0.7;">{year}</span>
            <span style="font-size:13px; opacity:0.7;">·</span>
            <span style="font-size:13px; opacity:0.7;">{rt}</span>
            <span style="font-size:13px; opacity:0.7;">·</span>
            <span style="font-size:13px; opacity:0.7;">{rating}</span>
            <span style="font-size:13px; opacity:0.7;">·</span>
            <span style="font-size:13px; opacity:0.7;">{quality}</span>
            <span style="font-size:13px; opacity:0.7;">·</span>
            <span style="font-size:13px; opacity:0.7;">🇬🇧</span>
          </div>
        </div>
        '''
    elif idx == 7:  # Grid Bento Panel
        body = f'''
        <div style="position:absolute; left:0; top:0; bottom:0; width:42%; background:rgba(13,18,28,0.85); backdrop-filter:blur(24px); border-right:1px solid rgba(93,200,255,0.25);"></div>
        <div style="position:absolute; left:60px; top:140px; right:0; width:34vw; display:grid; gap:18px;">
          <div style="background:rgba(93,200,255,0.06); border:1px solid rgba(93,200,255,0.2); border-radius:18px; padding:24px;">
            <div class="logo-fake" style="font-size:36px;">{title.upper()}</div>
          </div>
          <div style="background:rgba(93,200,255,0.06); border:1px solid rgba(93,200,255,0.2); border-radius:18px; padding:20px;">
            <div class="eyebrow" style="margin-bottom:10px;">Synopsis</div>
            <div class="synopsis" style="font-size:14px;">{html.escape(syn)}</div>
          </div>
          <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px;">
            <div style="background:rgba(93,200,255,0.06); border:1px solid rgba(93,200,255,0.2); border-radius:14px; padding:14px;">
              <div class="eyebrow" style="margin-bottom:6px;">Year · Run · Rating</div>
              <div class="mono" style="font-size:13px;">{year} · {rt} · {rating}</div>
            </div>
            <div style="background:rgba(93,200,255,0.06); border:1px solid rgba(93,200,255,0.2); border-radius:14px; padding:14px;">
              <div class="eyebrow" style="margin-bottom:6px;">Source · Quality</div>
              <div class="mono" style="font-size:13px;">{addon} · {quality}</div>
            </div>
          </div>
        </div>
        '''
    elif idx == 8:  # Full Screen Frosted
        body = f'''
        <div style="position:absolute; inset:0; background:rgba(2,6,16,0.55); backdrop-filter:blur(8px);"></div>
        <div style="position:absolute; left:50%; top:50%; transform:translate(-50%,-50%); text-align:center; max-width:760px;">
          <div class="logo-fake" style="font-size:80px; margin-bottom:28px; text-shadow:0 0 30px rgba(93,200,255,0.7); color:#fff;">{title.upper()}</div>
          <div class="synopsis" style="text-align:center; max-width:640px; margin:0 auto; font-size:18px;">{html.escape(syn)}</div>
          <div class="meta-row" style="justify-content:center; margin-top:30px;">
            <span class="mono" style="font-size:12px; color:#7CF1F1;">{year} · {rt} · {rating}</span>
            {chip_block(quality, "🇬🇧", addon, size, style="neon")}
          </div>
        </div>
        '''
    else:  # idx == 9 — Cinematic Borderless
        body = f'''
        <div style="position:absolute; left:80px; bottom:80px; max-width:560px;">
          <div class="logo-fake" style="font-size:60px; margin-bottom:18px; text-shadow:0 4px 24px rgba(0,0,0,0.95);">{title.upper()}</div>
          <div class="title" style="text-shadow:0 4px 24px rgba(0,0,0,0.95);">{title}</div>
          <div class="synopsis" style="margin-top:18px; text-shadow:0 2px 12px rgba(0,0,0,0.9);">{html.escape(syn)}</div>
          <div class="meta-row">
            <span class="mono" style="font-size:12px; color:#fff; text-shadow:0 2px 8px rgba(0,0,0,1);">{year} · {rt} · {rating} · {quality} · 🇬🇧 · {addon} · {size}</span>
          </div>
        </div>
        '''

    return f'''<!doctype html><html><head><meta charset="utf-8"><title>{title} — {name}</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@200;300;400;700;900&family=Manrope:wght@300;400;500;700&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>{SHARED_CSS}</style></head><body>
<div class="stage">
  <img class="bg-img" src="{bg}" alt="{title}">
  {body}
  <div class="backend-badge">▶︎ EXOPLAYER</div>
  <div class="buf-hud">BUF 12s · 6.2Mbps · ExoPlayer</div>
</div>
</body></html>'''


# ─── 10 CONTROL DOCK templates ───────────────────────────────────────

def control_html(idx, movie, variant):
    title = movie["title"]
    bg = movie["backdrop_url"]
    name = variant["name"]
    cur = "01:48:22"; total = "02:46:00"; pct = 65

    if idx == 0:  # Classic TV Bar
        body = f'''
        <div style="position:absolute; left:0; right:0; bottom:0; height:160px;
                    background:linear-gradient(180deg, transparent, rgba(2,6,16,0.95));">
          <div style="position:absolute; left:40px; right:40px; bottom:80px;">
            <div style="height:6px; background:rgba(255,255,255,0.15); border-radius:3px; position:relative;">
              <div style="height:100%; width:{pct}%; background:#5DC8FF; border-radius:3px; box-shadow:0 0 12px rgba(93,200,255,0.5);"></div>
              <div style="position:absolute; left:calc({pct}% - 8px); top:-6px; width:18px; height:18px; border-radius:50%; background:#5DC8FF; box-shadow:0 0 18px rgba(93,200,255,0.8);"></div>
            </div>
            <div style="display:flex; justify-content:space-between; margin-top:8px;">
              <span class="mono" style="font-size:12px; color:#5DC8FF;">{cur}</span>
              <span class="mono" style="font-size:12px; color:#94A3B8;">{total}</span>
            </div>
          </div>
          <div style="position:absolute; left:0; right:0; bottom:0; height:60px; display:flex; align-items:center; justify-content:center; gap:24px;">
            {ctl_icons("classic")}
          </div>
        </div>
        '''
    elif idx == 1:  # Floating Pill Glass
        body = f'''
        <div style="position:absolute; left:50%; bottom:48px; transform:translateX(-50%); display:flex; align-items:center; gap:18px; padding:14px 32px; border-radius:999px; background:rgba(10,19,34,0.7); backdrop-filter:blur(28px); border:1px solid rgba(93,200,255,0.25); box-shadow:0 28px 80px rgba(0,0,0,0.65);">
          {ctl_icons("pill")}
          <div style="width:1px; height:30px; background:rgba(255,255,255,0.12);"></div>
          <span class="mono" style="font-size:12px; color:#5DC8FF;">{cur}</span>
          <div style="width:200px; height:4px; background:rgba(255,255,255,0.15); border-radius:2px; position:relative;">
            <div style="height:100%; width:{pct}%; background:#5DC8FF; border-radius:2px;"></div>
          </div>
          <span class="mono" style="font-size:12px; color:#94A3B8;">{total}</span>
        </div>
        '''
    elif idx == 2:  # Apple TV Blur
        body = f'''
        <div style="position:absolute; left:0; right:0; bottom:0; height:22vh; background:rgba(0,0,0,0.45); backdrop-filter:blur(32px);"></div>
        <div style="position:absolute; left:0; right:0; bottom:0; height:1px; background:rgba(255,255,255,0.08);"></div>
        <div style="position:absolute; left:80px; right:80px; bottom:80px;">
          <div style="height:2px; background:rgba(255,255,255,0.12); position:relative; border-radius:1px;">
            <div style="height:100%; width:{pct}%; background:#fff; border-radius:1px;"></div>
          </div>
          <div style="display:flex; justify-content:space-between; margin-top:14px;">
            <span style="font-size:13px; color:rgba(255,255,255,0.85);">{cur}</span>
            <span style="font-size:13px; color:rgba(255,255,255,0.55);">−{total}</span>
          </div>
        </div>
        <div style="position:absolute; left:0; right:0; bottom:18px; display:flex; align-items:center; justify-content:center; gap:48px;">
          {ctl_icons("apple")}
        </div>
        '''
    elif idx == 3:  # Netflix Flat
        body = f'''
        <div style="position:absolute; left:0; right:0; bottom:0; height:30vh; background:linear-gradient(180deg, transparent, rgba(0,0,0,0.85));"></div>
        <div style="position:absolute; left:40px; right:40px; bottom:120px;">
          <div style="height:4px; background:rgba(255,255,255,0.2); border-radius:2px; position:relative;">
            <div style="height:100%; width:{pct}%; background:#5DC8FF; border-radius:2px;"></div>
            <div style="position:absolute; left:calc({pct}% - 8px); top:-6px; width:16px; height:16px; border-radius:50%; background:#5DC8FF;"></div>
          </div>
        </div>
        <div style="position:absolute; left:40px; right:40px; bottom:50px; display:flex; align-items:center; justify-content:space-between;">
          <div style="display:flex; align-items:center; gap:20px;">{ctl_left("netflix")}</div>
          <div style="display:flex; align-items:center; gap:8px;">
            <span class="mono" style="font-size:12px; color:#fff;">{cur} / {total}</span>
          </div>
          <div style="display:flex; align-items:center; gap:20px;">{ctl_right("netflix")}</div>
        </div>
        '''
    elif idx == 4:  # Corner Cluster
        body = f'''
        <div style="position:absolute; right:40px; bottom:40px; padding:20px; border-radius:22px; background:rgba(10,19,34,0.85); backdrop-filter:blur(28px); border:1px solid rgba(93,200,255,0.2); display:grid; grid-template-columns:auto 1fr; gap:16px;">
          <div style="width:6px; height:200px; background:rgba(255,255,255,0.15); border-radius:3px; position:relative;">
            <div style="position:absolute; bottom:0; width:100%; height:{pct}%; background:#5DC8FF; border-radius:3px;"></div>
          </div>
          <div style="display:flex; flex-direction:column; gap:14px; min-width:280px;">
            <div style="display:flex; gap:10px; align-items:center; justify-content:center;">{ctl_icons("cluster")}</div>
            <div style="display:flex; justify-content:space-between;">
              <span class="mono" style="font-size:11px; color:#5DC8FF;">{cur}</span>
              <span class="mono" style="font-size:11px; color:#94A3B8;">{total}</span>
            </div>
            <div style="display:flex; gap:8px; justify-content:center;">{extra_buttons_small()}</div>
          </div>
        </div>
        '''
    elif idx == 5:  # Cyberpunk Neon Deck
        body = f'''
        <div style="position:absolute; left:0; right:0; bottom:0; height:140px; background:linear-gradient(180deg, transparent, rgba(0,0,0,0.95)); border-top:1px solid rgba(124,241,241,0.4);"></div>
        <div style="position:absolute; left:60px; right:60px; bottom:90px;">
          <div style="display:flex; justify-content:space-between; margin-bottom:8px;">
            <span class="mono" style="font-size:11px; color:#7CF1F1; text-shadow:0 0 8px rgba(124,241,241,0.6);">[ {cur} ]</span>
            <span class="mono" style="font-size:11px; color:#7CF1F1; text-shadow:0 0 8px rgba(124,241,241,0.6);">// {pct}% //</span>
            <span class="mono" style="font-size:11px; color:#7CF1F1; text-shadow:0 0 8px rgba(124,241,241,0.6);">[ {total} ]</span>
          </div>
          <div style="height:3px; background:rgba(124,241,241,0.15); position:relative; clip-path:polygon(0 0, 100% 0, calc(100% - 8px) 100%, 0 100%);">
            <div style="height:100%; width:{pct}%; background:#7CF1F1; box-shadow:0 0 14px rgba(124,241,241,0.8);"></div>
          </div>
        </div>
        <div style="position:absolute; left:0; right:0; bottom:18px; display:flex; align-items:center; justify-content:center; gap:32px;">
          {ctl_icons("cyber")}
        </div>
        '''
    elif idx == 6:  # Radial Arc
        body = f'''
        <div style="position:absolute; left:50%; bottom:36px; transform:translateX(-50%); display:flex; align-items:flex-end; gap:36px;">
          <div style="display:flex; flex-direction:column; align-items:center; gap:8px; margin-bottom:24px;">{ctl_btn("⏮", "tiny")}<span class="mono" style="font-size:9px; color:#94A3B8;">−10</span></div>
          <div style="display:flex; flex-direction:column; align-items:center; gap:8px; margin-bottom:14px;">{ctl_btn("🔊", "tiny")}</div>
          <div style="display:flex; flex-direction:column; align-items:center; gap:8px;">{ctl_btn("⏸", "huge")}<span class="mono" style="font-size:11px; color:#5DC8FF;">{cur}</span></div>
          <div style="display:flex; flex-direction:column; align-items:center; gap:8px; margin-bottom:14px;">{ctl_btn("CC", "tiny")}</div>
          <div style="display:flex; flex-direction:column; align-items:center; gap:8px; margin-bottom:24px;">{ctl_btn("⏭", "tiny")}<span class="mono" style="font-size:9px; color:#94A3B8;">+10</span></div>
        </div>
        <div style="position:absolute; left:160px; right:160px; bottom:140px;">
          <div style="height:3px; background:rgba(255,255,255,0.12); border-radius:2px; position:relative;">
            <div style="height:100%; width:{pct}%; background:#5DC8FF;"></div>
          </div>
        </div>
        '''
    elif idx == 7:  # MacOS Dock
        body = f'''
        <div style="position:absolute; left:50%; bottom:60px; transform:translateX(-50%); padding:14px 28px; border-radius:24px; background:rgba(255,255,255,0.08); backdrop-filter:blur(40px); border:1px solid rgba(255,255,255,0.18); box-shadow:0 30px 80px rgba(0,0,0,0.6); display:flex; align-items:center; gap:20px;">
          {ctl_icons("dock")}
          <div style="width:1px; height:32px; background:rgba(255,255,255,0.15);"></div>
          {extra_buttons("dock")}
        </div>
        <div style="position:absolute; left:50%; bottom:30px; transform:translateX(-50%); display:flex; align-items:center; gap:14px;">
          <span class="mono" style="font-size:11px; color:#fff;">{cur}</span>
          <div style="width:340px; height:3px; background:rgba(255,255,255,0.15); border-radius:2px; position:relative;">
            <div style="height:100%; width:{pct}%; background:#fff; border-radius:2px;"></div>
          </div>
          <span class="mono" style="font-size:11px; color:rgba(255,255,255,0.6);">{total}</span>
        </div>
        '''
    elif idx == 8:  # Vertical Left Column
        body = f'''
        <div style="position:absolute; left:0; top:0; bottom:0; width:88px; background:rgba(10,19,34,0.85); backdrop-filter:blur(24px); border-right:1px solid rgba(93,200,255,0.18); display:flex; flex-direction:column; align-items:center; justify-content:center; gap:18px;">
          {ctl_icons("vertical")}
        </div>
        <div style="position:absolute; left:100px; top:0; bottom:0; width:8px; display:flex; align-items:center;">
          <div style="height:60%; width:6px; background:rgba(255,255,255,0.12); border-radius:3px; position:relative;">
            <div style="position:absolute; bottom:0; width:100%; height:{pct}%; background:#5DC8FF; border-radius:3px; box-shadow:0 0 12px rgba(93,200,255,0.5);"></div>
          </div>
        </div>
        <div style="position:absolute; left:120px; bottom:60px;">
          <div class="mono" style="font-size:11px; color:#5DC8FF;">{cur}</div>
          <div class="mono" style="font-size:11px; color:#94A3B8; margin-top:4px;">{total}</div>
        </div>
        '''
    else:  # idx == 9 — Minimalist Hover
        body = f'''
        <div style="position:absolute; left:0; right:0; bottom:0; height:2px; background:rgba(255,255,255,0.08);">
          <div style="height:100%; width:{pct}%; background:#fff;"></div>
        </div>
        <div style="position:absolute; left:50%; bottom:50px; transform:translateX(-50%); width:80px; height:80px; border-radius:50%; background:rgba(255,255,255,0.12); backdrop-filter:blur(20px); border:1px solid rgba(255,255,255,0.3); display:flex; align-items:center; justify-content:center; font-size:32px;">⏸</div>
        <div style="position:absolute; left:50%; bottom:160px; transform:translateX(-50%); display:flex; gap:36px; opacity:0.5;">
          <span style="font-size:20px;">⏪</span><span style="font-size:20px;">🔊</span><span style="font-size:20px;">CC</span><span style="font-size:20px;">⚙</span><span style="font-size:20px;">⏩</span>
        </div>
        '''

    return f'''<!doctype html><html><head><meta charset="utf-8"><title>Controls — {name}</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;700&family=Manrope:wght@300;400;700&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>{SHARED_CSS}</style></head><body>
<div class="stage">
  <img class="bg-img" src="{bg}" alt="{title}" style="filter:brightness(0.55);">
  {body}
  <div class="backend-badge">▶︎ EXOPLAYER</div>
  <div class="buf-hud">BUF 12s · 6.2Mbps · ExoPlayer</div>
</div>
</body></html>'''


def ctl_btn(icon, size="normal"):
    sizes = {
        "huge":   "width:84px; height:84px; font-size:36px; background:#5DC8FF; color:#020610; box-shadow:0 0 30px rgba(93,200,255,0.6);",
        "large":  "width:64px; height:64px; font-size:28px; background:rgba(255,255,255,0.08); color:#fff;",
        "normal": "width:48px; height:48px; font-size:20px; background:rgba(255,255,255,0.06); color:#fff;",
        "tiny":   "width:44px; height:44px; font-size:14px; background:rgba(255,255,255,0.06); color:#fff;",
    }
    s = sizes.get(size, sizes["normal"])
    return f'<div style="{s} border-radius:50%; display:inline-flex; align-items:center; justify-content:center; border:1px solid rgba(255,255,255,0.12); cursor:pointer; font-family:JetBrains Mono;">{icon}</div>'


def ctl_icons(style):
    if style == "classic":
        return ctl_btn("🔊", "normal") + ctl_btn("CC", "normal") + ctl_btn("⏪", "large") + ctl_btn("⏸", "huge") + ctl_btn("⏩", "large") + ctl_btn("⚙", "normal") + ctl_btn("⛶", "normal")
    if style == "pill":
        return ctl_btn("⏪", "normal") + ctl_btn("⏸", "large") + ctl_btn("⏩", "normal")
    if style == "apple":
        return ctl_btn("🔊", "normal") + ctl_btn("⏪", "large") + ctl_btn("⏸", "huge") + ctl_btn("⏩", "large") + ctl_btn("CC", "normal")
    if style == "cluster":
        return ctl_btn("⏪", "normal") + ctl_btn("⏸", "large") + ctl_btn("⏩", "normal")
    if style == "cyber":
        return ctl_btn("◀◀", "normal") + ctl_btn("◀▶", "large") + ctl_btn("▶▶", "normal")
    if style == "dock":
        return ctl_btn("⏪", "normal") + ctl_btn("⏸", "large") + ctl_btn("⏩", "normal")
    if style == "vertical":
        return ctl_btn("⏪", "normal") + ctl_btn("⏸", "huge") + ctl_btn("⏩", "normal") + ctl_btn("🔊", "normal") + ctl_btn("CC", "normal") + ctl_btn("⚙", "normal")
    return ""


def ctl_left(style):
    return ctl_btn("⏸", "large") + ctl_btn("⏪", "normal") + ctl_btn("⏩", "normal") + ctl_btn("🔊", "normal")


def ctl_right(style):
    return ctl_btn("CC", "normal") + ctl_btn("⚙", "normal") + ctl_btn("◳", "normal") + ctl_btn("⛶", "normal")


def extra_buttons(style):
    return ctl_btn("🔊", "normal") + ctl_btn("CC", "normal") + ctl_btn("⚙", "normal") + ctl_btn("◳", "normal") + ctl_btn("⛶", "normal")


def extra_buttons_small():
    return ctl_btn("🔊", "tiny") + ctl_btn("CC", "tiny") + ctl_btn("⚙", "tiny") + ctl_btn("⛶", "tiny")


# ─── Write all files ─────────────────────────────────────────────────
print("Generating overlay mockups …")
for i, m in enumerate(MOVIES):
    v = OVL[i]
    path = OUT / "overlays" / f"{m['filename']}.html"
    path.write_text(overlay_html(i, m, v))
    print(f"  ✓ {path.name}  ({v['name']})")

print("\nGenerating control-dock mockups …")
ctl_filenames = [
    "01-bar-classic", "02-floating-pill", "03-apple-tv-blur",
    "04-netflix-flat", "05-corner-cluster", "06-cyberpunk-neon",
    "07-radial-arc", "08-macos-dock", "09-vertical-column",
    "10-minimalist-hover",
]
for i, v in enumerate(CTL):
    m = MOVIES[i]
    path = OUT / "controls" / f"{ctl_filenames[i]}.html"
    path.write_text(control_html(i, m, v))
    print(f"  ✓ {path.name}  ({v['name']})")

# ─── Index page with thumbnails ──────────────────────────────────────
cards_overlay = "\n".join(
    f'''<a href="overlays/{m['filename']}.html" class="card">
      <iframe src="overlays/{m['filename']}.html" scrolling="no" tabindex="-1"></iframe>
      <div class="card-meta">
        <div class="num">O{i+1:02d}</div>
        <div class="card-title">{m['title']}</div>
        <div class="card-sub">{OVL[i]['name']}</div>
      </div>
    </a>'''
    for i, m in enumerate(MOVIES)
)

cards_ctl = "\n".join(
    f'''<a href="controls/{ctl_filenames[i]}.html" class="card">
      <iframe src="controls/{ctl_filenames[i]}.html" scrolling="no" tabindex="-1"></iframe>
      <div class="card-meta">
        <div class="num">C{i+1:02d}</div>
        <div class="card-title">{CTL[i]['name']}</div>
        <div class="card-sub">{MOVIES[i]['title']}</div>
      </div>
    </a>'''
    for i in range(10)
)

index_html = f'''<!doctype html><html><head><meta charset="utf-8">
<title>ExoPlayer overlay & control dock — 10 × 2 variants</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@200;300;700&family=Manrope:wght@300;400;700&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
* {{ box-sizing:border-box; margin:0; padding:0; }}
body {{ background:#020610; color:#fff; font-family:'Manrope', sans-serif;
  padding:48px 64px; min-height:100vh; }}
h1 {{ font-family:'Outfit', sans-serif; font-weight:200; font-size:48px;
  letter-spacing:-0.03em; margin-bottom:8px; }}
.subtitle {{ color:#94A3B8; font-size:16px; margin-bottom:8px; }}
.eyebrow {{ font-family:'JetBrains Mono', monospace; font-size:11px;
  letter-spacing:0.28em; text-transform:uppercase; color:#7CF1F1;
  margin-bottom:14px; }}
h2 {{ font-family:'Outfit', sans-serif; font-weight:300; font-size:28px;
  margin-top:48px; margin-bottom:24px; letter-spacing:-0.02em; }}
.grid {{ display:grid; grid-template-columns:repeat(auto-fill, minmax(380px, 1fr));
  gap:24px; }}
.card {{ position:relative; display:block; overflow:hidden;
  border-radius:18px; border:1px solid rgba(255,255,255,0.08);
  background:#0A1322; aspect-ratio:16/9; text-decoration:none; color:inherit;
  transition:transform 220ms ease, border-color 220ms ease,
             box-shadow 220ms ease; }}
.card:hover {{ transform:translateY(-4px) scale(1.02);
  border-color:rgba(93,200,255,0.5);
  box-shadow:0 24px 60px rgba(93,200,255,0.18); }}
.card iframe {{ position:absolute; inset:0; width:1920px; height:1080px;
  transform:scale(0.21); transform-origin:top left;
  border:0; pointer-events:none; }}
.card-meta {{ position:absolute; left:14px; bottom:14px; right:14px;
  background:rgba(2,6,16,0.85); backdrop-filter:blur(12px);
  border:1px solid rgba(93,200,255,0.22); border-radius:12px;
  padding:10px 14px; display:flex; align-items:center; gap:12px; }}
.num {{ font-family:'JetBrains Mono', monospace; font-size:11px;
  color:#7CF1F1; font-weight:700; letter-spacing:0.18em; }}
.card-title {{ font-family:'Outfit', sans-serif; font-weight:400;
  font-size:14px; flex:1; }}
.card-sub {{ font-family:'JetBrains Mono', monospace; font-size:10px;
  color:#94A3B8; letter-spacing:0.12em; text-transform:uppercase; }}
</style></head><body>
<div class="eyebrow">▶︎ EXOPLAYER · DESIGN GALLERY · v2.7.39</div>
<h1>ExoPlayer overlay &amp; control dock</h1>
<p class="subtitle">10 overlay variants × 10 control-dock variants — pick one of each, tell me, I'll build it in Kotlin.</p>

<h2>Overlay variants <span class="eyebrow" style="margin-left:14px;">10 designs · synopsis + logo + chips</span></h2>
<div class="grid">{cards_overlay}</div>

<h2>Control dock variants <span class="eyebrow" style="margin-left:14px;">10 designs · play / scrub / language / settings</span></h2>
<div class="grid">{cards_ctl}</div>
</body></html>'''

(OUT / "index.html").write_text(index_html)
print(f"\n✓ {OUT / 'index.html'}")

readme = f'''# ExoPlayer overlay & control dock — design gallery

Open in any browser:
- **Gallery**: `{OUT}/index.html` (or via the preview at `/design_mockups/index.html`)

## Overlay variants (10)

''' + "\n".join(
    f"- **O{i+1:02d}** — {OVL[i]['name']} — `{m['filename']}.html` — {m['title']}"
    for i, m in enumerate(MOVIES)
) + '''\n\n## Control-dock variants (10)\n\n''' + "\n".join(
    f"- **C{i+1:02d}** — {CTL[i]['name']} — `{ctl_filenames[i]}.html` — over {MOVIES[i]['title']} backdrop"
    for i in range(10)
) + '''\n\n## How to pick\n\nTell the agent which overlay number (O01-O10) and which control number (C01-C10)
you want and it will be built in Kotlin on top of the existing ExoPlayerActivity.
'''
(OUT / "README.md").write_text(readme)
print(f"✓ {OUT / 'README.md'}")
print("\nAll done.  Open /design_mockups/index.html to browse.")
