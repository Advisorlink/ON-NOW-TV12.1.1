import { games, sports, leagues, surfaces, fieldFor, plays, players, standings } from './data.js';
import { drawSurface, drawWorld } from './surfaces.js';
import { enhanceSportsView, refreshPremiumPlay, enhanceFieldDialog } from './premium.js';
import { replaysView } from './replays.js';

const $ = (s) => document.querySelector(s);
const iconPaths = {
  bolt:'M13 2 4 14h7l-1 8 10-13h-7l1-7Z',
  scores:'M4 4h16v16H4z M8 8v3 M16 8v3 M8 16h8',
  globe:'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM3 12h18 M12 3c5 5 5 13 0 18-5-5-5-13 0-18Z',
  star:'m12 3 2.8 5.7 6.3.9-4.6 4.5 1.1 6.3-5.6-3-5.6 3 1.1-6.3L3 9.6l6.2-.9L12 3Z',
  arrow:'M5 12h14 m-6-6 6 6-6 6',back:'M19 12H5m6-6-6 6 6 6',
  tv:'M3 6h18v13H3z M8 22h8 M8 2l4 4 4-4',play:'m9 5 11 7-11 7V5Z',
  bell:'M5 17h14l-2-3V9a5 5 0 0 0-10 0v5l-2 3ZM10 20h4',check:'m5 12 4 4L19 6',
  close:'m6 6 12 12M18 6 6 18',left:'m15 5-7 7 7 7',right:'m9 5 7 7-7 7',
  field:'M3 5h18v14H3z M12 5v14 M3 9h4v6H3 M21 9h-4v6h4',pin:'M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Zm-7-2a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z',
};
const ic = (name) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${iconPaths[name] || iconPaths.arrow}"/></svg>`;
const safe = v => String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const slug = s => s.toLowerCase().replace(/[^a-z0-9]+/g,'-');
const readSet = (key,defaults=[]) => {try {const value=JSON.parse(localStorage.getItem(key));return new Set(Array.isArray(value)?value:defaults);}catch{return new Set(defaults);}};
const follows=readSet('astra-demo-follows',['mlb-nyy','nfl-kc','nba-lal']);
const reminders=readSet('astra-demo-reminders');
const persist = (key,set) => {try{localStorage.setItem(key,JSON.stringify([...set]));}catch{/* Temporary state still works with storage disabled. */}};
let sport='All sports',league='All leagues',screen='scores',mapId='mlb',playIndex=3,toastTimer,activeGame='mlb';
const app=$('#app'),dialog=$('#detail-dialog');
const badge = (t, id) => `<span class="team-badge"><img src="${t.logo}" alt="${safe(t.name)} crest" data-testid="${id}-logo" loading="lazy"></span>`;
const routeFor = g => g.id==='mlb'||g.id==='nfl'?`game-${g.id}`:`game-${g.id}`;
const gameLink = g => `?screen=${routeFor(g)}`;
const teamSide = (t,side,id) => `<span class="team-stack ${side}">${badge(t,`${id}-${side}`)}<span><span class="team-abbr">${t.short}</span><span class="team-record" style="display:block">${t.record}</span></span></span>`;
const pageIntro=(eyebrow,title,sub='',extra='')=>`<div class="view-intro"><div><div class="view-eyebrow">${eyebrow}</div><h2 class="view-title" data-testid="view-title">${title}</h2>${sub?`<p class="view-subtitle" data-testid="view-subtitle">${sub}</p>`:''}</div>${extra}</div>`;

