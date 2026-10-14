import { games } from './data.js';
const escape = s => String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const profiles = [
  {id:'33192',name:'Aaron Judge',short:'A. Judge',side:'away',pos:'RF',line:'2–3 · HR · 2 RBI'},
  {id:'32081',name:'Gerrit Cole',short:'G. Cole',side:'away',pos:'SP',line:'6.0 IP · 8 K · 3 ER'},
  {id:'30583',name:'Giancarlo Stanton',short:'G. Stanton',side:'away',pos:'DH',line:'1–3 · BB'},
  {id:'41009',name:'Tanner Houck',short:'T. Houck',side:'home',pos:'SP',line:'6.1 IP · 5 K'},
];
const lineup = {
  mlb:[['206','ESPN US HD','ESPN',true],['631','YES Network HD','YES',true],['213','MLB Network HD','MLB',false]],
  nfl:[['221','CBS Sports HD','CBS',true],['212','NFL Network HD','NFL',false]],
  dodgers:[['104','FOX HD','FOX',true]],cowboys:[['105','NBC HD','NBC',true],['818','Peacock Sports','PCK',false]],
  seriea:[['817','Paramount+ Sports','P+',false]],afl:[['504','Fox Footy HD','FOX',true]],astros:[['213','MLB Network HD','MLB',true]],derby:[['242','USA Network HD','USA',true]],
};
let openModal, selectedSide='both',lastGame, currentGame, currentIndex=3;
const badge=(team,id)=>`<img src="${team.logo}" alt="${escape(team.name)} crest" data-testid="${id}">`;
function probabilityCrests(g){document.querySelectorAll('.prob-numbers strong').forEach((element,index)=>{if(element.querySelector('.prob-crest'))return;const img=document.createElement('img');img.src=(index===0?g.away:g.home).logo;img.className='prob-crest';img.alt=(index===0?g.away:g.home).name+' crest';img.dataset.testid=`probability-${index===0?'away':'home'}-crest`;index===0?element.prepend(img):element.append(img);});}
function portrait(p,g,prefix){const t=g[p.side];return `<div class="portrait" style="--portrait-team:${t.color}"><img src="portraits/${p.id}.png" alt="${p.name}" data-testid="${prefix}-portrait-${p.id}"></div>`;}
function playerList(g){
  const block=document.querySelector('.players-block');if(!block||g.id!=='mlb')return;
  block.innerHTML=`<div class="side-heading"><h2>Players</h2><span>SAMPLE LINES</span></div><div class="player-team-tabs" role="group" aria-label="Player team">${['both','away','home'].map(side=>`<button data-player-team="${side}" data-testid="players-filter-${side}" aria-pressed="${selectedSide===side}">${side==='both'?'Both':badge(g[side],`players-filter-crest-${side}`)+g[side].short}</button>`).join('')}</div>${profiles.filter(p=>selectedSide==='both'||p.side===selectedSide).map(p=>`<div class="premium-player" data-testid="player-profile-${p.id}">${portrait(p,g,'leader')}<div><strong>${p.short}</strong><small>${g[p.side].short} · ${p.pos}</small><span>${p.line}</span></div>${badge(g[p.side],`player-team-crest-${p.id}`)}</div>`).join('')}<button class="text-button" data-standings="${g.league}" data-testid="open-standings">League standings <span aria-hidden="true">→</span></button>`;
}
function fixtureGraphics(){
  document.querySelectorAll('.score-card').forEach(card=>{
    const g=games.find(g=>`card-${g.id}`===card.dataset.testid);if(!g)return;
    card.style.setProperty('--away-team',g.away.color);card.style.setProperty('--home-team',g.home.color);
    if(!card.querySelector('.team-watermark')){const img=document.createElement('img');img.src=g.home.logo;img.alt='';img.setAttribute('aria-hidden','true');img.className='team-watermark';img.dataset.testid=`watermark-${g.id}`;card.prepend(img);}
    const ch=lineup[g.id]?.[0];if(!ch)return;
    const label=card.querySelector('.channel-label');label.classList.add('fixture-lineup');label.innerHTML=`<span class="mini-network">${ch[2]}</span><span><strong data-testid="fixture-channel-${g.id}">${ch[0]} · ${ch[1]}</strong><small data-testid="fixture-channel-confidence-${g.id}">${ch[3]?'GUIDE MATCH':'POSSIBLE BROADCAST'} · SAMPLE</small></span>`;
    if(g.state==='post'&&g.replayAvailable===true&&!card.querySelector('[data-demo-replay]')){const action=card.querySelector('.card-action');action.outerHTML=`<button class="card-action replay-card-button" data-demo-replay="${g.id}" data-testid="fixture-replay-${g.id}">↻ Watch replay</button>`;}
  });
}
function broadcasts(g){
  const section=document.querySelector('section[aria-label="Watch channels"]'),channels=lineup[g.id];if(!section||!channels)return;
  section.innerHTML=`<div class="side-heading"><h2>${g.state==='pre'?'Scheduled on your channels':g.state==='post'?'Broadcast & catch-up':'On your channels'}</h2><span data-testid="channel-count">${channels.length} · SAMPLE LINEUP</span></div>${channels.map((ch,i)=>`<button class="channel-row" data-channel="${ch[1]}" data-testid="watch-channel-${i}"><span class="channel-mark">${ch[2]}</span><span class="channel-copy"><strong>${ch[0]} · ${ch[1]}</strong><small>${ch[3]?'GUIDE MATCH · SAMPLE':'POSSIBLE BROADCAST · SAMPLE'}${g.state==='pre'?' · '+g.status:''}</small></span><span aria-hidden="true">${g.state==='in'?'▶':'›'}</span></button>`).join('')}${g.state==='post'&&g.replayAvailable===true?`<button class="solid-button replay-command" data-demo-replay="${g.id}" data-testid="watch-replay">↻ Watch replay</button><div class="replay-demo" data-testid="replay-sample-label">SAMPLE CATCH-UP AVAILABILITY</div>`:''}`;
}
export function enhanceSportsView(g,index,showModal){
  openModal=showModal;fixtureGraphics();if(!g)return;
  currentGame=g;currentIndex=index;if(lastGame!==g.id){selectedSide='both';lastGame=g.id;}
  probabilityCrests(g);broadcasts(g);
  const legend=document.querySelector('.field-legend');if(legend)legend.lastElementChild.textContent='2D FIELD · SAMPLE PLAYS';
  if(g.id==='mlb'){
    const active=document.querySelector('.game-situation');
    if(active)active.innerHTML=`<div class="side-heading"><h2>On the diamond</h2><span>TOP 7TH</span></div>${[profiles[3],profiles[2]].map((p,i)=>`<div class="active-athlete" data-testid="situation-${i}">${portrait(p,g,'active')}<div><span class="athlete-role">${i===0?'PITCHING':'AT BAT'}</span><strong>${p.short}</strong><small>${p.line}</small></div>${badge(g[p.side],`active-player-team-${p.id}`)}</div>`).join('')}`;
    playerList(g);
  }
}
export function refreshPremiumPlay(g,index){currentIndex=index;probabilityCrests(g);}
document.addEventListener('click',event=>{
  const button=event.target.closest('button');if(!button)return;
  if(button.dataset.playerTeam){selectedSide=button.dataset.playerTeam;playerList(currentGame);document.querySelector(`[data-testid="players-filter-${selectedSide}"]`)?.focus({preventScroll:true});}
  if(button.dataset.demoReplay){const g=games.find(g=>g.id===button.dataset.demoReplay);if(g?.replayAvailable!==true)return;openModal('Watch replay',`<div class="view-eyebrow">SAMPLE DESIGN · NOT A RECORDING</div><p data-testid="replay-preview-notice">${g.away.name} at ${g.home.name}</p><p>This browser preview has no IPTV connection or recording. In the Android app, replay availability is checked automatically.</p><button class="solid-button" data-close-dialog data-testid="replay-preview-close">Back to game</button>`);}
});
export function enhanceFieldDialog(fieldId){
  document.querySelector('.dialog-canvas')?.setAttribute('data-field-kind',fieldId);
}