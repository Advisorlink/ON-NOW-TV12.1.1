/* Sports Hub design mockup — faithful JS port of the Kotlin views (SportsUi.kt,
   SportFieldView.kt, GamePlotView.kt, WorldMapView.kt, SportsHubActivity.kt,
   GameActivity.kt). TV density xhdpi: 1dp = 2px, 1sp = 2px. Sample data only. */
const DP = 2;
const dp = v => v * DP;
const C = { BG: '#070B14', CARD: '#101828', CARD_FOCUS: '#18243A', ACCENT: '#5DC8FF', LIVE: '#FF4D5E', DIM: '#9DA5B5', PLOT: '#0B1220', GREEN: '#7CF1A1', AMBER: '#FFC850', AWAY: '#FF8FAB' };
const PULSE = 0.35;
const alpha = (hex, a) => { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a / 255})`; };
const logo = (lg, k) => lg === 'soccer' ? `https://a.espncdn.com/i/teamlogos/soccer/500/${k}.png` : `https://a.espncdn.com/i/teamlogos/${lg}/500/${k}.png`;

/* ---------- sample data ---------- */
const T = (lg, k, short, name, record, color) => ({ logo: logo(lg, k), short, name, record, color, abbr: short });
const GAMES = [
  { state: 'in', sport: 'baseball', league: 'mlb', leagueName: 'MLB', statusDetail: 'Top 7th', broadcasts: ['ESPN', 'YES Network'], away: T('mlb', 'nyy', 'NYY', 'New York Yankees', '58-34', '#003087'), home: T('mlb', 'bos', 'BOS', 'Boston Red Sox', '49-43', '#BD3039'), aScore: '4', hScore: '3', geo: [42.35, -71.06], name: 'Yankees at Red Sox', venue: 'Fenway Park' },
  { state: 'in', sport: 'football', league: 'nfl', leagueName: 'NFL', statusDetail: 'Q3 8:42', broadcasts: ['CBS'], away: T('nfl', 'kc', 'KC', 'Kansas City Chiefs', '7-2', '#E31837'), home: T('nfl', 'buf', 'BUF', 'Buffalo Bills', '6-3', '#00338D'), aScore: '17', hScore: '21', geo: [42.77, -78.79], name: 'Chiefs at Bills', venue: 'Highmark Stadium' },
  { state: 'in', sport: 'soccer', league: 'eng.1', leagueName: 'Premier League', statusDetail: "67'", broadcasts: ['Sky Sports', 'NBC'], away: T('soccer', 364, 'LIV', 'Liverpool', '2nd · 61 pts', '#C8102E'), home: T('soccer', 359, 'ARS', 'Arsenal', '1st · 64 pts', '#EF0107'), aScore: '1', hScore: '1', geo: [51.55, -0.11], name: 'Liverpool at Arsenal', venue: 'Emirates Stadium' },
  { state: 'in', sport: 'basketball', league: 'nba', leagueName: 'NBA', statusDetail: 'Q4 2:15', broadcasts: ['TNT'], away: T('nba', 'lal', 'LAL', 'Los Angeles Lakers', '31-19', '#552583'), home: T('nba', 'bos', 'BOS', 'Boston Celtics', '38-12', '#007A33'), aScore: '98', hScore: '101', geo: [42.36, -71.06], name: 'Lakers at Celtics' },
  { state: 'in', sport: 'hockey', league: 'nhl', leagueName: 'NHL', statusDetail: 'P2 11:03', broadcasts: ['Sportsnet'], away: T('nhl', 'tor', 'TOR', 'Toronto Maple Leafs', '30-18-5', '#00205B'), home: T('nhl', 'mtl', 'MTL', 'Montreal Canadiens', '24-22-6', '#AF1E2D'), aScore: '2', hScore: '2', geo: [45.50, -73.57], name: 'Maple Leafs at Canadiens' },
  { state: 'in', sport: 'soccer', league: 'esp.1', leagueName: 'LaLiga', statusDetail: 'HT', broadcasts: ['ESPN+'], away: T('soccer', 83, 'BAR', 'Barcelona', '2nd', '#A50044'), home: T('soccer', 86, 'RMA', 'Real Madrid', '1st', '#FEBE10'), aScore: '0', hScore: '2', geo: [40.45, -3.69], name: 'Barcelona at Real Madrid' },
  { state: 'pre', sport: 'baseball', league: 'mlb', leagueName: 'MLB', statusDetail: '7:10 PM', broadcasts: ['FOX'], away: T('mlb', 'lad', 'LAD', 'Los Angeles Dodgers', '61-31', '#005A9C'), home: T('mlb', 'sf', 'SF', 'San Francisco Giants', '47-45', '#FD5A1E'), time: '7:10 PM', geo: [37.78, -122.39], name: 'Dodgers at Giants' },
  { state: 'pre', sport: 'football', league: 'nfl', leagueName: 'NFL', statusDetail: '8:20 PM', broadcasts: ['NBC', 'Peacock'], away: T('nfl', 'dal', 'DAL', 'Dallas Cowboys', '5-4', '#003594'), home: T('nfl', 'phi', 'PHI', 'Philadelphia Eagles', '8-1', '#004C54'), time: '8:20 PM', geo: [39.90, -75.17], name: 'Cowboys at Eagles' },
  { state: 'pre', sport: 'soccer', league: 'ita.1', leagueName: 'Serie A', statusDetail: '2:45 PM', broadcasts: ['Paramount+'], away: T('soccer', 111, 'JUV', 'Juventus', '3rd', '#000000'), home: T('soccer', 110, 'INT', 'Inter Milan', '1st', '#010E80'), time: '2:45 PM', geo: [45.48, 9.12], name: 'Juventus at Inter' },
  { state: 'pre', sport: 'australian-football', league: 'afl', leagueName: 'AFL', statusDetail: '7:50 PM', broadcasts: ['Fox Footy'], away: T('afl', 'col', 'COLL', 'Collingwood', '9-4', '#000000'), home: T('afl', 'rich', 'RICH', 'Richmond', '5-8', '#FFD200'), time: '7:50 PM', geo: [-37.82, 144.98], name: 'Collingwood at Richmond' },
  { state: 'post', sport: 'baseball', league: 'mlb', leagueName: 'MLB', statusDetail: 'Final', broadcasts: ['MLB Network'], away: T('mlb', 'hou', 'HOU', 'Houston Astros', '52-40', '#002D62'), home: T('mlb', 'atl', 'ATL', 'Atlanta Braves', '50-42', '#CE1141'), aScore: '5', hScore: '2', geo: [33.89, -84.47], name: 'Astros at Braves' },
  { state: 'post', sport: 'soccer', league: 'eng.1', leagueName: 'Premier League', statusDetail: 'FT', broadcasts: ['USA Network'], away: T('soccer', 382, 'MCI', 'Manchester City', '3rd', '#6CABDD'), home: T('soccer', 360, 'MUN', 'Manchester United', '8th', '#DA291C'), aScore: '3', hScore: '1', geo: [53.46, -2.29], name: 'Man City at Man Utd' },
];
const SPORTS = ['Football', 'Baseball', 'Basketball', 'Hockey', 'Soccer', 'Tennis', 'Cricket', 'Golf', 'MMA', 'Racing', 'Rugby', 'Australian Football'];
const LEAGUES = ['MLB', 'College Baseball', 'WBC', 'Minor League'];