function header(){
  const mode=screen==='map'?'map':screen==='following'?'following':screen==='replays'?'replays':'scores';
  return `<header class="masthead"><a class="brand" href="?screen=scores" data-testid="brand-home" aria-label="V2 Sports Hub"><span class="brand-icon" aria-hidden="true">V2</span><span><span class="brand-kicker">V2 LIVE</span><h1 aria-label="V2 Sports Hub">Sports Hub</h1></span></a><nav class="main-nav" aria-label="Main navigation">${[['scores','Scores','scores'],['replays','Replays','play'],['map','World map','globe'],['following','Following','star']].map(([id,label,ico])=>`<a href="?screen=${id}" class="nav-link ${mode===id?'active':''}" ${mode===id?'aria-current="page"':''} data-testid="${id==='replays'?'top-nav-replays-button':'nav-'+id}">${ic(ico)}${label}</a>`).join('')}</nav><div class="mast-meta"><span class="live-total mono" data-testid="live-game-count"><i class="live-dot"></i>06 LIVE NOW</span><div class="sample-clock" data-testid="sample-clock">19:42<small>DEMO MATCHDAY</small></div></div></header>`;
}
function sportFilters(){return `<nav class="sport-bar" aria-label="Sports">${sports.map(s=>`<button data-sport="${s}" data-testid="sport-${slug(s)}" class="sport-button ${sport===s?'active':''}" aria-pressed="${sport===s}">${s}</button>`).join('')}</nav>${sport!=='All sports'&&leagues[sport]?`<nav class="league-bar" aria-label="Leagues">${leagues[sport].map(l=>`<button data-league="${l}" data-testid="league-${slug(l)}" class="league-button ${league===l?'active':''}" aria-pressed="${league===l}">${l}</button>`).join('')}</nav>`:''}`;}
function footer(){return `<footer class="footer"><span data-testid="footer-data-notice">V2 SPORTS HUB · DESIGN PREVIEW<br>Sample scores · No live playback</span><a href="?screen=fields" data-testid="nav-fields">${ic('field')}Fields & courts ${ic('arrow')}</a></footer>`;}
function scoreCard(g,compact=false){
  const live=g.state==='in',up=g.state==='pre',saved=reminders.has(g.id),id=`card-${g.id}`;
  return `<article class="score-card ${compact?'compact':''} ${g.id==='mlb'&&live?'featured':''}" data-testid="${id}"><a href="${gameLink(g)}" class="card-main" data-testid="open-${g.id}" aria-label="${safe(g.away.name)} at ${safe(g.home.name)}"><div class="card-top"><span class="card-league">${g.league.toUpperCase()}</span><span class="card-phase" data-testid="status-${g.id}">${live?'<i class="live-dot"></i>':''}${g.status}</span></div><div class="matchup">${teamSide(g.away,'away',id)}<span class="score" data-testid="score-${g.id}">${up?'VS':`${g.aScore}<span class="score-sep">:</span>${g.hScore}`}</span>${teamSide(g.home,'home',id)}</div><div class="card-names"><span>${g.away.name}</span><span>${g.home.name}</span></div></a><div class="card-bottom"><span class="channel-label">${ic('tv')}<span data-testid="channels-${g.id}">${g.channels.join(' / ')}</span></span>${up?`<button class="card-action" data-remind="${g.id}" data-testid="remind-${g.id}" aria-pressed="${saved}">${ic(saved?'check':'bell')}${saved?'Reminder set':'Remind me'}</button>`:`<a class="card-action" href="${gameLink(g)}" data-testid="details-${g.id}">${live?'Match centre':'Full result'}${ic('arrow')}</a>`}</div></article>`;
}
function section(title,state,list){
  if(!list.length)return '';
  const upcoming=state==='pre',final=state==='post';
  return `<section aria-label="${title}"><div class="section-heading"><div class="section-title"><i class="status-heading ${upcoming?'upcoming':final?'final':''}"></i><h2 data-testid="heading-${state}">${title}</h2><span class="count" data-testid="count-${state}">${String(list.length).padStart(2,'0')}</span></div><span class="section-note">${upcoming?'DEMO MATCHDAY':final?'THE FINAL WHISTLE':'EVERY SCORE. EVERY MOMENT.'}</span></div><div class="${upcoming?'upcoming-grid':final?'final-grid':'score-grid'}">${list.map(g=>scoreCard(g,upcoming||final)).join('')}</div></section>`;
}
function scoresView(following=false){
  const list=games.filter(g=>(sport==='All sports'||g.sport===sport)&&(league==='All leagues'||g.league===league)&&(!following||follows.has(g.away.id)||follows.has(g.home.id)));
  let top='';
  if(following){const teams=[...new Map(games.flatMap(g=>[g.away,g.home]).map(t=>[t.id,t])).values()].filter(t=>follows.has(t.id));top=pageIntro('YOUR SIDE OF THE GAME','The ones you follow',`${teams.length} teams · ${list.filter(g=>g.state==='in').length} in action`, `<a class="outline-button" href="?screen=scores" data-testid="following-browse">${ic('scores')}All scores</a>`)+`<div class="following-teams">${teams.map(t=>`<div class="following-team" data-testid="following-team-${t.id}">${badge(t,`following-${t.id}`)}<span><strong>${t.name}</strong><small>${t.record}</small></span><button data-follow="${t.id}" data-testid="unfollow-${t.id}" aria-label="Unfollow ${t.name}" title="Unfollow ${t.name}">${ic('star')}</button></div>`).join('')}</div>`;}
  return top+sportFilters()+(list.length?section('Live now','in',list.filter(g=>g.state==='in'))+section('Up next','pre',list.filter(g=>g.state==='pre'))+section('Final scores','post',list.filter(g=>g.state==='post')):`<div class="empty-state" data-testid="empty-games"><h2>${following&&!follows.size?'Your teams belong here':'No sample games in this league'}</h2><p>${following&&!follows.size?'Follow a team from any match centre.':'Choose another sport to see the sample matchday.'}</p><button class="solid-button" data-reset-filters data-testid="reset-filters">${ic('scores')}View all scores</button></div>`);
}
function followButton(t){const following=follows.has(t.id);return `<button class="follow-button ${following?'followed':''}" data-follow="${t.id}" data-testid="follow-${t.id}" aria-pressed="${following}">${ic('star')}${following?'Following':'Follow'}</button>`;}
function gameTeam(t,side){return `<div class="game-team ${side}">${badge(t,`game-${side}`)}<div><h2 data-testid="game-${side}-name">${t.name}</h2><div class="game-team-meta"><span class="record-label">${t.record}</span>${followButton(t)}</div></div></div>`;}
function probability(g,index){const p=plays[g.id]?.[index]?.prob??g.prob;if(p==null)return '';return `<div class="probability"><div class="prob-header"><h3 style="font-size:12px" data-testid="probability-heading">Win probability</h3><span>SAMPLE MODEL</span></div><div class="prob-numbers"><strong style="color:${g.away.color}" data-testid="away-probability">${p}% <small>${g.away.short}</small></strong><strong style="color:${g.home.color}" data-testid="home-probability"><small>${g.home.short}</small>${100-p}%</strong></div><div class="prob-bar" role="img" aria-label="${g.away.short} ${p} percent, ${g.home.short} ${100-p} percent" data-testid="probability-bar"><span style="width:${p}%;background:${g.away.color}"></span><span style="width:${100-p}%;background:${g.home.color}"></span></div></div>`;}
function playPanel(g){
  const list=plays[g.id];if(!list)return `<div class="play-current"><div class="play-description"><h3 data-testid="sample-game-status">${g.state==='pre'?'Kick-off at '+g.status:g.status}</h3><p>Detailed play-by-play is included in the MLB and NFL sample games.</p></div></div>`;
  const p=list[playIndex];return `<div id="probability-container">${probability(g,playIndex)}</div><div class="play-current"><span class="play-tag" data-testid="selected-play-time">${p.time}</span><div class="play-description"><h3 data-testid="selected-play-title">${p.title}</h3><p data-testid="selected-play-text">${p.text}</p></div><div class="play-controls"><button class="icon-button" data-step="-1" data-testid="previous-play" title="Previous play" aria-label="Previous play" ${playIndex===0?'disabled':''}>${ic('left')}</button><button class="icon-button" data-step="1" data-testid="next-play" title="Next play" aria-label="Next play" ${playIndex===list.length-1?'disabled':''}>${ic('right')}</button><button class="live-button" data-play="${list.length-1}" data-testid="latest-play">LATEST</button></div></div><div class="play-timeline" aria-label="Play timeline">${list.map((p,i)=>`<button class="play-item ${i===playIndex?'active':''}" data-play="${i}" data-testid="play-${i}" aria-pressed="${i===playIndex}"><span>${String(i+1).padStart(2,'0')} / ${p.time}</span><p>${p.title}</p></button>`).join('')}</div>`;
}
function gameView(g){
  activeGame=g.id;const up=g.state==='pre',live=g.state==='in',hasPlays=!!plays[g.id];
  const channels=g.id==='mlb'?[...g.channels,'MLB Network']:g.id==='nfl'?['CBS Sports HD','NFL Network','FOX Sports 1']:g.channels;
  return `<a class="back-link" href="?screen=scores" data-testid="back-scores">${ic('back')}All scores<span class="muted"> / ${g.league}</span></a><section class="game-scoreboard" aria-label="Match scoreboard">${gameTeam(g.away,'away')}<div class="game-score-center"><div class="game-status" data-testid="game-status">${live?'<i class="live-dot"></i>LIVE · ':''}${g.status.toUpperCase()}</div><div class="score" data-testid="game-score">${up?'VS':`${g.aScore}<span class="score-sep">:</span>${g.hScore}`}</div><div class="game-venue" data-testid="game-venue">${g.venue} · ${g.league}</div></div>${gameTeam(g.home,'home')}</section><div class="game-body"><section class="game-left" aria-label="Game field and plays"><div class="field-area"><canvas data-surface="${fieldFor[g.sport]||'soccer'}" data-game="${g.id}" data-testid="game-field" role="img" aria-label="${g.sport} field${hasPlays?' showing sample play positions':''}"></canvas><span class="field-label">${g.sport==='Baseball'?'THE DIAMOND':g.sport==='Football'?'THE GRIDIRON':g.sport.toUpperCase()}</span><span class="field-label-right" data-testid="field-context">${g.id==='mlb'?'FENWAY PARK · B2 S1 O1':g.id==='nfl'?'BUF POSSESSION':g.league.toUpperCase()}</span><div class="field-legend"><span><i></i>${hasPlays?'PREVIOUS PLAYS':'FIELD OF PLAY'}</span>${hasPlays?'<span><i style="background:var(--gold)"></i>SELECTED PLAY</span>':''}<span>SCHEMATIC · SAMPLE DATA</span></div></div><div id="play-panel">${playPanel(g)}</div></section><aside class="game-right"><section aria-label="Watch channels"><div class="side-heading"><h2>${up?'Scheduled broadcasts':'Watch this game'}</h2><span data-testid="channel-count">${String(channels.length).padStart(2,'0')} CHANNELS</span></div>${channels.map((ch,i)=>`<button class="channel-row" data-channel="${safe(ch)}" data-testid="watch-channel-${i}"><span class="channel-mark">${safe(ch.split(' ')[0])}</span><span class="channel-copy"><strong>${safe(ch)}</strong><small>${i===0?'EPG MATCH · HD':i===1?'BROADCAST MATCH':'ALTERNATIVE CHANNEL'}</small></span>${ic('play')}</button>`).join('')}</section>${hasPlays?`<section class="game-situation"><div class="side-heading"><h2>In the game</h2><span>${g.status.toUpperCase()}</span></div>${(g.id==='mlb'?[['Pitching','T. Houck · 6.1 IP · 5 K'],['At bat','G. Stanton · 1–3, BB'],['On base','1st & 3rd · 1 out']]:[['Possession','Buffalo Bills'],['Latest score','34-yard touchdown pass'],['Extra point','Tyler Bass · GOOD']]).map(([label,value],i)=>`<div class="situation-row" data-testid="situation-${i}"><span>${label}</span><strong>${value}</strong></div>`).join('')}</section><section class="players-block"><div class="side-heading"><h2>Game leaders</h2><span>PLAYER LINES</span></div>${players[g.id].map(([name,team,stat,detail],i)=>`<div class="player-row" data-testid="player-${i}"><span class="player-name">${name}<small>${team}</small></span><strong class="player-stat">${stat}</strong><span class="player-detail">${detail}</span></div>`).join('')}<button class="text-button" data-standings="${g.league}" data-testid="open-standings">League standings${ic('arrow')}</button></section>`:''}${up?`<button class="solid-button" style="margin-top:20px;width:100%" data-remind="${g.id}" data-testid="game-reminder" aria-pressed="${reminders.has(g.id)}">${ic(reminders.has(g.id)?'check':'bell')}${reminders.has(g.id)?'Reminder set':'Remind me'}</button>`:''}</aside></div>`;
}
function mapView(){
  const selected=games.find(g=>g.id===mapId)||games[0],liveGames=games.filter(g=>g.state==='in');
  const markerPos=g=>{let x=(g.geo[1]+180)/360*100,y=(83-g.geo[0])/143*100;if(g.id==='nba'){x+=1.6;y+=1.8;}if(g.id==='nhl')y-=1.7;return [x,y];};
  return pageIntro('THE WORLD IS IN PLAY','One world. All the action.','6 live games · 3 continents',`<span class="live-total mono" data-testid="map-live-count"><i class="live-dot"></i>LIVE AROUND THE WORLD</span>`)+`<div class="map-layout"><section class="map-surface" aria-label="World map of sample games"><canvas id="world-canvas" data-testid="world-map" role="img" aria-label="World map with selectable live and upcoming game locations"></canvas>${[['NORTH AMERICA',13,30],['SOUTH AMERICA',29,66],['EUROPE',51,22],['AFRICA',50,53],['ASIA',74,31],['OCEANIA',84,81]].map(([label,x,y])=>`<span class="map-label" style="left:${x}%;top:${y}%">${label}</span>`).join('')}${games.filter(g=>g.state!=='post').map(g=>{const [x,y]=markerPos(g);return `<button class="map-marker ${g.state==='pre'?'pre':''} ${g.id===mapId?'selected':''}" style="left:${x}%;top:${y}%" data-map-game="${g.id}" data-testid="map-marker-${g.id}" aria-label="${safe(g.away.name)} at ${safe(g.home.name)}, ${g.city}" title="${g.away.short} at ${g.home.short} · ${g.city}" aria-pressed="${g.id===mapId}"></button>`;}).join('')}<div class="map-selected" data-testid="map-selected-game">${ic('pin')}<span>${selected.away.short} <span class="muted">at</span> ${selected.home.short}<small>${selected.venue} · ${selected.city}</small></span></div><div class="map-caption"><i class="live-dot"></i>LIVE <span style="margin-left:15px;color:var(--cyan)">●</span> UPCOMING</div></section><aside><div class="side-heading"><h2>On the map</h2><span>LIVE NOW</span></div><div class="map-game-list">${liveGames.map(g=>`<button class="map-list-button ${mapId===g.id?'active':''}" data-map-game="${g.id}" data-testid="map-select-${g.id}" aria-pressed="${mapId===g.id}"><span><strong>${g.away.short} <span class="muted">/</span> ${g.home.short}</strong><small>${g.city} · ${g.league}</small></span><span class="map-list-score">${g.aScore} : ${g.hScore}</span></button>`).join('')}<a class="solid-button map-open" href="${gameLink(selected)}" data-testid="map-open-game">Open ${selected.away.short} / ${selected.home.short}${ic('arrow')}</a></div></aside></div>`;
}
function fieldsView(){return pageIntro('EVERY SPORT HAS ITS STAGE','Fields of play','12 sports. Twelve different ways to follow the action.',`<a class="outline-button" href="?screen=game-mlb" data-testid="fields-see-live">${ic('play')}See a match</a>`)+`<section class="fields-grid" aria-label="Sports fields">${surfaces.map(([id,name,caption,color],i)=>`<button class="field-tile" data-field="${id}" data-testid="field-${id}"><span class="field-tile-head"><strong>${name}</strong><span>${String(i+1).padStart(2,'0')}</span></span><canvas data-surface="${id}" data-color="${color}" data-testid="surface-${id}" aria-label="${name} field schematic" role="img"></canvas><span class="field-tile-foot">${caption}${ic('arrow')}</span></button>`).join('')}</section>`;}

