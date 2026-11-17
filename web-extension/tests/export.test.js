import test from 'node:test';import assert from 'node:assert/strict';import {diaryBackup} from '../export.js';import {step} from '../core.js';
const song={id:'123',title:'Song',artist:'Artist',album:'Album',duration:180};
test('export retains precise intervals, splits midnight, and preserves stable IDs',()=>{
 const start=Date.parse('2026-11-15T15:59:59Z'),end=start+2000;
 const visits=[{id:'visit',song,duration:180,ms:2000,start,end}];const intervals=[{id:'visit:sample',visit:'visit',track:'123',start,end,ms:2000,zone:'Asia/Shanghai'}];
 const backup=diaryBackup(visits,intervals),again=diaryBackup(visits,intervals);
 assert.equal(backup.format,'listening-diary-app');assert.equal(backup.spans.length,2);assert.deepEqual(backup.spans.map(x=>x.day),['2026-11-15','2026-11-16']);assert.equal(backup.spans.reduce((n,s)=>n+s.ms,0),2000);assert.deepEqual(backup.spans.map(s=>s.remote_id),again.spans.map(s=>s.remote_id));assert.equal(backup.tracks[0].duration,180000);
});
test('sample intervals do not span pauses or jumps',()=>{let id=0;const a={at:10000,position:0,song,playing:true,duration:180,zone:'UTC'};let x=step(null,a,()=>String(++id));x=step(x.state,{...a,at:12000,position:2},()=>String(++id));assert.deepEqual([x.interval.start,x.interval.end,x.interval.ms],[10000,12000,2000]);x=step(x.state,{...a,at:14000,position:80},()=>String(++id));assert.equal(x.interval,null)});
