import {privateHost,sendBatch,pairGroup} from './sync.js';
import {diaryBackup} from './export.js';
const $=s=>document.querySelector(s),records=new Map(),states=new Map(),items=new Map();
let loading=true,queued=[],editing=false;
const port=chrome.runtime.connect({name:'diary-live'});
port.onMessage.addListener(message=>{if(message?.type!=='sample-update')return;if(loading)queued.push(message);else applySample(message)});
port.onDisconnect.addListener(()=>{if(chrome.runtime.lastError)$('#live').textContent='等待重新连接'});
const duration=ms=>{const n=Math.floor(Math.max(0,Number(ms)||0)/1000);return n>=3600?`${Math.floor(n/3600)} 小时 ${Math.floor(n%3600/60)} 分钟`:n>=60?`${Math.floor(n/60)} 分 ${n%60} 秒`:`${n} 秒`};
function artUrl(song){try{const u=new URL(song?.artwork||'');return u.protocol==='https:'?u.href:''}catch{return ''}}
function applySample(m){if(m.record){records.set(m.record.id,m.record);states.set(m.tabId,m.record)}else states.delete(m.tabId);render()}
function text(node,value){if(node.textContent!==value)node.textContent=value}
function render(){
 const rows=[...records.values()].sort((a,b)=>b.start-a.start),valid=rows.filter(r=>r.ms>0),effective=rows.filter(r=>r.effective);
 text($('#plays'),String(effective.length));text($('#minutes'),String(Math.floor(rows.reduce((n,r)=>n+(Number(r.ms)||0),0)/60000)));text($('#songs'),String(new Set(effective.map(r=>r.song?.id).filter(Boolean)).size));text($('#artists'),String(new Set(effective.map(r=>r.song?.artist?.trim()).filter(Boolean)).size));
 const active=[...states.values()].filter(s=>s&&Date.now()-(Number(s.at)||0)<12000).sort((a,b)=>b.at-a.at)[0];
 const live=$('#live');text(live,active?(active.playing?'记录中':'已暂停'):'等待播放');live.classList.toggle('active',!!active?.playing);
 text($('#now-title'),active?.song?.title||'下一段音乐记忆，正在路上');text($('#now-artist'),active?.song?.artist||'打开 Apple Music 网页开始播放');text($('#now-time'),`已听 ${duration(active?.ms||0)}`);
 const art=$('#art'),cover=$('#cover'),url=artUrl(active?.song);if(art.dataset.url!==url){art.dataset.url=url;if(url){art.src=url;art.hidden=false;cover.querySelector('span').hidden=true}else{art.removeAttribute('src');art.hidden=true;cover.querySelector('span').hidden=false}}art.onerror=()=>{art.hidden=true;cover.querySelector('span').hidden=false};
 const shown=valid.slice(0,20),ids=new Set(shown.map(r=>r.id));for(const [id,node] of items)if(!ids.has(id)){node.remove();items.delete(id)}
 for(const r of shown){let li=items.get(r.id);if(!li){li=document.createElement('li');const img=document.createElement('img');img.className='mini-cover';img.alt='';const box=document.createElement('div');box.className='track';const title=document.createElement('div');title.className='track-title';const sub=document.createElement('div');sub.className='track-sub';box.append(title,sub);const time=document.createElement('div');time.className='track-time';li.append(img,box,time);items.set(r.id,li)}
  const img=li.querySelector('img'),u=artUrl(r.song);if(img.dataset.url!==u){img.dataset.url=u;if(u)img.src=u;else img.removeAttribute('src')}text(li.querySelector('.track-title'),r.song?.title||'未知歌曲');text(li.querySelector('.track-sub'),`${r.song?.artist||'未知歌手'} · ${new Date(r.start).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})}${r.effective?'':' · 未达有效播放'}`);text(li.querySelector('.track-time'),duration(r.ms));$('#records').append(li);
 }
 $('#empty').hidden=shown.length>0;text($('#history-count'),valid.length?`最近 ${shown.length} 条`:'');
}
try{const data=await chrome.runtime.sendMessage({type:'overview'});if(data?.error)throw Error(data.error);for(const r of data?.records||[])records.set(r.id,r);for(const s of data?.states||[])states.set(`initial-${s.id}`,s);loading=false;for(const m of queued)applySample(m);queued=[];render()}catch(e){loading=false;text($('#empty'),'读取记录失败，请重新打开扩展。');$('#empty').hidden=false}
setInterval(()=>{let changed=false;for(const [id,s] of states)if(!s||Date.now()-(Number(s.at)||0)>=12000){states.delete(id);changed=true}if(changed)render()},5000);
function download(data,name){const blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'});const u=URL.createObjectURL(blob),a=document.createElement('a');a.href=u;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(u),1000)}
async function exportData(forDiary){try{const data=await chrome.runtime.sendMessage({type:'records'});if(data?.error)throw Error(data.error);const rows=(data?.records||[]).sort((a,b)=>b.start-a.start),intervals=data?.intervals||[];if(!forDiary){download({format:'listening-diary-web-preview',version:2,exportedAt:Date.now(),records:rows,intervals},'listening-diary-web-preview.json');return}const backup=diaryBackup(rows,intervals);if(!backup.spans.length){$('#notice').textContent='暂无可导入的分段记录，请播放一段时间后再试。';return}download(backup,'listening-diary-web-import.json');$('#notice').textContent=`已导出 ${backup.spans.length} 段；旧预览累计记录无法还原准确分段，未包含在内。`}catch(e){$('#notice').textContent='导出失败：'+e.message}}
$('#export').onclick=()=>exportData(false);$('#diary-export').onclick=()=>exportData(true);
const keys=['browserSyncHost','browserSyncHosts','browserSyncKey','browserSyncStatus','browserSyncDeviceName'];
const hostInput=$('#host'),pairForm=$('#pair-form'),pairedLabel=$('#paired-label'),status=$('#sync-status'),change=$('#change'),unpair=$('#unpair');
function renderPair(data){const paired=!!(data.browserSyncHost&&data.browserSyncKey);if(!editing)hostInput.value=data.browserSyncHost||'';pairForm.hidden=paired&&!editing;change.hidden=!paired||editing;unpair.hidden=!paired;pairedLabel.textContent=paired?`已配对 · ${data.browserSyncDeviceName||data.browserSyncHost}`:'尚未配对';status.textContent=data.browserSyncStatus||(paired?'等待同步':'配对后，打开手机日记即可自动同步。')}
const initial=await chrome.storage.local.get(keys);renderPair(initial);
if(initial.browserSyncHost&&initial.browserSyncKey)chrome.runtime.sendMessage({type:'sync-now',probe:true}).catch(()=>{});
chrome.storage.onChanged.addListener((changes,area)=>{if(area==='local'&&keys.some(k=>k in changes))chrome.storage.local.get(keys).then(renderPair)});
change.onclick=()=>{editing=true;pairForm.hidden=false;change.hidden=true};
$('#pair').onclick=async()=>{const host=hostInput.value.trim(),code=$('#key').value.trim();if(!privateHost(host)||!/^\d{6}$/.test(code)){status.textContent='请输入有效的局域网 IP 和六位配对码';return}try{const granted=await chrome.permissions.request({origins:['http://*/*']});if(!granted){status.textContent='需要允许访问日记设备的局域网地址';return}status.textContent='正在配对…';const paired=await pairGroup(host,code),identity=await chrome.runtime.sendMessage({type:'browser-identity'});if(!identity)throw Error('无法登记浏览器设备');const ack=await sendBatch(host,paired.key,[],[],fetch,identity);editing=false;await chrome.storage.local.set({browserSyncHost:host,browserSyncHosts:[...new Set([host,...paired.addresses])].filter(privateHost).slice(0,24),browserSyncKey:paired.key,browserSyncDeviceName:ack.deviceName||host,browserSyncStatus:'配对成功，正在同步…'});$('#key').value='';await chrome.runtime.sendMessage({type:'sync-now'})}catch(e){status.textContent='连接失败：检查 IP、配对码并保持配对码页面打开'}};
unpair.onclick=async()=>{editing=false;await chrome.storage.local.remove(keys);$('#key').value='';renderPair({});status.textContent='已解除配对，本机记录保留'};