function getScreen(){return new URLSearchParams(location.search).get('screen')||'scores';}
function render({keepScroll=false,focusId=null}={}){
  screen=getScreen();const scroll=window.scrollY;
  const g=screen.startsWith('game-')?games.find(g=>g.id===screen.slice(5)):null;
  if(screen==='league'&&sport==='All sports'){sport='Baseball';league='MLB';}
  const body=g?gameView(g):screen==='replays'?replaysView():screen==='map'?mapView():screen==='fields'?fieldsView():scoresView(screen==='following');
  app.innerHTML=`<div class="shell">${header()}<main class="screen-enter" data-testid="astra-screen-${safe(screen)}">${body}</main>${footer()}</div>`;
  const originalKeys=['scores','league','map','following','game-mlb','game-nfl','fields'];
  $('#compare-original').href=`../sportshub-mockup/index.html?screen=${originalKeys.includes(screen)?screen:'scores'}`;
  renderCanvases();
  enhanceSportsView(g,playIndex,openDialog);
  if(keepScroll)window.scrollTo(0,scroll);else window.scrollTo(0,0);
  if(focusId)document.querySelector(`[data-testid="${focusId}"]`)?.focus({preventScroll:true});
}
function navigate(href){const u=new URL(href,location.href);history.pushState({},'',u);sport='All sports';league='All leagues';playIndex=3;render();}
function layoutMapMarkers(){
  const surface=$('.map-surface');if(!surface)return;
  const surfaceRect=surface.getBoundingClientRect(),{width,height}=surfaceRect,placed=[],leaders=[];
  const reserved=[...surface.querySelectorAll('.map-selected,.map-caption')].map(el=>{const r=el.getBoundingClientRect();return{left:r.left-surfaceRect.left-15,right:r.right-surfaceRect.left+15,top:r.top-surfaceRect.top-15,bottom:r.bottom-surfaceRect.top+15};});
  const gap=width<520?27:32;
  surface.querySelectorAll('.map-marker').forEach(marker=>{
    const g=games.find(g=>g.id===marker.dataset.mapGame);
    const anchor={x:(g.geo[1]+180)/360*width,y:(83-g.geo[0])/143*height};
    let spot=anchor,found=false;
    for(let radius=0;radius<=108&&!found;radius+=4){
      for(let step=0;step<16;step++){
        const angle=step*Math.PI/8,x=anchor.x+Math.cos(angle)*radius,y=anchor.y+Math.sin(angle)*radius;
        if(x<16||x>width-16||y<16||y>height-16||reserved.some(r=>x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom))continue;
        if(placed.every(p=>Math.hypot(x-p.x,y-p.y)>=gap)){spot={x,y};found=true;break;}
      }
    }
    placed.push(spot);marker.style.left=`${spot.x/width*100}%`;marker.style.top=`${spot.y/height*100}%`;
    if(Math.hypot(spot.x-anchor.x,spot.y-anchor.y)>4)leaders.push(`<line x1="${anchor.x}" y1="${anchor.y}" x2="${spot.x}" y2="${spot.y}"/><circle cx="${anchor.x}" cy="${anchor.y}" r="1.6"/>`);
  });
  surface.querySelector('.map-leaders')?.remove();
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.classList.add('map-leaders');svg.setAttribute('viewBox',`0 0 ${width} ${height}`);svg.setAttribute('aria-hidden','true');svg.innerHTML=leaders.join('');surface.insertBefore(svg,surface.querySelector('.map-marker'));
}
function renderCanvases(){document.querySelectorAll('canvas[data-surface]').forEach(cv=>drawSurface(cv,cv.dataset.surface,cv.dataset.color||'#00e6ed',cv.dataset.game?plays[cv.dataset.game]||[]:[],playIndex));const world=$('#world-canvas');if(world){drawWorld(world);layoutMapMarkers();}}
function notify(message){clearTimeout(toastTimer);$('#toast').textContent=message;$('#toast').classList.add('show');toastTimer=setTimeout(()=>$('#toast').classList.remove('show'),2600);}
function openDialog(title,content){dialog.innerHTML=`<div class="dialog-head"><h2 id="dialog-title" data-testid="dialog-title">${title}</h2><button class="icon-button" data-close-dialog data-testid="dialog-close" aria-label="Close dialog" title="Close">${ic('close')}</button></div>${content}`;dialog.showModal();}
function updatePlays(index,focusId){const g=games.find(g=>g.id===activeGame),list=plays[g.id];playIndex=Math.max(0,Math.min(list.length-1,index));$('#play-panel').innerHTML=playPanel(g);renderCanvases();refreshPremiumPlay(g,playIndex);const next=document.querySelector(`[data-testid="${focusId}"]`);if(next&&!next.disabled)next.focus({preventScroll:true});else document.querySelector(`[data-testid="play-${playIndex}"]`)?.focus({preventScroll:true});}

