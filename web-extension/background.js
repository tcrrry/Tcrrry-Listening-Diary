import {step} from './core.js';
import {diaryBackup} from './export.js';
import {sendBatch,privateHost} from './sync.js';
import {browserIdentity} from './identity.js';
let dbPromise;
function db(){return dbPromise ||= new Promise((resolve,reject)=>{
 const q=indexedDB.open('listening-diary-web-preview',3);
 q.onupgradeneeded=()=>{const d=q.result;if(!d.objectStoreNames.contains('visits'))d.createObjectStore('visits',{keyPath:'id'});if(!d.objectStoreNames.contains('state'))d.createObjectStore('state');if(!d.objectStoreNames.contains('intervals'))d.createObjectStore('intervals',{keyPath:'id'});if(!d.objectStoreNames.contains('pending')){const pending=d.createObjectStore('pending',{keyPath:'id'});const cursor=q.transaction.objectStore('intervals').openCursor();cursor.onsuccess=()=>{const row=cursor.result;if(row){pending.put(row.value);row.continue()}}}};
 q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);
});}
let chain=Promise.resolve();
const views=new Set();
chrome.runtime.onConnect?.addListener(port=>{if(port.name!=='diary-live')return;views.add(port);port.onDisconnect.addListener(()=>views.delete(port))});
function sample(tabId,payload){
 chain=chain.catch(()=>{}).then(async()=>{const d=await db();return new Promise((resolve,reject)=>{
  let update=null;
  const tx=d.transaction(['visits','state','intervals','pending'],'readwrite'),state=tx.objectStore('state'),visits=tx.objectStore('visits'),intervals=tx.objectStore('intervals');
  const q=state.get(String(tabId));q.onsuccess=()=>{
   const previous=q.result||null;const result=step(previous,payload,()=>crypto.randomUUID());
   if(result.interval){let part=result.interval;const open=previous?.openInterval;
    if(open&&open.visit===part.visit&&open.zone===part.zone&&open.end===part.start){part={...open,end:part.end,ms:part.end-open.start}}
    intervals.put(part);tx.objectStore('pending').put(part);result.state.openInterval=part;
   }else if(result.state)result.state.openInterval=null;
   if(result.state)state.put(result.state,String(tabId));else state.delete(String(tabId));
   if(result.record)visits.put(result.record);update={tabId,record:result.record,at:payload.at};
  };
  tx.oncomplete=()=>{for(const port of views)try{port.postMessage({type:'sample-update',...update})}catch(_){views.delete(port)}resolve();scheduleSync()};tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);
 })});return chain;
}

