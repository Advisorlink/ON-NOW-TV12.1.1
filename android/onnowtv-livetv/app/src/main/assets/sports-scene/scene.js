/* Three.js r160, WebGL1-compatible. One scene, no idle render loop, no network or tracking data invented. */
(function () {
  'use strict';
  const T = window.THREE;
  let renderer, scene, camera, world, state = {}, frame = 0, disposed = false;
  const mats = new Map(), geometries = [], textures = [];
  const mat = (color, roughness = .85) => {
    const key = `${color}/${roughness}`;
    if (!mats.has(key)) mats.set(key, new T.MeshStandardMaterial({color, roughness, metalness:0}));
    return mats.get(key);
  };
  function mesh(geometry, material, x=0, y=0, z=0, parent=world) {
    geometries.push(geometry); const item=new T.Mesh(geometry, material);item.position.set(x,y,z);parent.add(item);return item;
  }
  function box(x,y,z,w,h,d,color,parent=world) {return mesh(new T.BoxGeometry(w,h,d),mat(color),x,y,z,parent);}
  function sphere(x,y,z,r,color,parent=world) {return mesh(new T.SphereGeometry(r,10,8),mat(color),x,y,z,parent);}
  function disk(x,y,z,r,color,parent=world) {const m=mesh(new T.CircleGeometry(r,48),mat(color),x,y,z,parent);m.rotation.x=-Math.PI/2;return m;}
  function line(points,color='#f1e6d2',width=.045,y=.085,parent=world){
    for(let i=1;i<points.length;i++) {const a=points[i-1],b=points[i],dx=b[0]-a[0],dz=b[1]-a[1],length=Math.hypot(dx,dz);const m=box((a[0]+b[0])/2,y,(a[1]+b[1])/2,width,.025,length,color,parent);m.rotation.y=Math.atan2(dx,dz);}
  }
  function rect(x,z,w,h,color='#f1e6d2',y=.09){line([[x,z],[x+w,z],[x+w,z+h],[x,z+h],[x,z]],color,.045,y);}
  function arc(x,z,r,start=0,end=Math.PI*2,color='#f1e6d2',y=.1) {const pts=[];for(let a=start;a<=end+.001;a+=(end-start)/64)pts.push([x+Math.cos(a)*r,z+Math.sin(a)*r]);line(pts,color,.04,y);}
  function label(text,x,y,z,color='#d9e9e3',size=.9){
    const c=document.createElement('canvas');c.width=256;c.height=80;const ctx=c.getContext('2d');ctx.font='600 40px sans-serif';ctx.textAlign='center';ctx.fillStyle=color;ctx.fillText(String(text).slice(0,15),128,51);
    const texture=new T.CanvasTexture(c);texture.colorSpace=T.SRGBColorSpace;textures.push(texture);
    const material=new T.SpriteMaterial({map:texture,transparent:true,depthTest:false});const sprite=new T.Sprite(material);sprite.position.set(x,y,z);sprite.scale.set(size*3.2,size,1);world.add(sprite);return sprite;
  }
  function shadow(x,z,r=.32){const m=disk(x,.115,z,r,'#132c24');m.scale.z=.68;return m;}
  function player(x,z,team,number,pose='ready'){
    shadow(x,z,.32);const g=new T.Group();g.position.set(x,.13,z);world.add(g);
    const color=team==='home'?state.homeColor:state.awayColor;
    box(-.1,.16,0,.13,.31,.15,'#dce2e8',g);box(.1,.16,0,.13,.31,.15,'#dce2e8',g);
    box(-.1,.025,.07,.17,.08,.25,'#152235',g);box(.1,.025,.07,.17,.08,.25,'#152235',g);
    const torso=mesh(new T.CylinderGeometry(.19,.16,.32,8),mat(color),0,.46,0,g);torso.rotation.z=pose==='pitch'?.12:0;
    sphere(0,.77,0,.185,'#bd8d68',g);
    const cap=mesh(new T.SphereGeometry(.2,10,6,0,Math.PI*2,0,Math.PI/2),mat(color),0,.81,0,g);
    box(0,.8,.16,.27,.035,.18,color,g);
    const arm=box(-.22,.43,.03,.11,.29,.12,color,g);arm.rotation.z=.2;
    const arm2=box(.23,pose==='pitch'?.67:.44,.025,.11,.29,.12,color,g);arm2.rotation.z=pose==='pitch'?-.8:-.2;
    sphere(-.25,.31,.07,.075,'#9a694b',g);
    if(pose==='bat'){const bat=box(.3,.85,0,.05,.66,.05,'#dec089',g);bat.rotation.z=-.45;}
    return g;
  }
  function turf(w,h,color1='#257e49',color2='#2d9356'){
    box(0,-.35,0,w+.34,.7,h+.34,'#102b28');box(0,-.03,0,w,.12,h,color1);
    for(let i=0;i<12;i++)box(-w/2+w*(i+.5)/12,.04,0,w/12,.015,h,i%2?color1:color2);
  }
  function stadium(w,h){
    const rows=3;
    for(let i=0;i<rows;i++){
      const offset=.25+i*.22,y=.18+i*.16,col=i%2?'#233c4b':'#1b3141';
      box(0,y,-h/2-offset,w+offset*2,.14,.18,col);
      box(-w/2-offset,y,0,.18,.14,h+offset*2,col);box(w/2+offset,y,0,.18,.14,h+offset*2,col);
    }
  }
  function baseball(){
    const radius=7.6,homeZ=4.7;
    const shape=new T.Shape();shape.moveTo(0,homeZ);shape.lineTo(-radius*Math.SQRT1_2,homeZ-radius*Math.SQRT1_2);shape.absarc(0,homeZ,radius,-Math.PI*.75,-Math.PI*.25,false);shape.lineTo(0,homeZ);
    const ground=new T.ExtrudeGeometry(shape,{depth:.48,bevelEnabled:false,curveSegments:40});const slab=mesh(ground,mat('#163d2d'));slab.rotation.x=Math.PI/2;slab.position.y=.0;
    const grassMaterial=mat('#238448');grassMaterial.side=T.DoubleSide;
    const grass=mesh(new T.ShapeGeometry(shape,40),grassMaterial);grass.rotation.x=Math.PI/2;grass.position.y=.015;
    // Narrow radial turf bands are clipped by the field's wedge, rather than decorating a rectangle.
    const stripeMaterial=mat('#299b54');stripeMaterial.side=T.DoubleSide;
    for(let r=1;r<7.5;r+=.64){const ring=mesh(new T.RingGeometry(r,r+.3,48,1,-Math.PI*.75,Math.PI*.5),stripeMaterial);ring.rotation.x=Math.PI/2;ring.position.set(0,.028,homeZ);}
    const infield=box(0,.04,2.04,3.72,.06,3.72,'#bc9365');infield.rotation.y=Math.PI/4;
    const grassDiamond=box(0,.087,2.04,2.62,.022,2.62,'#267c43');grassDiamond.rotation.y=Math.PI/4;
    disk(0,.105,2.02,.34,'#c5a277');disk(0,.06,homeZ,.49,'#c5a277');
    const bases=[[0,4.67],[2.6,2.04],[0,-.57],[-2.6,2.04]];
    line([...bases,bases[0]],'#f6eee1',.036,.125);
    bases.forEach(([x,z],i)=>{const b=box(x,.16,z,.22,.095,.22,i&&state.bases?.includes(i)?'#ffd85a':'#fffcf1');b.rotation.y=Math.PI/4;});
    line([[0,homeZ],[-5.42,-.75]],'#f6eee1',.05);line([[0,homeZ],[5.42,-.75]],'#f6eee1',.05);
    const wallPts=[];for(let i=0;i<=48;i++){const a=-Math.PI*.75+i*Math.PI*.5/48;wallPts.push([Math.cos(a)*radius,homeZ+Math.sin(a)*radius]);}
    line(wallPts,'#325e57',.22,.22);line(wallPts,'#c1b170',.045,.4);
    label('330',-4.55,.65,-1.15,'#e3deb5',.36);label('400',0,.5,-3.1,'#e3deb5',.36);label('330',4.6,.65,-1.05,'#e3deb5',.36);
    const defense=[[-3.8,-.8],[0,-2.35],[3.8,-.8],[-2.05,.85],[1.8,.85],[-2.8,2.02],[2.8,2.02],[0,2.0],[0,5.15]];
    defense.forEach(([x,z],i)=>player(x,z,'home',i+1,i===7?'pitch':'ready'));
    player(.45,4.7,'away',1,'bat');
    state.bases?.forEach(i=>{if(bases[i])player(bases[i][0]+.33,bases[i][1],'away',i);});
    label(state.awayAbbr||'AWAY',-3.1,.13,4.9,state.awayColor,.42);label(state.homeAbbr||'HOME',3.1,.13,4.9,state.homeColor,.42);
  }
  function soccer(){turf(13,8);stadium(13,8);rect(-6.25,-3.75,12.5,7.5);line([[0,-3.75],[0,3.75]]);arc(0,0,1.12);[-6.25,4.35].forEach(x=>rect(x,-1.8,1.9,3.6));[-6.25,5.55].forEach(x=>rect(x,-.9,.7,1.8));[-6.5,6.5].forEach(x=>{box(x,.4,0,.06,.8,2.1,'#e9f1ec');box(x,.8,0,.55,.06,2.1,'#e9f1ec');});[[-5.9,0],[-3.5,-2.1],[-3.5,2.1],[-1.4,0],[1.1,-1.9],[1.1,1.9]].forEach(([x,z],i)=>{player(x,z,'home',i);player(-x,-z,'away',i);});}
  function football(){turf(14,7.2);stadium(14,7.2);box(-6.45,.08,0,1.05,.025,7.1,state.awayColor);box(6.45,.08,0,1.05,.025,7.1,state.homeColor);rect(-6.9,-3.5,13.8,7);for(let i=0;i<=10;i++){const x=-5.9+i*1.18;line([[x,-3.5],[x,3.5]],'#d5e9ce',.027);if(i>0&&i<10){label(String(Math.min(i,10-i)*10),x,.15,-2.7,'#dce7d0',.24);}}for(let i=0;i<5;i++){player(-.8,-1.3+i*.65,'home',i);player(.1,-1.3+i*.65,'away',i);}player(-2.2,0,'home',6);player(2.8,0,'away',7);line([[-.6,-3.45],[-.6,3.45]],'#5bd9ff',.09,.15);}
  function basketball(){turf(12,6.8,'#c79356','#d8a667');stadium(12,6.8);rect(-5.75,-3.15,11.5,6.3,'#fff1d8');line([[0,-3.15],[0,3.15]],'#fff1d8');arc(0,0,.9,0,Math.PI*2,'#fff1d8');[-5.75,3.3].forEach(x=>rect(x,-1.05,2.45,2.1,'#fff1d8'));[-5.25,5.25].forEach(x=>{box(x,1.1,0,.08,2.2,.08,'#d1d7d8');box(x,2.2,0,.08,.8,1.2,'#e9f4f8');arc(x+(x<0?.3:-.3),0,.22,0,Math.PI*2,'#ff713d',1.95);});[[-3,-1.8],[-1.5,.1],[-3,1.8],[1,-2],[1,2]].forEach(([x,z],i)=>{player(x,z,'away',i);player(-x,-z,'home',i);});}
  function hockey(){turf(13,7,'#a9c9d9','#bfd9e3');stadium(13,7);rect(-6.2,-3.25,12.4,6.5,'#d9f0f1');line([[0,-3.3],[0,3.3]],'#d74a62',.09);[-2.5,2.5].forEach(x=>line([[x,-3.3],[x,3.3]],'#548abb',.12));arc(0,0,.95,0,Math.PI*2,'#d74a62');[-3.8,3.8].forEach(x=>[-1.8,1.8].forEach(z=>arc(x,z,.7,0,Math.PI*2,'#d74a62')));[[-5.8,0],[-3,1.8],[-3,-1.8],[-.9,.9],[-.9,-.9]].forEach(([x,z],i)=>{player(x,z,'home',i);player(-x,-z,'away',i);});}
  function tennis(){turf(12,7,'#215577','#285d81');rect(-5.5,-3,11,6);rect(-5.5,-2.2,11,4.4);rect(-2.7,-2.2,5.4,4.4);line([[-2.7,0],[2.7,0]]);box(0,.45,0,.04,.9,6.4,'#b0b9ba');player(-4.9,.7,'home',1);player(4.9,-.7,'away',1);}
  function oval(kind){turf(13,8);const ellipse=[];for(let i=0;i<=80;i++){const a=i*Math.PI/40;ellipse.push([Math.cos(a)*6.2,Math.sin(a)*3.7]);}line(ellipse);if(kind==='cricket'){box(0,.08,0,1.2,.04,4.3,'#c9b283');rect(-.8,-1.7,1.6,3.4);player(0,-1.5,'home',1,'pitch');player(0,1.4,'away',1,'bat');}else{arc(0,0,1);rect(-1.5,-1.5,3,3);[-3,0,3].forEach(x=>{player(x,-1.3,'home',1);player(x,1.3,'away',1);});}}
  function rugby(){turf(13,7);rect(-6,-3.2,12,6.4);[-4.7,-2.5,0,2.5,4.7].forEach(x=>line([[x,-3.2],[x,3.2]]));[-5.6,5.6].forEach(x=>{box(x,1.5,-.8,.06,3,.06,'#eeeadd');box(x,1.5,.8,.06,3,.06,'#eeeadd');box(x,1.1,0,.06,.06,1.65,'#eeeadd');});for(let i=0;i<5;i++){player(-1.8,-2+i,'home',i);player(1.8,-2+i,'away',i);}}
  function mma(){const slab=mesh(new T.CylinderGeometry(4.5,4.7,.55,8),mat('#233446'),0,-.15,0);disk(0,.14,0,3.6,'#3c5367');for(let i=0;i<8;i++){const a=(i+.5)*Math.PI/4,x=Math.cos(a)*4.2,z=Math.sin(a)*4.2;box(x,.65,z,.08,1.3,.08,'#617484');}player(-1,0,'home',1);player(1,0,'away',2);}
  function racing(){turf(14,8);const pts=[];for(let i=0;i<=90;i++){const a=i/90*Math.PI*2;pts.push([Math.cos(a)*5.6,Math.sin(a)*2.7]);}line(pts,'#38434b',1.25);line(pts,'#dce4da',.025,.13);for(let i=0;i<3;i++){const x=-3+i*2;box(x,.3,2.7,.7,.3,.36,i%2?state.homeColor:state.awayColor);sphere(x-.2,.18,2.9,.1,'#111c24');sphere(x+.2,.18,2.9,.1,'#111c24');}}
  function golf(){turf(12,8);const green=disk(1,.08,0,2.4,'#65aa56');green.scale.x=1.5;disk(1,.1,0,.13,'#102a25');box(1,1.0,0,.04,1.8,.04,'#e7e7cc');box(1.4,1.65,0,.8,.45,.025,state.homeColor);player(-2,1,'away',1);}
  function marker(p,i){
    let x,z;
    if(state.sport==='mlb'){
      if(Number.isFinite(p.hx)&&Number.isFinite(p.hy)){x=(p.hx-125)/125*5.4;z=4.65-(210-p.hy)/210*7.4;}
      else if(Number.isFinite(p.x)&&Number.isFinite(p.y)&&p.normalized){x=(p.x-.5)*12;z=(p.y-.8)*9+4.65;}
      else return;
    }else if(state.sport==='nfl'&&Number.isFinite(p.yardsToGo)){x=6-Number(p.yardsToGo)/100*12;z=0;}
    else if(Number.isFinite(p.x)&&Number.isFinite(p.y)&&p.normalized){x=(p.x-.5)*12;z=(p.y-.5)*7;}
    else return;
    if(!Number.isFinite(x)||!Number.isFinite(z)||Math.abs(x)>7||Math.abs(z)>6)return;
    const selected=i===state.plays.length-1,col=selected?'#ffdd58':(p.outcome==='out'?'#ff6b7e':p.outcome==='homerun'?'#ffc74b':'#90e7f1');
    sphere(x,.26,z,selected?.14:.09,col);if(selected){arc(x,z,.25,0,Math.PI*2,col,.16);if(state.sport==='mlb')line([[0,4.67],[x,z]],'#ead066',.025,.13);}
  }
  function clearWorld(){
    if(world)scene.remove(world);geometries.splice(0).forEach(g=>g.dispose());textures.splice(0).forEach(t=>t.dispose());
    if(world)world.traverse(o=>{if(o.isSprite)o.material.dispose();});mats.forEach(m=>m.dispose());mats.clear();world=new T.Group();scene.add(world);
  }
  function setup(){
    if(renderer)return true;if(!T)return false;
    try{
      renderer=new T.WebGLRenderer({antialias:true,alpha:false,powerPreference:'low-power',preserveDrawingBuffer:true});
      renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,1.25));renderer.setClearColor('#0c1520');renderer.outputColorSpace=T.SRGBColorSpace;
      renderer.domElement.dataset.testid='sports-3d-canvas';renderer.domElement.setAttribute('role','img');renderer.domElement.setAttribute('aria-label','Three-dimensional schematic sports field');document.body.appendChild(renderer.domElement);
      scene=new T.Scene();camera=new T.OrthographicCamera(-9,9,5,-5,.1,70);
      scene.add(new T.HemisphereLight('#e6f4ff','#263a22',2.0));const light=new T.DirectionalLight('#fff3d6',2.4);light.position.set(-8,15,10);scene.add(light);
      renderer.domElement.addEventListener('webglcontextlost',e=>{e.preventDefault();document.getElementById('fallback').classList.add('show');parent.postMessage({type:'v2-sports-scene-ready',success:false},location.origin);});
      return true;
    }catch(_){document.getElementById('fallback').classList.add('show');return false;}
  }
  function draw(){if(!renderer||disposed||document.hidden)return;const w=innerWidth,h=innerHeight;if(!w||!h)return;renderer.setSize(w,h,false);const aspect=w/h;
    const height=state.sport==='mlb'?10.7:10.1,width=Math.max(16.7,height*aspect);camera.left=-width/2;camera.right=width/2;camera.top=width/aspect/2;camera.bottom=-width/aspect/2;
    const centre=state.sport==='mlb'?1.25:0;camera.up.set(0,state.view==='top'?0:1,state.view==='top'?-1:0);camera.position.set(state.view==='top'?0:state.sport==='mlb'?0:8,state.view==='top'?25:16,state.view==='top'?centre:21);camera.lookAt(0,0,centre);camera.zoom=1;camera.updateProjectionMatrix();camera.updateMatrixWorld(true);
    const bounds=new T.Box3().setFromObject(world);let mx=0,my=0;
    for(const x of [bounds.min.x,bounds.max.x])for(const y of [bounds.min.y,bounds.max.y])for(const z of [bounds.min.z,bounds.max.z]){const p=new T.Vector3(x,y,z).project(camera);mx=Math.max(mx,Math.abs(p.x));my=Math.max(my,Math.abs(p.y));}
    if(mx>0&&my>0)camera.zoom=Math.max(.65,Math.min(1.9,.87/mx,.76/my));camera.updateProjectionMatrix();renderer.render(scene,camera);window.__v2SceneFrames=(window.__v2SceneFrames||0)+1;
  }
  function requestDraw(){cancelAnimationFrame(frame);frame=requestAnimationFrame(draw);}
  window.SportsScene={
    render(input){if(!setup())return false;disposed=false;state={sport:'mlb',homeColor:'#d64b64',awayColor:'#6ca1da',plays:[],bases:[],view:'angle',...input};
      for(const k of ['homeColor','awayColor'])if(!/^#[0-9a-f]{6}$/i.test(state[k]))state[k]=k==='homeColor'?'#d64b64':'#6ca1da';
      state.plays=Array.isArray(state.plays)?state.plays.slice(-60):[];state.bases=Array.isArray(state.bases)?state.bases.filter(n=>[1,2,3].includes(n)):[];
      clearWorld();({mlb:baseball,nfl:football,soccer,nba:basketball,nhl:hockey,tennis,cricket:()=>oval('cricket'),afl:()=>oval('afl'),rugby,mma,f1:racing,golf}[state.sport]||soccer)();
      state.plays.forEach(marker);document.getElementById('fallback').classList.remove('show');draw();return true;},
    setView(view){state.view=view==='top'?'top':'angle';requestDraw();},
    pause(){cancelAnimationFrame(frame);},
    resume(){requestDraw();},
    dispose(){disposed=true;cancelAnimationFrame(frame);if(world)clearWorld();renderer?.dispose();renderer?.forceContextLoss();renderer?.domElement.remove();renderer=null;}
  };
  window.addEventListener('resize',requestDraw);document.addEventListener('visibilitychange',()=>{if(!document.hidden)requestDraw();else cancelAnimationFrame(frame);});
})();