document.addEventListener('click',e=>{
  const link=e.target.closest('a');if(link){if(e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;const url=new URL(link.href,location.href);if(url.pathname===location.pathname&&url.searchParams.has('screen')){e.preventDefault();navigate(link.href);}return;}
  const b=e.target.closest('button');if(!b)return;const d=b.dataset,focusId=d.testid;
  if(d.sport){sport=d.sport;league=leagues[sport]?.[0]||'All leagues';render({keepScroll:true,focusId});}
  if(d.league){league=d.league;render({keepScroll:true,focusId});}
  if('resetFilters'in d){if(screen==='following'&&!follows.size)navigate('?screen=scores');else{sport='All sports';league='All leagues';render();}}
  if(d.follow){follows.has(d.follow)?follows.delete(d.follow):follows.add(d.follow);persist('astra-demo-follows',follows);render({keepScroll:true,focusId});notify(follows.has(d.follow)?'Team added to Following':'Team removed from Following');}
  if(d.remind){reminders.has(d.remind)?reminders.delete(d.remind):reminders.add(d.remind);persist('astra-demo-reminders',reminders);render({keepScroll:true,focusId});notify(reminders.has(d.remind)?'Demo reminder saved · No notification will be sent':'Demo reminder removed');}
  if(d.play!==undefined)updatePlays(Number(d.play),focusId);
  if(d.step)updatePlays(playIndex+Number(d.step),focusId);
  if(d.mapGame){mapId=d.mapGame;render({keepScroll:true,focusId});}
  if(d.channel){openDialog('Your game. Your channel.',`<div class="view-eyebrow">SAMPLE CHANNEL MATCH</div><div class="dialog-channel"><span class="channel-mark">${safe(d.channel.split(' ')[0])}</span><strong data-testid="dialog-channel-name">${safe(d.channel)}</strong></div><p data-testid="playback-demo-notice">This is a design preview. Live playback and channel switching are not connected.</p><button class="solid-button" data-close-dialog data-testid="channel-return">Back to match ${ic('arrow')}</button>`);}
  if(d.standings){const rows=standings[d.standings]||[];openDialog(`${d.standings} standings`,`<div class="view-eyebrow">${d.standings==='MLB'?'AMERICAN LEAGUE EAST':'AFC EAST'} · SAMPLE DATA</div><table class="standings-table" data-testid="standings-table"><thead><tr><th>TEAM</th><th>W</th><th>L</th><th>GB</th></tr></thead><tbody>${rows.map(([name,w,l,gb],i)=>`<tr data-testid="standing-${i}"><td><span class="muted">${i+1}.</span> ${name}</td><td>${w}</td><td>${l}</td><td>${gb}</td></tr>`).join('')}</tbody></table>`);}
  if(d.field){const [id,name,,color]=surfaces.find(s=>s[0]===d.field);openDialog(name,`<div class="view-eyebrow">FIELD OF PLAY · SCHEMATIC</div><canvas class="dialog-canvas" data-testid="expanded-field" role="img" aria-label="${name} field"></canvas>`);drawSurface($('.dialog-canvas'),id,color);enhanceFieldDialog(id);}
  if('closeDialog'in d)dialog.close();
});
// Native-like geometric D-pad navigation without autoplay or a render loop.
document.addEventListener('keydown',e=>{
  if(!['ArrowUp','ArrowDown','ArrowLeft','ArrowRight','Escape'].includes(e.key))return;
  if(e.key==='Escape'){if(dialog.open)dialog.close();else if(screen==='replays')navigate(new URLSearchParams(location.search).has('sport')?'?screen=replays':'?screen=scores');else if(screen.startsWith('game-')||screen==='fields')navigate('?screen=scores');return;}
  const root=dialog.open?dialog:document;
  const nodes=[...root.querySelectorAll('button:not(:disabled),a[href]')].filter(el=>el.getBoundingClientRect().width&&el.getBoundingClientRect().height);
  const current=document.activeElement;const r=current?.getBoundingClientRect();
  if(!nodes.includes(current)){nodes[0]?.focus();e.preventDefault();return;}
  const vertical=e.key==='ArrowUp'||e.key==='ArrowDown',sign=e.key==='ArrowUp'||e.key==='ArrowLeft'?-1:1;
  const x=r.left+r.width/2,y=r.top+r.height/2;
  const target=nodes.filter(el=>el!==current).map(el=>{const q=el.getBoundingClientRect(),dx=q.left+q.width/2-x,dy=q.top+q.height/2-y,forward=(vertical?dy:dx)*sign,cross=Math.abs(vertical?dx:dy);return{el,forward,score:forward+cross*3};}).filter(v=>v.forward>4).sort((a,b)=>a.score-b.score)[0];
  if(target){target.el.focus({preventScroll:true});target.el.scrollIntoView({block:'nearest',inline:'nearest'});}e.preventDefault();
});
dialog.addEventListener('click',e=>{if(e.target===dialog){const r=dialog.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)dialog.close();}});
document.addEventListener('error',e=>{if(e.target.tagName==='IMG'){const img=e.target;if(!img.dataset.fallback){img.dataset.fallback='true';img.src='crest-fallback.svg';}}},true);
window.addEventListener('popstate',()=>{sport='All sports';league='All leagues';playIndex=3;render();});
let resizeTimer;window.addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{renderCanvases();if(dialog.open&&$('.dialog-canvas')){const surface=surfaces.find(s=>s[1]===$('#dialog-title').textContent);if(surface)drawSurface($('.dialog-canvas'),surface[0],surface[3]);}},100);});
render();
document.fonts.ready.then(renderCanvases);