let syncing=false,nextAttempt=0;
async function setStatus(status){await chrome.storage.local.set({browserSyncStatus:status})}
function scheduleSync(){if(Date.now()<nextAttempt)return;nextAttempt=Date.now()+30000;syncPending().catch(()=>{})}
async function syncPending(probe=false){
 if(syncing)return;syncing=true;try{
  const conf=await chrome.storage.local.get(['browserSyncHost','browserSyncHosts','browserSyncKey']);if(!conf.browserSyncHost||!conf.browserSyncKey)return;
  const d=await db();let sent=0;const identity=await browserIdentity();
  for(let rounds=0;rounds<20;rounds++){
   const batch=await new Promise((resolve,reject)=>{const t=d.transaction(['pending','visits'],'readonly'),p=t.objectStore('pending').getAll(null,64),visits=[];p.onsuccess=()=>{for(const id of new Set(p.result.map(x=>x.visit))){const q=t.objectStore('visits').get(id);q.onsuccess=()=>{if(q.result)visits.push(q.result)}}};t.oncomplete=()=>resolve({parts:p.result,visits});t.onerror=()=>reject(t.error)});
   if(!batch.parts.length)break;
   if(batch.parts.some(part=>!batch.visits.some(v=>v.id===part.visit)))throw Error('待同步记录缺少歌曲信息');const data=diaryBackup(batch.visits,batch.parts);if(data.spans.length<batch.parts.length||data.spans.length>128)throw Error('待同步时间片无效');
   let ack=null,used='';const hosts=[...new Set([conf.browserSyncHost,...(Array.isArray(conf.browserSyncHosts)?conf.browserSyncHosts:[])])].filter(privateHost).slice(0,24);
   for(const host of hosts){try{ack=await sendBatch(host,conf.browserSyncKey,data.tracks,data.spans,fetch,identity);used=host;break}catch(_){}}
   if(!ack)throw Error('设备暂不可达');const learned=Array.isArray(ack.addresses)?ack.addresses.filter(privateHost):[];const merged=[...new Set([used,...learned,...hosts])].slice(0,24);await chrome.storage.local.set({browserSyncHost:used,browserSyncHosts:merged,browserSyncDeviceName:typeof ack.deviceName==='string'&&ack.deviceName.trim()?ack.deviceName.trim().slice(0,48):used});conf.browserSyncHost=used;conf.browserSyncHosts=merged;
   await new Promise((resolve,reject)=>{const t=d.transaction('pending','readwrite'),store=t.objectStore('pending');for(const part of batch.parts){const q=store.get(part.id);q.onsuccess=()=>{if(q.result&&q.result.start===part.start&&q.result.end===part.end&&q.result.visit===part.visit)store.delete(part.id)}}t.oncomplete=resolve;t.onerror=()=>reject(t.error)});
   sent+=batch.parts.length;
  }
  if(!sent&&probe){let ack=null,used='';const hosts=[...new Set([conf.browserSyncHost,...(Array.isArray(conf.browserSyncHosts)?conf.browserSyncHosts:[])])].filter(privateHost).slice(0,24);for(const host of hosts){try{ack=await sendBatch(host,conf.browserSyncKey,[],[],fetch,identity);used=host;break}catch(_){}}if(!ack)throw Error('设备暂不可达');const learned=Array.isArray(ack.addresses)?ack.addresses.filter(privateHost):[];await chrome.storage.local.set({browserSyncHost:used,browserSyncHosts:[...new Set([used,...learned,...hosts])].slice(0,24),browserSyncDeviceName:typeof ack.deviceName==='string'&&ack.deviceName.trim()?ack.deviceName.trim().slice(0,48):used});}
  await setStatus(sent?`已同步 ${sent} 段 · ${new Date().toLocaleTimeString()}`:probe?'已连接 · 暂无待同步记录':'暂无待同步记录');
 }catch(e){await setStatus('等待日记在线后重试');}finally{syncing=false}
}
chrome.alarms.create('diary-sync',{periodInMinutes:1});chrome.alarms.onAlarm.addListener(a=>{if(a.name==='diary-sync')syncPending().catch(()=>{})});

chrome.runtime.onMessage.addListener((msg,sender,reply)=>{
 if(msg?.type==='browser-identity'&&!sender.tab){browserIdentity().then(reply,()=>reply(null));return true}
 if(msg?.type==='sync-now'&&!sender.tab){syncPending(msg.probe===true).then(()=>reply({ok:true}),()=>reply({ok:false}));return true}

 if(msg?.type==='sample'&&Number.isInteger(sender.tab?.id)&&sender.url?.startsWith('https://music.apple.com/')){sample(sender.tab.id,msg.payload).then(()=>reply({ok:true}),()=>reply({ok:false}));return true}
 if(msg?.type==='overview'&&!sender.tab){db().then(d=>{const tx=d.transaction(['visits','state','pending'],'readonly'),v=tx.objectStore('visits').getAll(),s=tx.objectStore('state').getAll(),p=tx.objectStore('pending').count();tx.oncomplete=()=>reply({records:v.result,states:s.result,pending:p.result});tx.onerror=()=>reply({error:'读取失败'})}).catch(()=>reply({error:'读取失败'}));return true}
 if(msg?.type==='records'&&!sender.tab){db().then(d=>{const tx=d.transaction(['visits','intervals'],'readonly'),q=tx.objectStore('visits').getAll(),p=tx.objectStore('intervals').getAll();tx.oncomplete=()=>reply({records:q.result,intervals:p.result});tx.onerror=()=>reply({error:'读取失败'})}).catch(()=>reply({error:'读取失败'}));return true}
});
chrome.tabs.onRemoved.addListener(id=>{chain=chain.catch(()=>{}).then(async()=>{const d=await db();const tx=d.transaction('state','readwrite');tx.objectStore('state').delete(String(id));return new Promise((ok,bad)=>{tx.oncomplete=ok;tx.onerror=()=>bad(tx.error)})})});