/* ---------- SportsUi kit ---------- */
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
const text = (s, sp, color = '#fff', bold = false, mono = false) => { const e = el('div', (bold ? 'b ' : '') + (mono ? 'mono ' : ''), s); e.style.fontSize = dp(sp) + 'px'; e.style.color = color; return e; };
const eyebrow = (s, color = C.DIM) => text(s.toUpperCase(), 10, color, true, true);
const pill = (label, selected = false, accent = C.ACCENT, focused = false, sp = 13) => {
  const e = text(label, sp, selected ? '#061018' : '#fff', true); e.classList.add('pill');
  if (selected) e.style.background = accent;
  if (focused) { e.classList.add('focus'); e.style.background = selected ? accent : 'rgba(255,255,255,.2)'; }
  return e;
};
const img = (url, size, round = false) => { const i = el('img'); i.src = url; i.style.width = i.style.height = dp(size) + 'px'; if (round) i.style.borderRadius = '999px'; return i; };
const focusable = (e, radius, accent = C.ACCENT, focused = false, base = C.CARD) => {
  e.classList.add('focusable'); e.style.borderRadius = dp(radius) + 'px'; e.style.background = focused ? C.CARD_FOCUS : base;
  if (focused) { e.classList.add('focus'); e.style.borderColor = accent; }
};

