function dayAt(ms,zone){const p=new Intl.DateTimeFormat('en-US',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(ms);const get=k=>p.find(x=>x.type===k)?.value;return `${get('year')}-${get('month')}-${get('day')}`}
export function diaryBackup(visits,intervals){
 const byVisit=new Map(visits.map(v=>[v.id,v]));const tracks=new Map(),spans=[];
 for(const part of intervals){const visit=byVisit.get(part.visit);if(!visit?.song?.id||!Number.isSafeInteger(part.start)||!Number.isSafeInteger(part.end)||part.end<=part.start)continue;
  const song=visit.song;tracks.set(song.id,{id:song.id,title:song.title||'',artist:song.artist||'',album:song.album||'',duration:Math.max(0,Math.round((visit.duration||song.duration||0)*1000))});
  let start=part.start,index=0;while(start<part.end){const day=dayAt(start,part.zone);let end=part.end;
   if(dayAt(end-1,part.zone)!==day){let lo=start+1,hi=end;while(lo<hi){const mid=Math.floor((lo+hi)/2);if(dayAt(mid,part.zone)===day)lo=mid+1;else hi=mid}end=lo}
   spans.push({remote_id:`web:${part.id}:${index++}`,visit:`web:${part.visit}`,track:song.id,day,zone:part.zone,start,end,ms:end-start,device:'',route_source:'',volume_pct:-1});start=end;
  }
 }
 return {schemaVersion:1,format:'listening-diary-app',sourceId:'web_preview',exportedAt:Date.now(),tracks:[...tracks.values()],spans,events:[],event_sources:[],import_batches:[],import_tracks:[],import_spans:[]};
}
