(()=>{
 if(window.__listeningDiaryPreview)return;window.__listeningDiaryPreview=true;
 let music=null,timer=null,last='';const handlers=[];
 const read=(key,fallback)=>music?.[key]??music?.player?.[key]??fallback;
 function sample(){try{
  const item=read('nowPlayingItem',null),a=item?.attributes||{},p=a.playParams||{};
  const id=String(p.catalogId||p.id||item?.id||'');
  let position=Number(read('currentPlaybackTime',0));let el=null;
  try{const q=music?.services?.mediaItemPlayback?._currentPlayer;el=q?._targetElement;const offset=Number(q?._buffer?.currentTimestampOffset??0);if(el&&Number.isFinite(el.currentTime)&&Number.isFinite(offset))position=Math.max(0,el.currentTime-offset)}catch(_){}
  const data={at:Date.now(),zone:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC',position:Number.isFinite(position)?position:0,playing:!!read('isPlaying',false)&&(!el||(!el.paused&&!el.ended)),seeking:!!el?.seeking,buffering:!!el&&(el.readyState<3||!!el.error),rate:Number(el?.playbackRate)||1,duration:Number(read('currentPlaybackDuration',(Number(a.durationInMillis)||0)/1000))||0,song:id&&a.name?{id,title:String(a.name),artist:String(a.artistName||''),album:String(a.albumName||''),duration:(Number(a.durationInMillis)||0)/1000,artwork:String(a.artwork?.url||'').replaceAll('{w}','200').replaceAll('{h}','200')}:null};
  window.postMessage({type:'TCRRRY_DIARY_SAMPLE_V1',data},location.origin);
 }catch(_){}}
 function connect(){try{music=window.MusicKit?.getInstance()}catch(_){}if(!music){timer=setTimeout(connect,1000);return}
  for(const name of ['nowPlayingItemDidChange','playbackStateDidChange','playbackDurationDidChange'])try{const f=()=>sample();music.addEventListener(name,f);handlers.push([name,f])}catch(_){}
  sample();timer=setInterval(sample,2000);
 }
 window.addEventListener('pagehide',()=>{sample();clearInterval(timer);for(const [n,f] of handlers)try{music?.removeEventListener(n,f)}catch(_){}},{once:true});connect();
})();