/* ---------- SportFieldView port ---------- */
function drawField(cv, sport, accent) {
  const c = cv.getContext('2d'); const w = cv.width, h = cv.height; c.clearRect(0, 0, w, h);
  const r = { l: w * .10, t: h * .12, r: w * .90, b: h * .88 }; r.w = r.r - r.l; r.h = r.b - r.t; r.cx = (r.l + r.r) / 2; r.cy = (r.t + r.b) / 2;
  const P = { line: [alpha(accent, 0x4A), dp(1.6)], soft: [alpha(accent, 0x30), dp(1.2)], dashed: [alpha(accent, 0x30), dp(1.2), [dp(5), dp(5)]] };
  const fillC = alpha(accent, 0x0E);
  const stroke = (p, fn) => { c.save(); c.strokeStyle = p[0]; c.lineWidth = p[1]; c.setLineDash(p[2] || []); c.beginPath(); fn(); c.stroke(); c.restore(); };
  const fill = (fn, col = fillC) => { c.save(); c.fillStyle = col; c.beginPath(); fn(); c.fill(); c.restore(); };
  const rect = (x0, y0, x1, y1) => c.rect(x0, y0, x1 - x0, y1 - y0);
  const rrect = (x0, y0, x1, y1, rad) => c.roundRect(x0, y0, x1 - x0, y1 - y0, rad);
  const oval = (x0, y0, x1, y1) => c.ellipse((x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2, 0, 0, Math.PI * 2);
  const ln = (x0, y0, x1, y1) => { c.moveTo(x0, y0); c.lineTo(x1, y1); };
  const circ = (x, y, rad) => { c.moveTo(x + rad, y); c.arc(x, y, rad, 0, Math.PI * 2); };
  const arc = (x0, y0, x1, y1, start, sweep) => c.ellipse((x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2, 0, start * Math.PI / 180, (start + sweep) * Math.PI / 180);
  const both = (fn, rad) => { fill(fn); stroke(P.line, fn); };
  switch (sport) {
    case 'soccer': {
      both(() => rrect(r.l, r.t, r.r, r.b, dp(6)));
      stroke(P.soft, () => { ln(r.cx, r.t, r.cx, r.b); circ(r.cx, r.cy, r.h * .17); });
      stroke(P.line, () => circ(r.cx, r.cy, dp(2.4)));
      const boxH = r.h * .52, boxW = r.w * .16, gH = r.h * .26, gW = r.w * .06;
      [true, false].forEach(left => {
        const x0 = left ? r.l : r.r - boxW, x1 = left ? r.l + boxW : r.r;
        stroke(P.soft, () => { rect(x0, r.cy - boxH / 2, x1, r.cy + boxH / 2); const gx0 = left ? r.l : r.r - gW, gx1 = left ? r.l + gW : r.r; rect(gx0, r.cy - gH / 2, gx1, r.cy + gH / 2); });
        stroke(P.line, () => circ(left ? r.l + boxW * .68 : r.r - boxW * .68, r.cy, dp(2)));
      }); break;
    }
    case 'tennis': {
      both(() => rect(r.l, r.t, r.r, r.b));
      const alley = r.h * .14, svc = r.w * .22;
      stroke(P.soft, () => { ln(r.l, r.t + alley, r.r, r.t + alley); ln(r.l, r.b - alley, r.r, r.b - alley); ln(r.cx - svc, r.t + alley, r.cx - svc, r.b - alley); ln(r.cx + svc, r.t + alley, r.cx + svc, r.b - alley); ln(r.cx - svc, r.cy, r.cx + svc, r.cy); ln(r.l, r.cy, r.l + dp(6), r.cy); ln(r.r - dp(6), r.cy, r.r, r.cy); });
      stroke([P.line[0], dp(2.4)], () => ln(r.cx, r.t - dp(4), r.cx, r.b + dp(4))); break;
    }
    case 'mlb': {
      const homeY = r.b, size = Math.min(r.w, r.h) * .52, second = homeY - size * 1.42, firstX = r.cx + size, thirdX = r.cx - size, baseY = homeY - size * .71;
      const dia = () => { c.moveTo(r.cx, homeY); c.lineTo(firstX, baseY); c.lineTo(r.cx, second); c.lineTo(thirdX, baseY); c.closePath(); };
      both(dia);
      stroke(P.soft, () => arc(r.cx - size * 1.5, second - size * .55, r.cx + size * 1.5, homeY + size * .4, 205, 130));
      stroke(P.dashed, () => { ln(r.cx, homeY, r.r, r.t + r.h * .28); ln(r.cx, homeY, r.l, r.t + r.h * .28); });
      stroke(P.soft, () => circ(r.cx, homeY - size * .71, dp(5)));
      const b = dp(3.4);
      [[firstX, baseY], [r.cx, second], [thirdX, baseY], [r.cx, homeY]].forEach(([x, y]) => { c.save(); c.translate(x, y); c.rotate(Math.PI / 4); stroke(P.line, () => rect(-b, -b, b, b)); c.restore(); });
      break;
    }
    case 'cricket': {
      both(() => oval(r.l, r.t, r.r, r.b));
      stroke(P.dashed, () => oval(r.l + r.w * .16, r.t + r.h * .16, r.r - r.w * .16, r.b - r.h * .16));
      const pw = r.w * .055, ph = r.h * .42, ci = ph * .14;
      stroke(P.soft, () => { rect(r.cx - pw, r.cy - ph / 2, r.cx + pw, r.cy + ph / 2); ln(r.cx - pw * 1.7, r.cy - ph / 2 + ci, r.cx + pw * 1.7, r.cy - ph / 2 + ci); ln(r.cx - pw * 1.7, r.cy + ph / 2 - ci, r.cx + pw * 1.7, r.cy + ph / 2 - ci); });
      stroke(P.line, () => { ln(r.cx - pw * .5, r.cy - ph / 2, r.cx + pw * .5, r.cy - ph / 2); ln(r.cx - pw * .5, r.cy + ph / 2, r.cx + pw * .5, r.cy + ph / 2); }); break;
    }
    case 'nba': {
      both(() => rrect(r.l, r.t, r.r, r.b, dp(4)));
      stroke(P.soft, () => { ln(r.cx, r.t, r.cx, r.b); circ(r.cx, r.cy, r.h * .16); });
      const keyW = r.w * .19, keyH = r.h * .34;
      [true, false].forEach(left => {
        const x0 = left ? r.l : r.r - keyW, x1 = left ? r.l + keyW : r.r;
        stroke(P.soft, () => rect(x0, r.cy - keyH / 2, x1, r.cy + keyH / 2));
        stroke(P.dashed, () => circ(left ? r.l + keyW : r.r - keyW, r.cy, keyH * .30));
        const hoopX = left ? r.l + r.w * .045 : r.r - r.w * .045, aR = r.h * .42;
        stroke(P.line, () => arc(hoopX - aR, r.cy - aR, hoopX + aR, r.cy + aR, left ? -78 : 102, 156));
      }); break;
    }
    case 'nhl': {
      const rad = r.h * .28; both(() => rrect(r.l, r.t, r.r, r.b, rad));
      stroke(P.line, () => ln(r.cx, r.t, r.cx, r.b));
      const blue = r.w * .17, fx = r.w * .30, fy = r.h * .24;
      stroke(P.soft, () => { ln(r.cx - blue, r.t, r.cx - blue, r.b); ln(r.cx + blue, r.t, r.cx + blue, r.b); circ(r.cx, r.cy, r.h * .15); ln(r.l + r.w * .06, r.t + r.h * .2, r.l + r.w * .06, r.b - r.h * .2); ln(r.r - r.w * .06, r.t + r.h * .2, r.r - r.w * .06, r.b - r.h * .2); });
      stroke(P.dashed, () => [-1, 1].forEach(sx => [-1, 1].forEach(sy => circ(r.cx + sx * fx, r.cy + sy * fy, r.h * .11)))); break;
    }
    case 'nfl': {
      both(() => rect(r.l, r.t, r.r, r.b));
      const ez = r.w * .10; fill(() => { rect(r.l, r.t, r.l + ez, r.b); rect(r.r - ez, r.t, r.r, r.b); }, alpha(accent, 0x1C));
      stroke(P.line, () => { ln(r.l + ez, r.t, r.l + ez, r.b); ln(r.r - ez, r.t, r.r - ez, r.b); });
      const pw = r.w - 2 * ez;
      for (let i = 1; i <= 9; i++) { const x = r.l + ez + pw * i / 10; stroke(i === 5 ? P.line : P.soft, () => ln(x, r.t, x, r.b)); }
      break;
    }
    case 'afl': {
      both(() => oval(r.l, r.t, r.r, r.b));
      const sq = r.h * .30, aR = r.w * .20, gH = r.h * .16, gW = r.w * .05;
      stroke(P.soft, () => { rect(r.cx - sq / 2, r.cy - sq / 2, r.cx + sq / 2, r.cy + sq / 2); circ(r.cx, r.cy, dp(4)); rect(r.l, r.cy - gH / 2, r.l + gW, r.cy + gH / 2); rect(r.r - gW, r.cy - gH / 2, r.r, r.cy + gH / 2); });
      stroke(P.dashed, () => arc(r.l - aR, r.cy - aR, r.l + aR, r.cy + aR, -62, 124)); stroke(P.dashed, () => arc(r.r - aR, r.cy - aR, r.r + aR, r.cy + aR, 118, 124)); break;
    }
    case 'rugby': {
      both(() => rect(r.l, r.t, r.r, r.b));
      const tz = r.w * .09; fill(() => { rect(r.l, r.t, r.l + tz, r.b); rect(r.r - tz, r.t, r.r, r.b); }, alpha(accent, 0x1C));
      stroke(P.line, () => { ln(r.l + tz, r.t, r.l + tz, r.b); ln(r.r - tz, r.t, r.r - tz, r.b); ln(r.cx, r.t, r.cx, r.b); });
      const pw = r.w - 2 * tz;
      stroke(P.soft, () => { ln(r.l + tz + pw * .25, r.t, r.l + tz + pw * .25, r.b); ln(r.r - tz - pw * .25, r.t, r.r - tz - pw * .25, r.b); });
      stroke(P.dashed, () => { ln(r.cx - pw * .11, r.t, r.cx - pw * .11, r.b); ln(r.cx + pw * .11, r.t, r.cx + pw * .11, r.b); }); break;
    }
    case 'mma': {
      const rad = Math.min(r.w, r.h) / 2;
      const oct = radius => { for (let i = 0; i < 8; i++) { const a = (i * 45 - 22.5) * Math.PI / 180; const x = r.cx + radius * Math.cos(a), y = r.cy + radius * Math.sin(a); i ? c.lineTo(x, y) : c.moveTo(x, y); } c.closePath(); };
      both(() => oct(rad)); stroke(P.soft, () => oct(rad * .86)); stroke(P.dashed, () => circ(r.cx, r.cy, rad * .22)); break;
    }
    case 'f1': {
      const p = () => { c.moveTo(r.l + r.w * .18, r.b); c.lineTo(r.r - r.w * .24, r.b); c.quadraticCurveTo(r.r, r.b, r.r, r.b - r.h * .32); c.quadraticCurveTo(r.r, r.b - r.h * .62, r.r - r.w * .26, r.b - r.h * .60); c.lineTo(r.cx + r.w * .04, r.t + r.h * .34); c.quadraticCurveTo(r.cx - r.w * .06, r.t, r.l + r.w * .30, r.t + r.h * .06); c.quadraticCurveTo(r.l, r.t + r.h * .16, r.l + r.w * .04, r.t + r.h * .52); c.quadraticCurveTo(r.l + r.w * .07, r.b - r.h * .12, r.l + r.w * .18, r.b); c.closePath(); };
      c.lineJoin = 'round'; stroke([alpha(accent, 0x22), dp(9)], p); stroke(P.dashed, p);
      const sx = r.l + r.w * .42; stroke(P.line, () => { for (let i = 0; i < 3; i++) ln(sx + i * dp(5), r.b - dp(6), sx + i * dp(5), r.b + dp(6)); }); break;
    }
    case 'golf': {
      const gw = r.w * .72, gh = r.h * .68; both(() => oval(r.cx - gw / 2, r.cy - gh / 2, r.cx + gw / 2, r.cy + gh / 2));
      stroke(P.dashed, () => oval(r.cx - gw * .3, r.cy - gh * .3, r.cx + gw * .3, r.cy + gh * .3)); stroke(P.soft, () => oval(r.cx - gw * .14, r.cy - gh * .14, r.cx + gw * .14, r.cy + gh * .14));
      const top = r.cy - r.h * .34; stroke(P.line, () => { ln(r.cx, top, r.cx, r.cy); circ(r.cx, r.cy, dp(3.2)); });
      const flag = () => { c.moveTo(r.cx, top); c.lineTo(r.cx + r.w * .11, top + r.h * .05); c.lineTo(r.cx, top + r.h * .10); c.closePath(); };
      fill(flag, alpha(accent, 0x55)); stroke(P.line, flag);
      stroke(P.soft, () => { c.moveTo(r.l + r.w * .10, r.b - r.h * .05); c.quadraticCurveTo(r.cx, r.b + r.h * .10, r.r - r.w * .10, r.b - r.h * .05); }); break;
    }
  }
  return r;
}

/* ---------- GamePlotView port ---------- */
function drawPlot(cv, sport, accent, home, away, plays, upTo) {
  const r = drawField(cv, sport, accent); const c = cv.getContext('2d');
  const project = p => {
    if (sport === 'mlb') { if (p.hx == null) return null; const size = Math.min(r.w, r.h) * .52; const fx = Math.max(-1.4, Math.min(1.4, (p.hx - 125) / 125)), fy = Math.max(0, Math.min(1.05, p.hy / 210)); return [r.cx + fx * size * 1.45, r.b - (1 - fy) * size * 1.75]; }
    if (sport === 'nfl') { if (p.yardsToGo == null) return null; const y = Math.max(0, Math.min(100, p.yardsToGo)); const along = p.side === 'home' ? (100 - y) / 100 : y / 100; return [r.l + along * r.w, r.t + (p.side === 'home' ? .42 : .58) * r.h]; }
    if (sport === 'soccer') { return [r.l + p.fx / 100 * r.w, r.t + p.fy / 100 * r.h]; }
    return null;
  };
  const first = Math.max(0, upTo - 160);
  for (let i = first; i <= upTo; i++) {
    const p = plays[i]; const pt = project(p); if (!pt) continue;
    const teamColor = p.side === 'home' ? home : p.side === 'away' ? away : '#FFFFFF';
    const a = Math.max(40, Math.min(255, Math.round((1 - (upTo - i) / 160) * 200 + 40)));
    const radius = ['homerun', 'touchdown', 'goal', 'score'].includes(p.outcome) ? dp(6.5) : ['hit', 'made'].includes(p.outcome) ? dp(5) : dp(4);
    const dot = (col, rad) => { c.fillStyle = col; c.beginPath(); c.arc(pt[0], pt[1], rad, 0, Math.PI * 2); c.fill(); };
    const ring = (col, rad, lw) => { c.strokeStyle = col; c.lineWidth = lw; c.setLineDash([]); c.beginPath(); c.arc(pt[0], pt[1], rad, 0, Math.PI * 2); c.stroke(); };
    switch (p.outcome) {
      case 'homerun': case 'touchdown': case 'goal': case 'score': dot(alpha(C.AMBER, a / 2), radius * 2.1); dot(alpha(C.AMBER, a), radius); break;
      case 'hit': case 'made': dot(alpha('#FFFFFF', a), radius); ring(alpha(teamColor, a), radius + dp(2), dp(1.5)); break;
      case 'out': case 'miss': ring(alpha(C.LIVE, a), radius, dp(2)); break;
      default: dot(alpha(teamColor, a / 2), radius * .7);
    }
    if (i === upTo) ring(alpha('#FFFFFF', Math.round((1 - PULSE) * 220)), radius + dp(4) + PULSE * dp(14), dp(2));
  }
}

/* ---------- WorldMapView port ---------- */
const CONTINENTS = [
  [-168, 66, -140, 70, -95, 72, -70, 62, -55, 50, -65, 45, -75, 36, -81, 25, -97, 26, -105, 20, -87, 15, -80, 8, -92, 16, -110, 24, -120, 34, -125, 48, -150, 60, -165, 60],
  [-80, 8, -62, 10, -50, 0, -35, -7, -39, -20, -48, -28, -58, -38, -66, -50, -72, -45, -72, -20, -80, -5],
  [-10, 36, 0, 44, 10, 45, 20, 42, 30, 46, 40, 44, 30, 60, 25, 70, 10, 64, 5, 58, -5, 50, -10, 44],
  [-17, 15, -5, 35, 10, 37, 32, 31, 43, 12, 51, 11, 40, -5, 35, -25, 20, -35, 15, -30, 12, -15, 8, 5, -10, 5],
  [27, 70, 60, 72, 100, 76, 140, 72, 180, 66, 160, 60, 140, 52, 125, 38, 120, 22, 105, 10, 100, 5, 97, 18, 80, 8, 72, 20, 60, 25, 50, 30, 40, 42, 30, 46, 32, 60],
  [114, -22, 130, -12, 142, -11, 153, -27, 150, -37, 140, -38, 130, -32, 117, -34, 114, -28],
  [166, -46, 174, -41, 178, -37, 173, -35, 170, -44], [130, 33, 142, 42, 145, 44, 140, 36, 132, 31], [-8, 52, -5, 58, -2, 56, 0, 51, -5, 50],
];
function drawMap(cv, games, selected) {
  const c = cv.getContext('2d'); const W = cv.width, H = cv.height; c.clearRect(0, 0, W, H);
  const px = lon => (lon + 180) / 360 * W, py = lat => (90 - lat) / 180 * H * 1.08 - H * .02;
  c.strokeStyle = 'rgba(255,255,255,.08)'; c.lineWidth = 1; c.beginPath();
  for (let lon = -150; lon <= 180; lon += 30) { c.moveTo(px(lon), 0); c.lineTo(px(lon), H); }
  for (let lat = -60; lat <= 60; lat += 30) { c.moveTo(0, py(lat)); c.lineTo(W, py(lat)); } c.stroke();
  CONTINENTS.forEach(poly => { c.beginPath(); for (let i = 0; i + 1 < poly.length; i += 2) { const x = px(poly[i]), y = py(poly[i + 1]); i ? c.lineTo(x, y) : c.moveTo(x, y); } c.closePath(); c.fillStyle = '#16213A'; c.fill(); c.strokeStyle = 'rgba(93,200,255,.2)'; c.lineWidth = 1.5; c.stroke(); });
  games.forEach((g, i) => {
    const x = px(g.geo[1]), y = py(g.geo[0]); const live = g.state === 'in'; const r = live ? 9 : 5;
    if (live) { c.strokeStyle = alpha(C.LIVE, Math.round((1 - PULSE) * 200)); c.lineWidth = 2.5; c.beginPath(); c.arc(x, y, r + PULSE * 26, 0, Math.PI * 2); c.stroke(); }
    c.fillStyle = live ? C.LIVE : 'rgba(93,200,255,.6)'; c.beginPath(); c.arc(x, y, r, 0, Math.PI * 2); c.fill();
    if (i === selected) {
      c.strokeStyle = '#fff'; c.lineWidth = 3; c.beginPath(); c.arc(x, y, r + 7, 0, Math.PI * 2); c.stroke();
      const tx = Math.min(x + 18, W - 260);
      c.fillStyle = '#fff'; c.font = 'bold 26px system-ui, sans-serif'; c.fillText(g.name, tx, y - 6);
      c.fillStyle = 'rgba(157,165,181,.8)'; c.font = '20px system-ui, sans-serif';
      c.fillText(`${g.leagueName} · ${live ? `${g.aScore}–${g.hScore} · ${g.statusDetail}` : g.statusDetail}`, tx, y + 18);
    }
  });
}

/* ---------- screens ---------- */
const sizeCanvas = cv => { const r = cv.getBoundingClientRect(); const s = window.__frameScale || 1; cv.width = Math.round(r.width / s); cv.height = Math.round(r.height / s); };

function teamCell(t, end) {
  const c = el('div', 'col'); c.style.gap = dp(6) + 'px'; c.style.alignItems = end ? 'flex-end' : 'flex-start';
  c.append(img(t.logo, 44), text(t.short, 13, '#fff', true), text(t.record, 10, C.DIM, false, true)); return c;
}
function gameCard(g, focused) {
  const live = g.state === 'in'; const card = el('div', 'col'); card.style.gap = dp(10) + 'px'; card.style.padding = `${dp(14)}px ${dp(16)}px`;
  focusable(card, 16, live ? C.LIVE : g.home.color, focused);
  const r1 = el('div', 'row'); r1.style.gap = dp(8) + 'px';
  const eb = eyebrow(g.leagueName, live ? C.LIVE : C.DIM); eb.style.flex = '1'; r1.append(eb, text((live ? '● ' : '') + g.statusDetail, 11, live ? C.LIVE : C.DIM, live, true));
  const mid = el('div', 'row'); mid.style.gap = dp(12) + 'px';
  const a = teamCell(g.away, false), h = teamCell(g.home, true); a.style.flex = h.style.flex = '1';
  const sc = text(g.state === 'pre' ? g.time : `${g.aScore}  –  ${g.hScore}`, g.state === 'pre' ? 16 : 28, '#fff', true); sc.style.textAlign = 'center'; sc.style.whiteSpace = 'pre';
  mid.append(a, sc, h); card.append(r1, mid, text(g.broadcasts.join(' · '), 10, C.DIM, false, true)); return card;
}
function hubFrame(mode, focusIdx = 0) {
  const root = el('div', 'col'); root.style.cssText = `background:${C.BG};padding:${dp(28)}px ${dp(40)}px ${dp(20)}px;height:100%;box-sizing:border-box`;
  const header = el('div', 'row'); header.style.gap = dp(16) + 'px';
  const tc = el('div', 'col'); tc.style.flex = '1'; tc.append(eyebrow('ON NOW TV · Live'), text('Sports Hub', 30, '#fff', true));
  const liveCount = GAMES.filter(g => g.state === 'in').length;
  const mp = el('div', 'row'); mp.style.gap = dp(8) + 'px';
  [['scores', 'Scores'], ['map', 'World map'], ['following', 'Following']].forEach(([m, l]) => mp.append(pill(l, mode === m)));
  header.append(tc, text(`● ${liveCount} LIVE`, 12, C.LIVE, true, true), mp); root.append(header);
  const sr = el('div', 'row'); sr.style.cssText = `gap:${dp(8)}px;margin-top:${dp(22)}px;overflow:hidden`;
  sr.append(pill('All · Live now', mode !== 'league')); SPORTS.forEach((s, i) => sr.append(pill(s, mode === 'league' && i === 1)));
  const lr = el('div', 'row'); lr.style.cssText = `gap:${dp(8)}px;margin-top:${dp(10)}px;min-height:${dp(33)}px`;
  if (mode === 'league') LEAGUES.forEach((l, i) => lr.append(pill(l, i === 0, C.GREEN)));
  root.append(sr, lr);
  const stage = el('div'); stage.style.cssText = `flex:1;margin-top:${dp(18)}px;position:relative;min-height:0`;
  if (mode === 'map') {
    const cv = el('canvas'); cv.style.cssText = `position:absolute;inset:0 0 ${dp(40)}px 0;width:100%;height:calc(100% - ${dp(40)}px);background:${C.PLOT};border:${dp(2)}px solid ${C.ACCENT};border-radius:${dp(18)}px;box-sizing:border-box`;
    const cap = text('MLB · Yankees at Red Sox — press OK to open · ◀ ▶ to move', 13, C.DIM); cap.style.cssText += `;position:absolute;left:0;right:0;bottom:0;text-align:center`;
    stage.append(cv, cap); root.append(stage);
    requestAnimationFrame(() => { sizeCanvas(cv); drawMap(cv, GAMES.filter(g => g.geo).sort((a, b) => (a.state === 'in' ? 0 : 1) - (b.state === 'in' ? 0 : 1)), 0); });
    return root;
  }
  const content = el('div', 'col'); content.style.cssText = `gap:${dp(14)}px;overflow:hidden;height:100%`;
  const list = mode === 'following' ? GAMES.filter(g => ['nyy', 'kc', 'lal'].some(k => g.away.logo.includes(k) || g.home.logo.includes(k))) : mode === 'league' ? GAMES.filter(g => g.league === 'mlb') : GAMES;
  let n = 0;
  const section = (title, color, items) => {
    if (!items.length) return; const hr = el('div', 'row'); hr.style.gap = dp(10) + 'px'; hr.append(eyebrow(title, color), eyebrow(String(items.length), C.DIM)); content.append(hr);
    for (let i = 0; i < items.length; i += 3) { const row = el('div', 'row'); row.style.gap = dp(14) + 'px'; row.style.alignItems = 'stretch'; items.slice(i, i + 3).forEach(g => { const cd = gameCard(g, n++ === focusIdx); cd.style.flex = '1'; row.append(cd); }); for (let k = items.slice(i, i + 3).length; k < 3; k++) { const sp = el('div'); sp.style.flex = '1'; row.append(sp); } content.append(row); }
  };
  section('Live now', C.LIVE, list.filter(g => g.state === 'in')); section('Up next', C.ACCENT, list.filter(g => g.state === 'pre')); section('Final', C.DIM, list.filter(g => g.state === 'post'));
  stage.append(content); root.append(stage); return root;
}

function card(title, accent = C.DIM) { const c = el('div', 'col'); c.style.cssText = `gap:${dp(8)}px;padding:${dp(12)}px ${dp(16)}px ${dp(14)}px;background:${C.CARD};border-radius:${dp(14)}px;border:1px solid rgba(255,255,255,.13)`; c.append(eyebrow(title, accent)); return c; }
function gameFrame(kind) {
  const isMlb = kind === 'mlb'; const g = isMlb ? GAMES[0] : GAMES[1]; const hc = g.home.color, ac = g.away.color;
  const root = el('div', 'col'); root.style.cssText = `background:${C.BG};padding:${dp(22)}px ${dp(36)}px ${dp(18)}px;gap:${dp(16)}px;height:100%;box-sizing:border-box`;
  const header = el('div', 'row'); header.style.gap = dp(18) + 'px';
  const side = (t, end, following) => {
    const r = el('div', 'row'); r.style.cssText = `gap:${dp(12)}px;flex:1;justify-content:${end ? 'flex-end' : 'flex-start'}`;
    const nm = el('div', 'col'); nm.style.cssText = `gap:${dp(2)}px;align-items:${end ? 'flex-end' : 'flex-start'}`;
    nm.append(text(t.name, 18, '#fff', true), text(t.record, 11, C.DIM, false, true), pill(following ? '★ Following' : '☆ Follow', following, C.AMBER, false, 11));
    const lg = img(t.logo, 64); end ? r.append(nm, lg) : r.append(lg, nm); return r;
  };
  const centre = el('div', 'col'); centre.style.cssText = `gap:${dp(4)}px;align-items:center;margin:0 ${dp(24)}px`;
  const sc = text(`${g.aScore}  –  ${g.hScore}`, 44, '#fff', true); sc.style.whiteSpace = 'pre';
  centre.append(sc, text(`${g.leagueName} · ${g.statusDetail}`, 12, C.LIVE, true, true), text(g.venue, 11, C.DIM));
  header.append(side(g.away, false, isMlb), centre, side(g.home, true, false)); root.append(header);
  const body = el('div', 'row'); body.style.cssText = `gap:${dp(18)}px;flex:1;min-height:0;align-items:stretch`;
  const left = el('div', 'col'); left.style.cssText = `gap:${dp(12)}px;flex:1.15;min-width:0`;
  const cv = el('canvas'); cv.style.cssText = `flex:1;width:100%;background:${C.PLOT};border-radius:${dp(18)}px;border:1px solid rgba(255,255,255,.13);box-sizing:border-box;min-height:0`;
  const wp = el('div', 'col'); wp.style.cssText = `gap:${dp(8)}px;padding:${dp(12)}px ${dp(16)}px`; focusable(wp, 14, C.ACCENT, true);
  const wr = el('div', 'row'); const e1 = eyebrow('Win probability'); e1.style.flex = '1'; wr.append(e1, eyebrow('◀ ▶ step plays · OK = live'));
  const homePct = isMlb ? 41 : 63; const pr = el('div', 'row'); const pa = text(`${100 - homePct}%`, 22, ac, true), ph = text(`${homePct}%`, 22, hc, true); pa.style.flex = ph.style.flex = '1'; ph.style.textAlign = 'right'; pr.append(pa, ph);
  const bar = el('div'); bar.style.cssText = `height:${dp(10)}px;border-radius:999px;background:linear-gradient(to right, ${ac} 0 ${100 - homePct}%, ${hc} ${100 - homePct}% 100%)`;
  const playTxt = isMlb ? '● LIVE  T7 · P7 — Aaron Judge homers (31) on a fly ball to left center field. Juan Soto scores.' : '● LIVE  8:42 · P3 — Josh Allen pass complete deep right to Stefon Diggs for 34 yards, TOUCHDOWN.';
  wp.append(wr, pr, bar, Object.assign(text(playTxt, 13, '#E6EAF2'), { style: `font-size:${dp(13)}px;color:#E6EAF2;white-space:normal;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical` })); left.append(cv, wp);
  const right = el('div', 'col'); right.style.cssText = `gap:${dp(12)}px;flex:1;min-width:0;overflow:hidden`;
  const watch = card('Watch on your channels', C.ACCENT);
  const chans = isMlb ? [['ESPN', 'EPG: Yankees vs Red Sox · MLB Baseball', 'https://a.espncdn.com/i/espn/misc_logos/500/espn.png'], ['YES Network HD', 'Broadcaster in channel name', null], ['MLB Network', 'EPG: MLB Tonight — Yankees @ Red Sox', null]] : [['CBS Sports HD', 'EPG: NFL Football · Chiefs at Bills', null], ['NFL Network', 'Broadcaster in channel name', null], ['FOX Sports 1', 'EPG: NFL Live', null]];
  chans.forEach(([name, reason], i) => {
    const row = el('div', 'row'); row.style.cssText = `gap:${dp(10)}px;padding:${dp(8)}px ${dp(10)}px`; focusable(row, 10, C.ACCENT, false, C.PLOT);
    const lg = el('div'); lg.style.cssText = `width:${dp(30)}px;height:${dp(30)}px;border-radius:6px;background:#1A2438;display:flex;align-items:center;justify-content:center;font-size:${dp(9)}px;font-weight:700;color:${C.DIM}`; lg.textContent = name.split(' ')[0].slice(0, 4).toUpperCase();
    const col = el('div', 'col'); col.style.flex = '1'; col.style.minWidth = '0'; col.append(text(name, 13, '#fff', true), text(reason, 10, C.DIM));
    row.append(lg, col, text(i === 0 ? '▶ WATCH' : '▶', 11, C.ACCENT, true, true)); watch.append(row);
  });
  right.append(watch);
  const sit = card('Game');
  (isMlb ? ['Pitching  Tanner Houck · 6.1 IP, 7 H, 4 ER, 5 K', 'At bat  Giancarlo Stanton · 1-3, BB', 'Count  B2 S1 O1 · 1st · 3rd', 'Judge homers (31) on a fly ball to left center field. Soto scores.'] : ['2nd & 7 at KC 34 · BUF ball', 'Allen pass complete deep right to Diggs for 34 yards, TOUCHDOWN. Bass extra point is GOOD.']).forEach(s => sit.append(text(s, 13)));
  right.append(sit);
  const pc = card('Players'); const cols = el('div', 'row'); cols.style.cssText = `gap:${dp(14)}px;align-items:flex-start`;
  const players = isMlb ? [[g.away.abbr, ac, [['A. Judge', 'RF', '2-3, HR (31), 2 RBI'], ['J. Soto', 'LF', '1-2, 2 BB, R'], ['G. Cole', 'SP', '6.0 IP, 5 H, 3 ER, 8 K']]], [g.home.abbr, hc, [['R. Devers', '3B', '2-4, 2B, RBI'], ['J. Duran', 'CF', '1-3, 3B, R'], ['T. Houck', 'SP', '6.1 IP, 7 H, 4 ER, 5 K']]]] : [[g.away.abbr, ac, [['P. Mahomes', 'QB', '18/26, 221 YDS, 2 TD'], ['T. Kelce', 'TE', '6 REC, 78 YDS, TD'], ['I. Pacheco', 'RB', '11 CAR, 52 YDS']]], [g.home.abbr, hc, [['J. Allen', 'QB', '15/21, 243 YDS, 3 TD'], ['S. Diggs', 'WR', '7 REC, 112 YDS, 2 TD'], ['J. Cook', 'RB', '9 CAR, 41 YDS']]]];
  players.forEach(([abbr, color, rows]) => {
    const c = el('div', 'col'); c.style.cssText = `gap:${dp(8)}px;flex:1;min-width:0`; c.append(eyebrow(abbr, color));
    rows.forEach(([n, pos, line]) => { const r = el('div', 'row'); r.style.gap = dp(8) + 'px'; const hs = el('div'); hs.style.cssText = `width:${dp(28)}px;height:${dp(28)}px;border-radius:999px;background:#1A2438;flex:none`; const cc = el('div', 'col'); cc.style.flex = '1'; cc.style.minWidth = '0'; const nr = el('div', 'row'); nr.style.gap = dp(6) + 'px'; nr.append(text(n, 12, '#fff', true), text(pos, 9, C.DIM, false, true)); cc.append(nr, text(line, 10, C.DIM)); r.append(hs, cc); c.append(r); });
    cols.append(c);
  });
  pc.append(cols); right.append(pc, pill('League standings', false));
  body.append(left, right); root.append(body);
  const plays = isMlb ? [
    { hx: 95, hy: 150, outcome: 'out', side: 'away' }, { hx: 160, hy: 140, outcome: 'hit', side: 'home' }, { hx: 125, hy: 60, outcome: 'out', side: 'home' }, { hx: 70, hy: 110, outcome: 'hit', side: 'away' }, { hx: 185, hy: 95, outcome: 'out', side: 'away' }, { hx: 110, hy: 175, outcome: 'out', side: 'home' }, { hx: 150, hy: 165, outcome: 'hit', side: 'home' }, { hx: 60, hy: 75, outcome: 'homerun', side: 'home' }, { hx: 135, hy: 120, outcome: 'out', side: 'away' }, { hx: 80, hy: 160, outcome: 'hit', side: 'away' }, { hx: 200, hy: 130, outcome: 'hit', side: 'home' }, { hx: 115, hy: 90, outcome: 'out', side: 'home' }, { hx: 165, hy: 180, outcome: 'out', side: 'away' }, { hx: 90, hy: 125, outcome: 'hit', side: 'away' }, { hx: 140, hy: 70, outcome: 'out', side: 'away' }, { hx: 175, hy: 45, outcome: 'homerun', side: 'away' },
  ] : [
    { yardsToGo: 75, side: 'away' }, { yardsToGo: 68, side: 'away' }, { yardsToGo: 55, side: 'away' }, { yardsToGo: 41, side: 'away' }, { yardsToGo: 26, side: 'away' }, { yardsToGo: 12, side: 'away' }, { yardsToGo: 0, side: 'away', outcome: 'touchdown' }, { yardsToGo: 80, side: 'home' }, { yardsToGo: 71, side: 'home' }, { yardsToGo: 63, side: 'home' }, { yardsToGo: 48, side: 'home' }, { yardsToGo: 34, side: 'home' }, { yardsToGo: 0, side: 'home', outcome: 'touchdown' },
  ];
  requestAnimationFrame(() => { sizeCanvas(cv); drawPlot(cv, isMlb ? 'mlb' : 'nfl', hc, hc, ac, plays, plays.length - 1); });
  return root;
}

function fieldsFrame() {
  const root = el('div'); root.style.cssText = `background:${C.BG};padding:${dp(24)}px ${dp(40)}px;height:100%;box-sizing:border-box;display:flex;flex-direction:column;gap:${dp(14)}px`;
  const hd = el('div', 'col'); hd.append(eyebrow('SportFieldView.kt · 12 surfaces'), text('Drawn fields & courts', 26, '#fff', true)); root.append(hd);
  const grid = el('div'); grid.style.cssText = `flex:1;display:grid;grid-template-columns:repeat(4,1fr);grid-template-rows:repeat(3,1fr);gap:${dp(14)}px;min-height:0`;
  const list = [['soccer', 'Soccer pitch', '#7CF1A1'], ['mlb', 'Baseball diamond', '#BD3039'], ['nba', 'Basketball court', '#F58426'], ['nhl', 'Hockey rink', '#5DC8FF'], ['nfl', 'Gridiron', '#E31837'], ['tennis', 'Tennis court', '#C8F060'], ['cricket', 'Cricket oval', '#7CF1A1'], ['afl', 'AFL oval', '#FFD200'], ['rugby', 'Rugby field', '#A3E635'], ['mma', 'MMA octagon', '#FF4D5E'], ['f1', 'Race circuit', '#FF5A1E'], ['golf', 'Putting green', '#4ADE80']];
  list.forEach(([id, label, accent]) => {
    const c = el('div'); c.style.cssText = `position:relative;background:${C.PLOT};border-radius:${dp(14)}px;border:1px solid rgba(255,255,255,.13);min-height:0;overflow:hidden`;
    const cv = el('canvas'); cv.style.cssText = 'position:absolute;inset:0;width:100%;height:100%';
    const lb = text(label, 11, '#fff', true, true); lb.style.cssText += `;position:absolute;left:${dp(12)}px;top:${dp(10)}px`;
    const idl = text(`id: ${id}`, 9, C.DIM, false, true); idl.style.cssText += `;position:absolute;right:${dp(12)}px;top:${dp(10)}px`;
    c.append(cv, lb, idl); grid.append(c);
    requestAnimationFrame(() => { sizeCanvas(cv); drawField(cv, id, accent); });
  });
  root.append(grid); return root;
}

/* ---------- boot ---------- */
const SCREENS = { scores: ['Scores · All live now', () => hubFrame('scores')], league: ['Scores · Baseball → MLB', () => hubFrame('league')], map: ['World map', () => hubFrame('map')], following: ['Following', () => hubFrame('following')], 'game-mlb': ['Game · MLB (diamond)', () => gameFrame('mlb')], 'game-nfl': ['Game · NFL (gridiron)', () => gameFrame('nfl')], fields: ['12 drawn fields', fieldsFrame] };
const key = new URLSearchParams(location.search).get('screen') || 'scores';
const nav = document.getElementById('nav');
Object.entries(SCREENS).forEach(([k, [label]]) => { const a = el('a', k === key ? 'on' : '', label); a.href = `?screen=${k}`; a.dataset.testid = `mock-nav-${k}`; nav.querySelector('span').before(a); });
const frame = document.getElementById('frame'); frame.dataset.testid = `mock-screen-${key}`;
const fit = () => { const s = Math.min(window.innerWidth / 1920, (window.innerHeight - 44) / 1080); window.__frameScale = s; frame.style.transform = `scale(${s})`; frame.style.marginLeft = `${(window.innerWidth - 1920 * s) / 2}px`; };
fit(); frame.append(SCREENS[key][1]());
