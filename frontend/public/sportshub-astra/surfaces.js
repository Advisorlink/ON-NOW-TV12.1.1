// Display-only sports geometry. Coordinates and plays in this prototype are sample data.
export function drawSurface(canvas, kind, color = '#00e6ed', events = [], selected = events.length - 1) {
  const rect = canvas.getBoundingClientRect();
  if (!rect.width || !rect.height) return;
  const dpr = Math.min(devicePixelRatio || 1, 2);
  canvas.width = Math.round(rect.width * dpr); canvas.height = Math.round(rect.height * dpr);
  const c = canvas.getContext('2d');
  c.scale(dpr, dpr);
  const scale = Math.min(rect.width / 1000, rect.height / 560);
  c.translate((rect.width - scale * 1000) / 2, (rect.height - scale * 560) / 2);
  c.scale(scale, scale);
  const path = (fn, fill = null, stroke = color+'66', width = 2) => {c.beginPath();fn();if(fill){c.fillStyle=fill;c.fill();}if(stroke){c.strokeStyle=stroke;c.lineWidth=width;c.stroke();}};
  const line = (x1,y1,x2,y2,col=color+'55',width=2) => path(()=>{c.moveTo(x1,y1);c.lineTo(x2,y2);},null,col,width);
  const box = (x,y,w,h,fill=null,col=color+'66',width=2) => path(()=>c.rect(x,y,w,h),fill,col,width);
  const circle = (x,y,r,fill=null,col=color+'66',width=2) => path(()=>c.arc(x,y,r,0,Math.PI*2),fill,col,width);
  const oval = (x,y,rx,ry,fill=null,col=color+'66') => path(()=>c.ellipse(x,y,rx,ry,0,0,Math.PI*2),fill,col);
  const label = (str,x,y,sz=14,col='#7894a1') => {c.fillStyle=col;c.font=`500 ${sz}px 'JetBrains Mono', monospace`;c.textAlign='center';c.fillText(str,x,y);};
  const diamond = (x,y,size,fill=null,col=color) => path(()=>{c.moveTo(x,y-size);c.lineTo(x+size,y);c.lineTo(x,y+size);c.lineTo(x-size,y);c.closePath();},fill,col);
  const dash = fn => {c.setLineDash([8,8]);fn();c.setLineDash([]);};
  const pitch = () => box(100,100,800,360,color+'08');
  switch(kind){
    case 'mlb': {
      path(()=>{c.moveTo(500,466);c.lineTo(201,190);c.bezierCurveTo(295,-5,708,-5,799,190);c.closePath();},color+'0b',color+'80');
      for(let i=0;i<5;i++) path(()=>c.arc(500,466,160+i*49,Math.PI*1.22,Math.PI*1.78),null,color+'13',20);
      path(()=>{c.moveTo(500,463);c.lineTo(377,339);c.lineTo(500,215);c.lineTo(623,339);c.closePath();},'#8475681e',color+'90');
      circle(500,339,21,'#a9a39b13',color+'55');
      line(492,339,508,339,color,3);
      [[500,463],[377,339],[500,215],[623,339]].forEach(([x,y],i)=>diamond(x,y,7,i===1||i===3?'#ffdb70':color+'50',i===1||i===3?'#ffdb70':color));
      dash(()=>{line(500,463,178,167,color+'44');line(500,463,822,167,color+'44');});
      label('310',215,155,13);label('420',500,55,13);label('302',785,155,13);
      label('LF',300,260,11);label('CF',500,133,11);label('RF',700,260,11);
      if(events.length){dash(()=>line(500,459,events[selected].x*1000,events[selected].y*560,color+'77',2));}
      break;
    }
    case 'nfl': {
      pitch();box(100,100,70,360,color+'10');box(830,100,70,360,color+'10');
      for(let i=0;i<=10;i++){const x=170+i*66;line(x,100,x,460,color+(i===5?'a0':'40'));if(i>0&&i<10){label(String(i>5?(10-i)*10:i*10),x,148,21);label(String(i>5?(10-i)*10:i*10),x,433,21);}}
      for(let i=1;i<50;i++){const x=170+i*13.2;line(x,222,x,231,color+'66',1);line(x,329,x,338,color+'66',1);}
      c.save();c.translate(137,280);c.rotate(-Math.PI/2);label('CHIEFS',0,0,21,color+'bb');c.restore();
      c.save();c.translate(866,280);c.rotate(Math.PI/2);label('BILLS',0,0,21,color+'bb');c.restore();
      if(events.length){const p=events[selected];line(p.x*1000,101,p.x*1000,459,'#00e6ed',3);dash(()=>line(Math.min(817,p.x*1000+66),101,Math.min(817,p.x*1000+66),459,'#ffdb70',2));}
      break;
    }
    case 'soccer':pitch();line(500,100,500,460);circle(500,280,60);circle(500,280,3,color);[100,764].forEach(x=>{box(x,180,136,200);box(x===100?100:852,227,48,106);circle(x===100?201:799,280,3,color);});break;
    case 'nba':pitch();line(500,100,500,460);circle(500,280,59);[100,750].forEach(x=>{box(x,220,150,120);circle(x===100?250:750,280,43);});[146,854].forEach((x,i)=>{circle(x,280,9);line(x+(i?12:-12),259,x+(i?12:-12),301,color,4);path(()=>c.arc(x,280,150,i?Math.PI*.58:-Math.PI*.42,i?Math.PI*1.42:Math.PI*.42));});break;
    case 'nhl':path(()=>c.roundRect(100,100,800,360,105),color+'09');line(500,100,500,460,'#ff6278aa');circle(500,280,54);[357,643].forEach(x=>line(x,100,x,460,'#7fb9ff99',4));[237,763].forEach(x=>[197,363].forEach(y=>{circle(x,y,43);circle(x,y,3,color);}));[150,850].forEach(x=>line(x,158,x,402,'#ff627888'));break;
    case 'tennis':pitch();line(100,157,900,157);line(100,403,900,403);line(310,157,310,403);line(690,157,690,403);line(310,280,690,280);line(500,85,500,475,color+'bb',5);break;
    case 'cricket':oval(500,280,395,191,color+'09');dash(()=>oval(500,280,284,139));box(472,177,56,206,'#c8b79015');[198,362].forEach(y=>line(450,y,550,y));[177,383].forEach(y=>line(485,y,515,y,color,5));break;
    case 'afl':oval(500,280,399,184,color+'09');box(421,201,158,158);circle(500,280,35);circle(500,280,10);[102,898].forEach(x=>{box(x===102?x:x-32,245,32,70);dash(()=>path(()=>c.arc(x,280,142,x===102?-1.08:Math.PI-1.08,x===102?1.08:Math.PI+1.08)));});break;
    case 'rugby':pitch();line(500,100,500,460);[166,303,697,834].forEach(x=>line(x,100,x,460));[432,568].forEach(x=>dash(()=>line(x,100,x,460)));[164,836].forEach(x=>{line(x,251,x,309,color,5);});break;
    case 'mma':{const oct=(r)=>{for(let i=0;i<8;i++){const a=(i+.5)*Math.PI/4;i?c.lineTo(500+Math.cos(a)*r,280+Math.sin(a)*r):c.moveTo(500+Math.cos(a)*r,280+Math.sin(a)*r);}c.closePath();};path(()=>oct(219),color+'09');path(()=>oct(188),null,color+'40');circle(500,280,46);break;}
    case 'f1':{const track=()=>{c.moveTo(285,438);c.lineTo(717,438);c.bezierCurveTo(916,431,924,267,739,271);c.lineTo(555,284);c.bezierCurveTo(480,278,495,81,345,99);c.bezierCurveTo(147,117,134,401,285,438);};path(track,null,color+'20',20);dash(()=>path(track,null,color+'cc',2));for(let i=0;i<4;i++)box(500+i*6,426,6,23,i%2?'#090e15':color,null);break;}
    case 'golf':oval(500,290,306,157,color+'09');dash(()=>oval(500,290,222,105));oval(500,290,124,51);circle(500,290,9,color);line(500,290,500,143,color,3);path(()=>{c.moveTo(500,143);c.lineTo(578,165);c.lineTo(500,190);c.closePath();},color+'99',color);break;
  }
  events.slice(0,selected+1).forEach((p,i)=>{const x=p.x*1000,y=p.y*560;circle(x,y,i===selected?8:5,i===selected?'#ffdb70':color,null);if(i===selected){circle(x,y,17,null,'#ffdb70',2);circle(x,y,23,null,'#ffdb7044',2);}else circle(x,y,10,null,color+'55');});
}

