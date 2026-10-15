/* Steprivo offline Android MVP: local-only activity, economy and test-redemption store.
   Native step-counter deltas are NOT server-verified and are not redeemable in production. */
(() => {
'use strict';
const KEY='steprivo.offline.v1';
const today=()=>new Date().toLocaleDateString('en-CA');
const fmt=n=>Math.floor(Number(n)||0).toLocaleString('en-IN');
const read=()=>{try{return JSON.parse(localStorage.getItem(KEY)||'null')}catch(e){return null}};
const initial=()=>({schema:1,createdAt:new Date().toISOString(),name:'Walker',goal:5000,level:1,theme:'light',onboarded:false, sensorLast:null,sensorDate:null,sensorTime:null,lifetime:0,days:{},credited:{},balance:0,ledger:[],orders:[],sessions:[],session:null,source:'Android step counter',lastUpdated:null});
let d=Object.assign(initial(),read()||{});
let ticking=null;
const store=()=>{d.lastUpdated=new Date().toISOString();localStorage.setItem(KEY,JSON.stringify(d))};
const setText=(sel,value,root=document)=>{const el=root.querySelector(sel);if(el)el.textContent=value};
const show=(el,v)=>{if(el)el.style.display=v?'':'none'};
const node=(sel,root=document)=>root.querySelector(sel);
const all=(sel,root=document)=>[...root.querySelectorAll(sel)];
const getSteps=(date=today())=>Number(d.days[date]||0);
const isNative=!!window.SteprivoNative;
const nativeCall=(m,...args)=>{try{return window.SteprivoNative&&window.SteprivoNative[m](...args)}catch(e){return null}};
function addEntry(type,amount,desc,status='local_test'){
  const entry={id:'EV-'+Date.now()+'-'+Math.random().toString(36).slice(2,7),type,amount,desc,status,at:new Date().toISOString()};
  d.ledger.unshift(entry);d.ledger=d.ledger.slice(0,1000);return entry;
}
function creditFromSteps(){
 const key=today(),earned=Math.min(25,Math.floor(getSteps()/1000)),old=Number(d.credited[key]||0),difference=Math.max(0,earned-old);
 if(difference){d.credited[key]=earned;d.balance+=difference;addEntry('walk',difference,fmt(getSteps())+' recorded local steps');}
}
function currentLevel(){
 const thresholds=[5000,8000,10000,12500,15000],days=Array.from({length:7},(_,i)=>{let x=new Date();x.setDate(x.getDate()-i);return getSteps(x.toLocaleDateString('en-CA'))});
 const next=thresholds[Math.min(4,d.level)];if(d.level<5&&days.filter(n=>n>=next).length>=4)d.level++;
 return d.level;
}
const levelNames=['Starter','Stepper','Strider','Trekker','Summit'];
function milestone(){
 const steps=getSteps(),goal=d.goal||5000;return {steps,goal,remain:Math.max(0,goal-steps),pct:Math.min(1,steps/goal)};
}
function updateHome(){
 const h=node('.screen[data-screen="home"]');if(!h)return;
 const {steps,goal,remain,pct}=milestone();
 setText('.hg-name',d.name+' ',h);
 const hn=node('.hg-name',h);if(hn){let pill=document.createElement('span');pill.className='lvl-pill';pill.style.marginLeft='2px';pill.innerHTML='<span class="dot-l"></span>Level '+d.level+' · '+levelNames[d.level-1];hn.appendChild(pill)}
 setText('.hero-top .chip:first-child','TODAY · '+new Date().toLocaleDateString('en-IN',{weekday:'short',day:'numeric',month:'short'}).toUpperCase(),h);
 setText('.ring-center .big',fmt(steps),h);setText('.ring-center .unit','of '+fmt(goal)+' steps',h);
 setText('.ring-center .delta',remain?fmt(remain)+' steps remaining':'Daily goal achieved!',h);
 const ring=node('.ring .val[data-ring]',h);if(ring){ring.dataset.ring=String(pct);const C=2*Math.PI*(+ring.getAttribute('r'));ring.style.strokeDasharray=C;ring.style.strokeDashoffset=C*(1-pct)}
 const stats=all('.hero-stats .hstat b',h);if(stats[0])stats[0].textContent=(steps*.00072).toFixed(2)+' km';if(stats[1])stats[1].textContent=fmt(Math.round(steps/100))+' min est.';if(stats[2])stats[2].textContent='+'+fmt(d.credited[today()]||0);
 setText('.cc-bal',fmt(d.balance)+' Rivo coins',h);
 setText('.cc-sub','LOCAL TEST BALANCE · NOT SERVER-VERIFIED',h);
 setText('.cm-row span',fmt(d.credited[today()]||0)+' of 25 local daily cap',h);
 const cap=node('.cap-meter .bar i',h);if(cap)cap.style.width=Math.min(100,(d.credited[today()]||0)*4)+'%';
 const nc=node('.next-card p',h);if(nc)nc.innerHTML=remain?'<b>'+fmt(remain)+' steps to your '+fmt(goal)+' goal.</b> Keep moving at your own pace.':'<b>Goal complete!</b> Great work today — rest and recovery count too.';
 const teaser=all('.pmini',h);teaser.forEach(p=>{const pid=p.dataset.pid;const prod=PRODUCTS[pid];if(!prod)return;const prog=node('.bar i',p);if(prog)prog.style.width=Math.min(100,100*d.balance/prod.price)+'%';const lab=node('.pm-prog span',p);if(lab)lab.textContent=d.balance>=prod.price?'Test redemption available':fmt(d.balance)+' / '+fmt(prod.price)});
 const challenges=all('.chal-card',h);if(challenges[0]){const b=node('.bar i',challenges[0]);if(b)b.style.width=Math.min(100,steps/8000*100)+'%';setText('.cf-prog span',fmt(steps)+' / 8,000 recorded steps',challenges[0])}
 paintWeek(node('.screen[data-screen="home"] .wbars'));
}
function paintWeek(el){if(!el)return; const bars=all('.wbar',el);const mon=new Date();let dow=(mon.getDay()+6)%7;mon.setDate(mon.getDate()-dow);bars.forEach((b,i)=>{const x=new Date(mon);x.setDate(mon.getDate()+i);const k=x.toLocaleDateString('en-CA'),n=getSteps(k);const fill=node('i',b);if(fill)fill.style.height=(n?Math.max(8,Math.min(100,n/Math.max(d.goal,1)*100)):6)+'%';b.classList.toggle('hit',n>=d.goal);b.classList.toggle('today',k===today());});}
function updateProgress(){const s=node('.screen[data-screen="progress"]');if(!s)return;paintWeek(node('.wbars',s));const nodes=all('.lvl-node',s);nodes.forEach((el,i)=>{el.classList.remove('done','current','lvl-locked');el.classList.add(i<d.level-1?'done':i===d.level-1?'current':'lvl-locked')});setText('.section-h small','Advance by recording 4 qualifying days in the past 7',s);setText('.card .card-title .chip',fmt(Object.values(d.days).filter(x=>x>=d.goal).length)+' goal days',s);const weeks=all('.card b',s);if(weeks.length>0){const end=new Date();const sum=Array.from({length:7},(_,i)=>{const x=new Date(end);x.setDate(end.getDate()-i);return getSteps(x.toLocaleDateString('en-CA'))}).reduce((a,b)=>a+b,0);const e=node('.card b[style*="16px"]',s);if(e)e.textContent=fmt(sum)} }
function updateWallet(){
 const w=node('.screen[data-screen="wallet"]');if(!w)return;
 const pending=0,redeemed=d.orders.reduce((a,x)=>a+x.coins,0);
 setText('.wh-bal',fmt(d.balance),w);
 setText('.wh-sub','LOCAL TEST COINS · Not usable for real products',w);
 const cells=all('.wh-cell b',w);if(cells[0])cells[0].textContent=fmt(d.credited[today()]||0)+' / 25';if(cells[1])cells[1].textContent='0';if(cells[2])cells[2].textContent='0';if(cells[3])cells[3].textContent=fmt(redeemed);
 const target=node('.screen[data-screen="wallet"] .card[style*="padding:4px"]');
 if(target){target.innerHTML=d.ledger.length?d.ledger.slice(0,8).map(entryRow).join(''):'<div class="empty-state"><b>No coin activity yet</b><p>Walk with your phone to start building a local test balance.</p></div>'}
}
function entryRow(e){const positive=e.amount>=0;const time=new Date(e.at).toLocaleString('en-IN',{day:'numeric',month:'short',hour:'numeric',minute:'2-digit'});return '<div class="tx-item"><div class="tx-ic" style="background:var(--mint-tint);color:var(--mint-ink)">◈</div><div class="tx-main"><b>'+esc(e.type==='walk'?'Walking coins':e.type==='redeem'?'Test redemption':'Adjustment')+'</b><span>'+esc(e.desc)+' · '+time+'</span></div><div class="tx-amt '+(positive?'amt-plus':'amt-minus')+'">'+(positive?'+':'')+fmtSigned(e.amount)+'<span class="st st-pend">LOCAL TEST</span></div></div>'}
const fmtSigned=x=>x<0?'−'+fmt(Math.abs(x)):fmt(x);
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
function updateHistory(){const h=node('.screen[data-screen="history"]');if(!h)return;const blocks=all('.card',h);if(blocks[0])blocks[0].innerHTML=d.ledger.length?d.ledger.map(entryRow).join(''):'<div class="empty-state"><b>No transactions yet</b><p>Your walking and test redemptions appear here.</p></div>';blocks.slice(1).forEach(b=>show(b,false));all('[style*="letter-spacing:1.2px"]',h).forEach(e=>show(e,false));}
function updateCatalogue(){const s=node('.screen[data-screen="catalogue"]');if(!s)return;setText('.app-head button.chip',fmt(d.balance),s);all('.prod-card',s).forEach(el=>{const id=el.dataset.pid,p=PRODUCTS[id];if(!p)return;const bar=node('.bar i',el);if(bar)bar.style.width=Math.min(100,(d.balance/p.price)*100)+'%';const detail=node('.pc-prog span',el);if(detail)detail.textContent=d.balance>=p.price?'Test redeem available':fmt(d.balance)+' / '+fmt(p.price)});}
function updateProfile(){const p=node('.screen[data-screen="profile"]');if(!p)return;setText('.prof-hero h2',d.name,p);setText('.prof-hero .p-lvl','Level '+d.level+' · '+levelNames[d.level-1],p);setText('.prof-hero .p-av',d.name[0]?.toUpperCase()||'W',p);setText('.prof-hero [style*="margin-top:8px"]','Offline local profile · '+new Date(d.createdAt).toLocaleDateString('en-IN'),p);const vals=all('.pstat b',p);if(vals[0])vals[0].textContent=fmt(d.lifetime);if(vals[1])vals[1].textContent=fmt(d.ledger.filter(x=>x.amount>0).reduce((a,e)=>a+e.amount,0));if(vals[2])vals[2].textContent=fmt(d.sessions.length);const ends=all('.set-row .sr-end',p);if(ends[0])ends[0].textContent=d.orders.length+' tests';if(ends[1])ends[1].textContent=fmt(d.balance);}
function updateOrders(){const s=node('.screen[data-screen="orders"]');if(!s)return;setText('.app-head .ah-sub',fmt(d.orders.length)+' local test orders',s);const sample=all('.order-card',s);const parent=sample[0]?.parentElement;if(!parent)return;sample.forEach(x=>x.remove());const title=node('.section-h',s);if(title)title.textContent='Test redemptions · local only';const old=all('.section-h',s);old.slice(1).forEach(el=>el.remove());if(!d.orders.length){parent.insertAdjacentHTML('beforeend','<div class="card" style="margin:12px 20px">No test orders yet. You can test the redemption journey once you have enough locally recorded coins.</div>');return;}parent.insertAdjacentHTML('beforeend',d.orders.map(o=>'<div class="order-card"><div class="oc-head"><div class="oc-img" style="background:var(--gold-tint)">◈</div><div style="flex:1"><b>'+esc(o.name)+'</b><div class="oc-id">'+esc(o.id)+' · '+new Date(o.createdAt).toLocaleDateString('en-IN')+'</div></div><span class="chip gold">TEST ONLY</span></div><p style="font-size:12px;color:var(--text-2)">-'+fmt(o.coins)+' local coins · No order placed, no shipping or real inventory reserved.</p></div>').join(''));}
function updateChallenges(){const c=node('.screen[data-screen="challenges"]');if(!c)return;const cards=all('.chal-card',c);if(cards[0]){setText('.cf-prog span',fmt(getSteps())+' / 8,000',cards[0]);const bar=node('.bar i',cards[0]);if(bar)bar.style.width=Math.min(100,getSteps()/8000*100)+'%'}let oldNote=node('#localChallengesNotice');if(!oldNote){oldNote=document.createElement('div');oldNote.id='localChallengesNotice';oldNote.className='card';oldNote.style.margin='8px 20px';c.insertBefore(oldNote,c.children[1])}oldNote.innerHTML='<b>Local tracking mode</b><p style="font-size:12px;color:var(--text-2);margin-top:6px">Challenge bonuses are visual previews for now. Real rewards need server verification and approved economics.</p>';}
function updateSettings(){const s=node('.screen[data-screen="settings"]');if(!s)return;const groups=all('.set-group',s);if(groups[1]){const rows=all('.set-row',groups[1]);if(rows[0])setText('.sr-end',fmt(d.goal),rows[0]);if(rows[2])setText('.sr-end','Session only',rows[2]);if(rows[3]){setText('.sr-main span','Not configured · future phase',rows[3]);setText('.sr-end','Later',rows[3])}}
}
function updateLeaderboard(){const s=node('.screen[data-screen="leaderboard"]');if(!s)return;let notice=node('#soloLeaderboard',s);if(!notice){notice=document.createElement('div');notice.id='soloLeaderboard';notice.className='card';notice.style.margin='20px';s.appendChild(notice)}notice.innerHTML='<b>Personal leaderboard · Offline</b><p style="margin-top:8px;font-size:13px;color:var(--text-2)">Your last 7 days: '+fmt(Object.entries(d.days).slice(-7).reduce((a,kv)=>a+kv[1],0))+' steps. Community rankings become available after we connect Supabase.</p>';all('.lb-row,.lb-podium',s).forEach(e=>show(e,false));}
function repaint(){currentLevel();updateHome();updateProgress();updateWallet();updateHistory();updateCatalogue();updateProfile();updateChallenges();updateSettings();updateLeaderboard();}
// Disable the original marketing demo count-up and preserve actual sensor values.
animateHome=()=>updateHome();
// Keep the original routing and cards, but replace values every time.
const originalGo=go;go=function(name){originalGo(name);repaint();if(name==='orders')updateOrders();};
// Restore local visual preferences across launches.
const originalSetTheme=setTheme;setTheme=function(theme){d.theme=theme;store();originalSetTheme(theme)};originalSetTheme(d.theme);
// Convert sign-in demo to a truthful offline profile. No fake Google/email auth.
const sign=node('.screen[data-screen="signin"]');if(sign){
 setText('.onb-copy h2','Start your local profile',sign);setText('.onb-copy p','No account or internet required. Your data stays on this phone.',sign);
 const authBtns=all('button',sign);if(authBtns[0]){authBtns[0].innerHTML='Continue offline';authBtns[0].dataset.go='permissions';authBtns[0].addEventListener('click',()=>{const n=node('#steprivoName');d.name=(n?.value||'Walker').trim().slice(0,30)||'Walker';store()});}
 if(authBtns[1])show(authBtns[1],false);if(authBtns[2])show(authBtns[2],false);
 const or=all('span',sign).find(x=>x.textContent==='OR');if(or)show(or.parentElement,false);
 let inp=document.createElement('input');inp.id='steprivoName';inp.maxLength=30;inp.value=d.name==='Walker'?'':d.name;inp.placeholder='Your first name';inp.autocomplete='given-name';inp.style.cssText='display:block;width:100%;padding:15px 16px;border:1px solid var(--line-strong);border-radius:14px;background:var(--surface);color:var(--text);font:inherit;font-size:15px;margin-bottom:12px';const area=node('.pad',sign);area?.insertBefore(inp,area.firstChild);
 const legal=node('.legal',sign);if(legal)legal.textContent='All activity stays on this device. Google and email sign-in will arrive with Supabase.';
}
// Remove the original prototype claims about server checks and 'verified' production assets.
const labels=[['.screen[data-screen="onboard"] .legal','OFFLINE BUILD · No account needed'],['.screen[data-screen="permissions"] .ah-title','Enable local walking'],['.screen[data-screen="home"] .hero-cta .btn','Start a recorded walk'],['.screen[data-screen="wallet"] .wallet-disc','Rivo coins are LOCAL TEST POINTS in this build. They are not cash, cannot be transferred, and cannot claim real products.'],['.screen[data-screen="catalogue"] .ah-sub','Preview products · local test only'],['.screen[data-screen="orders"] .ah-sub','Local test orders'],['.screen[data-screen="session"] .session-top b','Recorded Walk'],['.screen[data-screen="session"] #sessMode','SENSOR TRACKING · OFFLINE'],['.screen[data-screen="signin"] .legal','Data remains on this phone. No cloud sync yet.']];labels.forEach(([sel,str])=>setText(sel,str));
const hwWarning=node('#gpsWarn');if(hwWarning){hwWarning.style.display='';hwWarning.textContent='Offline build: sensor steps are unverified. Map route is optional; GPS data is stored locally.';}
// Replace the illustrative route with sensor/location status, never fake a GPS trace.
const map=node('.session-map');if(map){map.innerHTML='<div style="display:flex;flex-direction:column;gap:12px;align-items:center;justify-content:center;height:100%;padding:20px;text-align:center"><span style="font-size:22px">⌖</span><b id="gpsLabel" style="font-size:13px">Route tracking is optional</b><span style="font-size:11px;color:var(--text-2)" id="gpsDetail">Only during outdoor sessions, if location access is allowed.</span></div>';}
// Record a genuine walk: steps come exclusively from native step sensor samples.
startSession=function(){if(d.session){toast('Finish your existing walk first');go('session');return;}const mode=node('#walkSheet .goal-opt.on')?.dataset.mode||'indoor';const dur=node('#durChips .chip.on')?.textContent.trim()||'30 min';d.session={id:'W-'+Date.now(),mode,duration:dur,startedAt:Date.now(),lastTickAt:Date.now(),elapsed:0,steps:0,paused:false,distGPS:0,lastLoc:null,route:[]};store();go('session');startTick();if(mode==='outdoor')nativeCall('startGps');paintRealSession();};
function startTick(){if(ticking)clearInterval(ticking);ticking=setInterval(()=>{if(!d.session)return;if(!d.session.paused){d.session.elapsed++;if(d.session.elapsed%5===0)store()}paintRealSession()},1000)};
function paintRealSession(){const x=d.session;if(!x)return;const t=x.elapsed,mins=Math.floor(t/60),seconds=String(t%60).padStart(2,'0');setText('#sessTime',String(mins).padStart(2,'0')+':'+seconds);setText('#sessSteps',fmt(x.steps));const km=x.distGPS>0.05?x.distGPS:x.steps*.00072;const dist=node('#sessDist');if(dist)dist.innerHTML=km.toFixed(2)+' <small>km</small>';const pace=node('#sessPace');if(pace)pace.innerHTML=km>.03?Math.floor(t/km/60)+"'"+String(Math.round(t/km%60)).padStart(2,'0')+'" <small>/km</small>':'— <small>/km</small>';setText('#sessCoins','+'+fmt(Math.floor(x.steps/1000))+' test');const goal=Number.parseInt(x.duration)||30;const c=node('#sessRing'),circ=2*Math.PI*84;if(c){c.style.strokeDasharray=circ;c.style.strokeDashoffset=circ*(1-Math.min(1,t/(goal*60)))}setText('#sessMode',x.mode.toUpperCase()+' · LOCAL SENSOR DATA');const label=node('#gpsLabel');if(label)label.textContent=x.mode==='indoor'?'Indoor session · no GPS':x.route.length?'GPS samples saved · '+x.route.length:'Waiting for GPS (optional)';}
// Original pause listener uses the demo sim state (null). A separate real-session handler handles it.
node('#pauseBtn')?.addEventListener('click',()=>{if(!d.session)return;d.session.paused=!d.session.paused;store();toast(d.session.paused?'Session paused':'Session resumed')});
finishSession=function(){if(!d.session){go('home');return;}const x=d.session;nativeCall('stopGps');const km=x.distGPS>.05?x.distGPS:x.steps*.00072;d.sessions.unshift({...x,finishedAt:new Date().toISOString(),distance:km});d.sessions=d.sessions.slice(0,500);d.session=null;if(ticking)clearInterval(ticking);store();const s=node('.screen[data-screen="summary"]');if(s){setText('.sum-hero h2','Nice walk, '+d.name+'!',s);setText('.sum-hero p','Your walking session is saved on this phone.',s);const cells=all('.sum-cell b',s);if(cells[0])cells[0].textContent=fmt(x.steps);if(cells[1])cells[1].textContent=km.toFixed(2)+' km';if(cells[2])cells[2].textContent=Math.floor(x.elapsed/60)+':'+String(x.elapsed%60).padStart(2,'0');if(cells[3])cells[3].textContent=km>.02?(x.elapsed/60/km).toFixed(1)+' min/km':'—';setText('.coin-earn b','Local walking points: '+fmt(Math.floor(x.steps/1000)),s);setText('.coin-earn p','Recorded locally from your phone sensor. Step totals are not independently verified, so rewards are test-only.',s);}go('summary');};
window.SteprivoDevice={
 onSensorStep(total){const n=Math.floor(Number(total));if(!Number.isFinite(n)||n<0)return;const day=today(),time=Date.now();if(d.sensorLast==null||d.sensorDate!==day||n<d.sensorLast){d.sensorLast=n;d.sensorDate=day;d.sensorTime=time;store();return;}let delta=n-d.sensorLast;if(delta<=0)return;const gap=Math.max(1,(time-(d.sensorTime||time))/1000);d.sensorLast=n;d.sensorDate=day;d.sensorTime=time; if(delta>Math.max(100,12*gap)){store();return;}d.days[day]=(d.days[day]||0)+delta;d.lifetime+=delta;if(d.session&&!d.session.paused){d.session.steps+=delta;paintRealSession()}creditFromSteps();store();repaint();},
 onGps(lat,lon,accuracy){const x=d.session;if(!x||x.mode!=='outdoor'||x.paused||accuracy>65)return;const p={lat:+lat,lon:+lon,at:Date.now()},q=x.lastLoc;if(q){const rad=Math.PI/180,a=Math.sin((p.lat-q.lat)*rad/2)**2+Math.cos(q.lat*rad)*Math.cos(p.lat*rad)*Math.sin((p.lon-q.lon)*rad/2)**2;const m=12742*Math.asin(Math.min(1,Math.sqrt(a)));if(m>.005&&m<.15)x.distGPS+=m;}x.lastLoc=p;x.route.push(p);if(x.route.length>500)x.route.shift();paintRealSession();},
 onSensorUnavailable(){toast('No step-counter sensor found. Activity recording may be unavailable on this phone.');},
 onPermissionDenied(){toast('Activity permission is off. Enable it in Android settings to count steps.');}
};
// Store a selected goal and mark onboarding complete.
node('.screen[data-screen="goal"] button[data-go="home"]')?.addEventListener('click',()=>{d.goal=S.goal;d.onboarded=true;store();nativeCall('requestSteps')});
// Actual Android permission request; not the dummy prototype permission dialog.
node('.screen[data-screen="permissions"] button.btn-primary')?.addEventListener('click',()=>nativeCall('requestSteps'));
// Coins and testing redemptions: app never fulfils a physical order offline.
const realConfirm=node('#confirmRedeem');if(realConfirm){realConfirm.textContent='Confirm LOCAL TEST redemption';realConfirm.addEventListener('click',e=>{e.stopImmediatePropagation();e.preventDefault();const prod=PRODUCTS[currentPid];if(!prod||prod.price>d.balance){toast('Not enough local coins');return;}d.balance-=prod.price;const id='TEST-'+Date.now();d.orders.unshift({id,pid:currentPid,name:prod.name,coins:prod.price,createdAt:new Date().toISOString(),state:'test_only'});addEntry('redeem',-prod.price,'TEST ONLY: '+prod.name);store();closeSheets();go('success');const s=node('.screen[data-screen="success"]');if(s){setText('h2','Test redemption saved',s);setText('p','This is a local test order. No real inventory was reserved or dispatched.',s);setText('.order-id',id,s)}toast('Local test order stored on your phone')},true)}
const origRenderProduct=renderProduct;renderProduct=function(pid){S.have=d.balance;origRenderProduct(pid);const s=node('.screen[data-screen="product"]');setText('.pd-cta .need','Test redemption only · no physical fulfilment',s);setText('#pdEta','Illustrative pricing · not a real offer',s)};
// In the profile, make functional manual export and local reset options.
const settings=node('.screen[data-screen="settings"]');if(settings){const rows=all('.set-row',settings);rows.forEach(row=>{const t=row.textContent||'';if(t.includes('Export my data')){row.removeAttribute('data-toast');row.addEventListener('click',()=>{const json=JSON.stringify(d,null,2);nativeCall('exportData',json);if(!isNative){const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([json],{type:'application/json'}));a.download='steprivo-local-data.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),2000)}})}if(t.includes('Delete account')){row.removeAttribute('data-toast');row.addEventListener('click',()=>{if(confirm('Delete ALL local Steprivo data from this phone? This cannot be undone.')){localStorage.removeItem(KEY);location.reload()}})}if(t.includes('Daily goal')){row.removeAttribute('data-toast');row.addEventListener('click',()=>{const choice=prompt('Your daily step goal (1000 to 25000):',d.goal);if(choice!==null){const n=Number(choice);if(n>=1000&&n<=25000){d.goal=Math.round(n);S.goal=d.goal;store();repaint()}else toast('Choose a goal from 1,000 to 25,000')}})}})}
// Remove misleading Google sign-in/Health Connect claims elsewhere in the static prototype.
setText('.screen[data-screen="profile"] p[style*="10.5px"]','Steprivo · Local beta 0.1 · No Supabase connection');
setText('.screen[data-screen="help"] #acc-f1','This offline beta adds 1 LOCAL TEST Rivo coin per 1,000 sensor steps, up to 25 a day. Coins and reward prices are provisional. Real validation will be added with Supabase.');
setText('.screen[data-screen="help"] #acc-f2','Local steps cannot be independently verified. This release is for testing phone sensor tracking and the UX. Real earnable coins require server-side checks.');
setText('.screen[data-screen="help"] #acc-f4','There is no actual fulfilment in this offline beta. All local catalogue redemptions are explicitly simulations.');
const intro=node('.screen[data-screen="onboard"] .onb-art');if(intro)intro.innerHTML='<img src="emblem.svg" alt="Steprivo emblem" style="max-width:90%;max-height:90%;object-fit:contain">';
const signinIcon=node('.screen[data-screen="signin"] .onb-art');if(signinIcon)signinIcon.innerHTML='<img src="emblem.svg" alt="Steprivo" style="max-width:90%;max-height:90%;object-fit:contain">';
const logoSlot=node('.screen[data-screen="wallet"] .wh-bal svg');if(logoSlot){const img=document.createElement('img');img.src='coin.svg';img.style.cssText='width:37px;height:37px;object-fit:contain';logoSlot.replaceWith(img)};
const homeCoin=node('.coin-card .cc-coin');if(homeCoin)homeCoin.innerHTML='<img src="coin.svg" alt="Rivo coin" style="width:46px;height:46px;object-fit:contain">';
const warning=document.createElement('div');warning.className='offline-notice';warning.textContent='LOCAL BETA · NO CLOUD SYNC · TEST COINS ONLY';document.querySelector('#app')?.appendChild(warning);
const style=document.createElement('style');style.textContent='.offline-notice{position:absolute;top:0;right:0;left:0;font-size:9px;letter-spacing:.7px;font-weight:800;color:#fff;background:#0A1628;text-align:center;padding:5px;z-index:50}.native-app #statusbar,.native-app .notch{display:none!important}.native-app .screen{padding-top:27px}.native-app #tabbar{height:78px}.native-app .sheet{padding-bottom:36px}';document.head.appendChild(style);if(isNative)document.documentElement.classList.add('native-app');
// Final truth-in-UI polish: never show design-prototype data as real account activity.
SLIDES[0]=['Welcome to Steprivo','Walk with your phone and see your real daily steps. Track at your own pace — no smartwatch required.'];
SLIDES[1]=['Build a local Rivo balance','For this offline test, 1,000 locally recorded sensor steps add 1 test Rivo coin. Real reward verification will arrive with Supabase.'];
SLIDES[2]=['Explore future rewards','Browse physical-product concepts and try test redemptions. This build cannot place or deliver real orders.'];
paintOnb();
setText('.screen[data-screen="permissions"] .perm-card p','Uses the Android step-counter sensor. The app reads available sensor totals when opened. Some walking may not be recoverable across reboots or midnight.');
const permCards=all('.screen[data-screen="permissions"] .perm-card');
if(permCards[0])setText('p','Android activity permission allows the sensor to record steps while the app is in use and helps recover same-day sensor changes on reopening.',permCards[0]);
const hc=node('.screen[data-screen="permissions"] .card.tight');if(hc){setText('p','Health Connect integration is planned for the next phase. This version reads the built-in Android step counter.',hc);setText('.chip','Later',hc)}
const prevUpdateHome=updateHome;
updateHome=function(){prevUpdateHome();
 const home=node('.screen[data-screen="home"]');
 let streak=0;let day=new Date();if(getSteps()<d.goal)day.setDate(day.getDate()-1);
 for(let i=0;i<365;i++){if(getSteps(day.toLocaleDateString('en-CA'))<d.goal)break;streak++;day.setDate(day.getDate()-1)}
 setText('.hero-top .chip:last-child','Streak '+streak,home);
};
const prevUpdateProfile=updateProfile;
updateProfile=function(){prevUpdateProfile();const p=node('.screen[data-screen="profile"]');const labels=all('.pstat span',p);if(labels[2])labels[2].textContent='WALKS';};
const prevUpdateProgress=updateProgress;
updateProgress=function(){prevUpdateProgress();const p=node('.screen[data-screen="progress"]');const items=all('.card .row-item',p);let best=Math.max(0,...Object.values(d.days).map(Number));let lifetime=d.lifetime;
 if(items[0]){setText('.ri-title','Recorded sessions',items[0]);setText('.ri-sub','Saved only on this phone',items[0]);setText('.ri-end',fmt(d.sessions.length),items[0])}
 if(items[1]){setText('.ri-title','Best walking day',items[1]);setText('.ri-sub','Locally recorded sensor steps',items[1]);setText('.ri-end',fmt(best),items[1])}
 if(items[2]){setText('.ri-title','Lifetime local steps',items[2]);setText('.ri-sub','Since you began using Steprivo',items[2]);setText('.ri-end',fmt(lifetime),items[2])}
};
const prevRenderProduct=renderProduct;
renderProduct=function(pid){prevRenderProduct(pid);const p=node('.screen[data-screen="product"]');
 const eligibility=all('.elig-check span',p);
 if(eligibility[0])eligibility[0].textContent='Local test profile (no sign-in verification)';
 if(eligibility[1])eligibility[1].textContent='Coin balance is simulated from local steps';
 if(eligibility[2])eligibility[2].textContent='Real inventory is NOT connected';
 if(eligibility[3])eligibility[3].textContent='Any redemption here creates a test record only';
 setText('#acc-terms','No shipping or physical redemption is possible in the local beta. Test redemptions deduct only the local test balance.',p);
};
const prevUpdateCatalogue=updateCatalogue;
updateCatalogue=function(){prevUpdateCatalogue();const c=node('.screen[data-screen="catalogue"]');setText('.section-h small','Concept products only · test prices · no real inventory',c);const featured=node('.card[style*="margin:14px"]',c);if(featured)setText('[style*="font-size:11.5px"]','Illustrative goal · not a live offer',featured)};
const prevUpdateOrders=updateOrders;
updateOrders=function(){prevUpdateOrders();};
const prevFinishSession=finishSession;
finishSession=function(){prevFinishSession();const s=node('.screen[data-screen="summary"]');
 const where=all('.card',s);if(where[0]){const title=node('.card-title',where[0]);if(title)title.textContent='Locally stored session';const content=node('.list',where[0]);if(content)content.innerHTML='<div class="row-item"><div class="ri-main"><div class="ri-title">Walking data saved</div><div class="ri-sub">This session is stored on this phone. No cloud verification is active.</div></div></div>'}
};
const prevRepaint=repaint;
repaint=function(){prevRepaint();updateHome();updateProgress();updateProfile();updateCatalogue();};
const success=node('.screen[data-screen="success"]');if(success){
 const card=node('.card',success);if(card){setText('.card-title','Local test only',card);const list=node('.tl',card);if(list)list.innerHTML='<div class="tl-item done"><div class="tl-dot"></div><div><b>Test record saved</b><span>Only your local test coin balance changed.</span></div></div><div class="tl-item"><div class="tl-dot"></div><div><b>No real order</b><span>Nothing will be reserved, packed, shipped, or delivered.</span></div></div>'}
}
repaint();if(d.onboarded){go(d.session?'session':'home');if(d.session){startTick();paintRealSession();}}else go('onboard');
if(isNative)nativeCall('requestSteps');
})();