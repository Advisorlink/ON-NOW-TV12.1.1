import { games, plays, fieldFor } from './data.js';
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
function data(g,index,view='angle') {const uniforms={'mlb-nyy':'#0C2340','mlb-bos':'#BD3039','nfl-kc':'#E31837','nfl-buf':'#00338D','nba-lal':'#552583','nba-bos':'#007A33'};return {sport:fieldFor[g.sport]||'soccer',homeColor:uniforms[g.home.id]||g.home.color,awayColor:uniforms[g.away.id]||g.away.color,homeAbbr:g.home.short,awayAbbr:g.away.short,bases:g.id==='mlb'?[1,3]:[],view,plays:(plays[g.id]||[]).slice(0,index+1).map(p=>({...p,normalized:true}))};}
function connect(frame,payload){frame._scenePayload=payload;frame.addEventListener('load',()=>frame.contentWindow?.postMessage({type:'v2-sports-scene',payload:frame._scenePayload},location.origin));}
function sceneFrame(id){const frame=document.createElement('iframe');frame.src='../sportshub-scene/index.html';frame.className='sports-scene-frame';frame.title='Three-dimensional schematic field with illustrative player figures';frame.dataset.testid=id;frame.tabIndex=-1;return frame;}
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
  const area=document.querySelector('.field-area');if(area&&!area.querySelector('iframe')){
    const frame=sceneFrame('game-field-3d');connect(frame,data(g,index));area.append(frame);
    const button=document.createElement('button');button.className='field-camera';button.textContent='▱ 3D view';button.dataset.testid='field-camera-toggle';button.dataset.premiumCamera='game';button.setAttribute('aria-pressed','false');area.append(button);
    const legend=area.querySelector('.field-legend');if(legend)legend.lastElementChild.textContent='ILLUSTRATIVE FIGURES · SAMPLE';
  }
  if(g.id==='mlb'){
    const active=document.querySelector('.game-situation');
    if(active)active.innerHTML=`<div class="side-heading"><h2>On the diamond</h2><span>TOP 7TH</span></div>${[profiles[3],profiles[2]].map((p,i)=>`<div class="active-athlete" data-testid="situation-${i}">${portrait(p,g,'active')}<div><span class="athlete-role">${i===0?'PITCHING':'AT BAT'}</span><strong>${p.short}</strong><small>${p.line}</small></div>${badge(g[p.side],`active-player-team-${p.id}`)}</div>`).join('')}`;
    playerList(g);
  }
}
export function refreshPremiumPlay(g,index){currentIndex=index;probabilityCrests(g);const frame=document.querySelector('[data-testid="game-field-3d"]');if(frame){frame._scenePayload=data(g,index,frame._scenePayload?.view);frame.contentWindow?.postMessage({type:'v2-sports-scene',payload:frame._scenePayload},location.origin);}}
window.addEventListener('message',event=>{
  if(event.origin!==location.origin||event.data?.type!=='v2-sports-scene-ready')return;
  document.querySelectorAll('.sports-scene-frame').forEach(frame=>{if(frame.contentWindow===event.source){frame.style.visibility=event.data.success?'visible':'hidden';frame.dataset.ready=String(!!event.data.success);}});
});
document.addEventListener('click',event=>{
  const button=event.target.closest('button');if(!button)return;
  if(button.dataset.playerTeam){selectedSide=button.dataset.playerTeam;playerList(currentGame);document.querySelector(`[data-testid="players-filter-${selectedSide}"]`)?.focus({preventScroll:true});}
  if(button.dataset.premiumCamera){const frame=document.querySelector(button.dataset.premiumCamera==='game'?'[data-testid="game-field-3d"]':'[data-testid="expanded-3d-field"]');if(frame){const top=frame._scenePayload.view!=='top';frame._scenePayload.view=top?'top':'angle';frame.contentWindow?.postMessage({type:'v2-sports-scene',payload:frame._scenePayload},location.origin);button.textContent=top?'▱ Top view':'▱ 3D view';button.setAttribute('aria-pressed',String(top));}}
  if(button.dataset.demoReplay){const g=games.find(g=>g.id===button.dataset.demoReplay);if(g?.replayAvailable!==true)return;openModal('Watch replay',`<div class="view-eyebrow">SAMPLE DESIGN · NOT A RECORDING</div><p data-testid="replay-preview-notice">${g.away.name} at ${g.home.name}</p><p>This browser preview has no IPTV connection or recording. In the Android app, replay availability is checked automatically.</p><button class="solid-button" data-close-dialog data-testid="replay-preview-close">Back to game</button>`);}
});
export function enhanceFieldDialog(fieldId){
  const old=document.querySelector('.dialog-canvas');if(!old)return;const holder=document.createElement('div');holder.className='dialog-scene';old.replaceWith(holder);holder.append(old);
  const frame=sceneFrame('expanded-3d-field');connect(frame,{sport:fieldId,homeColor:'#ff5265',awayColor:'#5dc8ff',plays:[],bases:[],view:'angle'});holder.append(frame);
  const camera=document.createElement('button');camera.className='outline-button';camera.textContent='▱ 3D view';camera.dataset.premiumCamera='dialog';camera.dataset.testid='expanded-field-camera';camera.setAttribute('aria-pressed','false');holder.after(camera);
}
document.querySelector('#detail-dialog')?.addEventListener('close',()=>document.querySelectorAll('.dialog-scene iframe').forEach(frame=>frame.remove()));