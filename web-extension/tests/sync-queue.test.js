import test from 'node:test';import assert from 'node:assert/strict';import {indexedDB} from 'fake-indexeddb';
globalThis.indexedDB=indexedDB;
const state={},handlers={};globalThis.chrome={runtime:{onMessage:{addListener:f=>handlers.message=f},onConnect:{addListener:f=>handlers.connect=f}},tabs:{onRemoved:{addListener:f=>handlers.removed=f}},alarms:{create(){},onAlarm:{addListener:f=>handlers.alarm=f}},storage:{local:{async get(keys){if(typeof keys==='string')return {[keys]:state[keys]};return Object.fromEntries(keys.map(k=>[k,state[k]]))},async set(v){Object.assign(state,v)}}}};
const db=await new Promise((resolve,reject)=>{const q=indexedDB.open('listening-diary-web-preview',2);q.onupgradeneeded=()=>{q.result.createObjectStore('visits',{keyPath:'id'});q.result.createObjectStore('state');q.result.createObjectStore('intervals',{keyPath:'id'})};q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error)});
await new Promise((resolve,reject)=>{let t=db.transaction(['visits','intervals'],'readwrite');t.objectStore('visits').put({id:'visit',song:{id:'track',title:'song',artist:'',album:'',duration:100},duration:100});t.objectStore('intervals').put({id:'part',visit:'visit',track:'track',start:1000,end:3000,ms:2000,zone:'UTC'});t.oncomplete=resolve;t.onerror=reject});db.close();
await import('../background.js');
const message=(msg,sender={})=>new Promise(resolve=>handlers.message(msg,sender,resolve));
const raw=crypto.getRandomValues(new Uint8Array(32));state.browserSyncHost='10.1.2.3';state.browserSyncKey=Buffer.from(raw).toString('base64url');
const pending=async()=>{const d=await new Promise(r=>{let q=indexedDB.open('listening-diary-web-preview',3);q.onsuccess=()=>r(q.result)});return new Promise(resolve=>{let t=d.transaction('pending','readonly'),q=t.objectStore('pending').getAll();t.oncomplete=()=>{d.close();resolve(q.result)}})};
test('backfill is retained on failure and cleared after authenticated acknowledgement',async()=>{
 globalThis.fetch=async()=>{throw Error('offline')};await message({type:'sync-now'});assert.equal((await pending()).length,1);assert.match(state.browserSyncStatus,/等待/);
 globalThis.fetch=async(url,opt)=>{const data=Buffer.from(opt.body,'base64'),key=await crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']),aad=new TextEncoder().encode('ListeningDiary/LAN/v1');const req=JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:data.subarray(0,12),additionalData:aad},key,data.subarray(12))));assert.equal(req.spans.length,1);const iv=crypto.getRandomValues(new Uint8Array(12)),body=new TextEncoder().encode(JSON.stringify({type:'browser-ack-v1',id:req.id,count:req.spans.length})),encrypted=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad},key,body));return {ok:true,text:async()=>Buffer.concat([iv,encrypted]).toString('base64')}};
 await message({type:'sync-now'});assert.equal((await pending()).length,0);assert.match(state.browserSyncStatus,/已同步/);
});
test('retries another learned private device when the original address is unreachable',async()=>{
 const now=Date.now(),song={id:'second',title:'new song',duration:100};const sender={tab:{id:7},url:'https://music.apple.com/'};
 await message({type:'sample',payload:{at:now,position:0,playing:true,duration:100,rate:1,zone:'UTC',song}},sender);await message({type:'sample',payload:{at:now+2000,position:2,playing:true,duration:100,rate:1,zone:'UTC',song}},sender);
 state.browserSyncHost='10.1.2.3';state.browserSyncHosts=['10.1.2.4'];const tried=[];
 globalThis.fetch=async(url,opt)=>{tried.push(url);if(url.includes('10.1.2.3'))throw Error('offline');const data=Buffer.from(opt.body,'base64'),key=await crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']),aad=new TextEncoder().encode('ListeningDiary/LAN/v1');const req=JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:data.subarray(0,12),additionalData:aad},key,data.subarray(12))));const iv=crypto.getRandomValues(new Uint8Array(12)),body=new TextEncoder().encode(JSON.stringify({type:'browser-ack-v1',id:req.id,count:req.spans.length,addresses:['10.1.2.4']})),encrypted=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad},key,body));return {ok:true,text:async()=>Buffer.concat([iv,encrypted]).toString('base64')}};
 await message({type:'sync-now'});assert.ok(tried.some(x=>x.includes('10.1.2.3')));assert.ok(tried.some(x=>x.includes('10.1.2.4')));assert.equal(state.browserSyncHost,'10.1.2.4');assert.equal((await pending()).length,0);
});
test('paired idle probe registers stable browser identity and device name without records',async()=>{
 let seen;globalThis.fetch=async(url,opt)=>{const data=Buffer.from(opt.body,'base64'),key=await crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']),aad=new TextEncoder().encode('ListeningDiary/LAN/v1');const req=JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:data.subarray(0,12),additionalData:aad},key,data.subarray(12))));seen=req;const iv=crypto.getRandomValues(new Uint8Array(12)),body=new TextEncoder().encode(JSON.stringify({type:'browser-ack-v1',id:req.id,count:0,deviceName:'手机日记'})),encrypted=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad},key,body));return {ok:true,text:async()=>Buffer.concat([iv,encrypted]).toString('base64')}};
 await message({type:'sync-now',probe:true});assert.match(seen.client.id,/^[a-f0-9]{32}$/);assert.equal(seen.client.name,'网页端 · Chrome');assert.equal(state.browserSyncDeviceName,'手机日记');assert.match(state.browserSyncStatus,/已连接/);assert.equal((await pending()).length,0);
});
test('open popup receives committed listening updates without rereading all intervals',async()=>{
 const updates=[];let disconnect;handlers.connect({name:'diary-live',postMessage:m=>updates.push(m),onDisconnect:{addListener:f=>disconnect=f}});
 const at=Date.now(),song={id:'live',title:'Live song',duration:100},sender={tab:{id:19},url:'https://music.apple.com/'};
 await message({type:'sample',payload:{at,position:0,playing:true,duration:100,song}},sender);
 await message({type:'sample',payload:{at:at+2000,position:2,playing:true,duration:100,song}},sender);
 assert.equal(updates.length,2);assert.equal(updates[1].record.ms,2000);
 const overview=await message({type:'overview'});assert.equal(overview.records.find(r=>r.song.id==='live').ms,2000);assert.equal('intervals' in overview,false);
 disconnect();await message({type:'sample',payload:{at:at+4000,position:4,playing:true,duration:100,song}},sender);assert.equal(updates.length,2);
});