let worldPromise;
export async function drawWorld(canvas){
  const rect=canvas.getBoundingClientRect();if(!rect.width||!rect.height)return;
  const dpr=Math.min(devicePixelRatio||1,2);canvas.width=rect.width*dpr;canvas.height=rect.height*dpr;
  const c=canvas.getContext('2d');c.scale(dpr,dpr);
  try{
    worldPromise ||= fetch('world.geojson').then(r=>{if(!r.ok)throw new Error('Map unavailable');return r.json();});
    const world=await worldPromise;if(!canvas.isConnected)return;
    const project=([lon,lat])=>[(lon+180)/360*rect.width,(83-lat)/143*rect.height];
    c.fillStyle='#1a2b35';c.strokeStyle='#36515e';c.lineWidth=.65;
    for(const feature of world.features){if(feature.properties.ADMIN==='Antarctica')continue;const polygons=feature.geometry.type==='MultiPolygon'?feature.geometry.coordinates:[feature.geometry.coordinates];for(const polygon of polygons){c.beginPath();for(const ring of polygon){ring.forEach((p,i)=>{const [x,y]=project(p);i?c.lineTo(x,y):c.moveTo(x,y);});c.closePath();}c.fill('evenodd');c.stroke();}}
  }catch{c.fillStyle='#b1c5d1';c.font='12px sans-serif';c.fillText('Map unavailable — select a game from the list.',15,canvas.height/dpr/2);}
}