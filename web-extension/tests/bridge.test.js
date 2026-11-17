import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import vm from 'node:vm';
test('page bridge emits playback data without tokens and samples in background',()=>{
 const out=[],subscriptions={},timers=[];
 const music={nowPlayingItem:{id:'123',attributes:{name:'Song',artistName:'Artist',albumName:'Album',durationInMillis:180000,playParams:{id:'123'}}},isPlaying:true,currentPlaybackTime:12,currentPlaybackDuration:180,musicUserToken:'secret',addEventListener(n,f){subscriptions[n]=f}};
 const window={MusicKit:{getInstance:()=>music},postMessage:(m,origin)=>out.push({m,origin}),addEventListener(){}};
 vm.runInNewContext(fs.readFileSync(new URL('../page.js',import.meta.url),'utf8'),{window,location:{origin:'https://music.apple.com'},setTimeout:()=>{},setInterval:(f)=>{timers.push(f);return 1},clearInterval(){},Date,Number,String});
 assert.equal(out[0].m.data.song.title,'Song');assert.equal(out[0].m.data.position,12);assert.equal(out[0].origin,'https://music.apple.com');assert.ok(!JSON.stringify(out).includes('secret'));
 music.currentPlaybackTime=14;timers[0]();assert.equal(out.at(-1).m.data.position,14);music.isPlaying=false;subscriptions.playbackStateDidChange();assert.equal(out.at(-1).m.data.playing,false);
});
test('content bridge forwards only matching origin and bounded metadata',()=>{
 let fn,forwarded=[];const window={addEventListener:(event,f)=>{fn=f}};const chrome={runtime:{sendMessage:x=>{forwarded.push(x);return Promise.resolve()}}};const location={origin:'https://music.apple.com'};
 vm.runInNewContext(fs.readFileSync(new URL('../content.js',import.meta.url),'utf8'),{window,chrome,location,Date,Number,String,Math});
 const event={source:window,origin:location.origin,data:{type:'TCRRRY_DIARY_SAMPLE_V1',data:{at:Date.now(),position:3,playing:true,song:{id:'1',title:'x'.repeat(1000)}}}};fn(event);assert.equal(forwarded.length,1);assert.equal(forwarded[0].payload.song.title.length,500);fn({...event,origin:'https://example.com'});assert.equal(forwarded.length,1);
});
