import { games } from './data.js';

const categories = [
  ['soccer','Football','football','#63d68d'],['f1','Formula 1','f1','#ff5d62'],['motorsport','Motorsport','motorsport','#ffc36a'],
  ['golf','Golf','golf','#8ee0aa'],['cricket','Cricket','cricket','#ff737e'],['tennis','Tennis','tennis','#dcff6c'],
  ['rugby','Rugby Union','rugby','#8abaff'],['nrl','Rugby League','rugby_league','#f898c0'],['afl','AFL','afl','#ff697d'],
  ['nfl','NFL','nfl','#ffc879'],['nba','Basketball','basketball','#ffab78'],['nhl','Ice Hockey','hockey','#8de9ff'],
  ['mlb','Baseball','baseball','#84baff'],['mma','Combat Sports','boxing','#ff778b'],['cycling','Cycling','cycling','#ffe178'],
  ['athletics','Athletics','athletics','#ff98b6'],['wwe','Wrestling','wwe','#efd497'],['other','Other Sports','trophy','#b7cbd2'],
];
const safe = value => String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const sportBucket = {'Baseball':'mlb','Soccer':'soccer','Football':'nfl','Basketball':'nba','Hockey':'nhl','AFL':'afl'};

export function replaysView() {
  const selected = new URLSearchParams(location.search).get('sport');
  const category = categories.find(item=>item[0]===selected);
  if (category) {
    const rows = games.filter(g=>g.state==='post' && g.replayAvailable===true && sportBucket[g.sport]===selected);
    return `<a class="back-link" href="?screen=replays" data-testid="replays-back-categories">← All sports</a><div class="replays-intro"><div><div class="view-eyebrow">SPORTS ARCHIVE / SAMPLE MATCHDAY</div><h2 data-testid="replays-heading">${category[1]} <em>Replays</em></h2></div><span class="archive-meta" data-testid="replays-result-count">${rows.length} SAMPLE RECORDING${rows.length===1?'':'S'}</span></div>${rows.length?`<div class="replay-recordings">${rows.map(g=>`<article class="replay-recording" data-testid="replay-recording-${g.id}"><div class="replay-match-art"><img src="${g.away.logo}" alt="${safe(g.away.name)}" data-testid="replay-away-${g.id}"><span>VS</span><img src="${g.home.logo}" alt="${safe(g.home.name)}" data-testid="replay-home-${g.id}"></div><div class="replay-recording-copy"><span class="archive-meta" data-testid="replay-league-${g.id}">${g.league} · SAMPLE MATCHDAY</span><h3 data-testid="replay-title-${g.id}">${g.away.name}<br><span class="muted">at</span> ${g.home.name}</h3><p data-testid="replay-source-${g.id}">${g.channels.join(' / ')} · Sample archive</p><button class="solid-button" data-demo-replay="${g.id}" data-testid="replay-watch-${g.id}">▶ Watch replay</button><a class="replay-result-link" href="?screen=game-${g.id}" data-testid="replay-result-${g.id}">Match centre →</a></div></article>`).join('')}</div>`:`<div class="replays-empty" data-testid="replays-empty-state"><span aria-hidden="true">↺</span><h3>No sample replays for this sport</h3><p data-testid="replays-empty-description">Actual recordings depend on your connected provider’s archive.</p><a class="outline-button" href="?screen=replays" data-testid="replays-empty-back">Browse all sports</a></div>`}`;
  }
  return `<div class="replays-intro"><div><div class="view-eyebrow">THE SPORTS ARCHIVE</div><h2 data-testid="replays-heading">Worth watching.<br><em>All over again.</em></h2></div><div class="archive-stamp" data-testid="replays-category-count"><strong>18</strong><span>SPORTS<br>ONE LIBRARY</span></div></div><section class="replay-category-grid" aria-label="Replay sports">${categories.map(([id,name,image,accent],index)=>`<a class="replay-category" href="?screen=replays&sport=${id}" style="--sport-accent:${accent}" data-testid="sport-category-tile-${id}"><span class="category-number" aria-hidden="true">${String(index+1).padStart(2,'0')}</span><img src="replay-art/${image}.webp" alt="" aria-hidden="true" class="replay-category-art" loading="${index<6?'eager':'lazy'}"><span class="replay-category-copy"><span class="archive-meta">REPLAYS</span><strong data-testid="replay-category-name-${id}">${name}</strong><span class="category-browse">Browse recordings <b aria-hidden="true">↗</b></span></span></a>`).join('')}</section>`;
}