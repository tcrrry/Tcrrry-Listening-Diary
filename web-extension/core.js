export function step(previous, sample, makeId) {
  if (!sample || !Number.isFinite(sample.at) || !Number.isFinite(sample.position) || sample.position < 0) return {state:previous, record:null};
  const song = sample.song;
  if (!song || !song.id || !song.title) return {state:null, record:null};
  const valid = !!sample.playing && !sample.seeking && !sample.buffering;
  let s = previous, interval = null;
  const changed = !s || s.song.id !== song.id;
  const repeated = !changed && s.duration > 0 && s.position >= Math.max(0,s.duration-8) && sample.position <= 5 && sample.position < s.position-5 && s.playing && valid;
  if (changed || repeated) s = {id:makeId(), song, start:sample.at, end:sample.at, ms:0, position:sample.position, at:sample.at, duration:sample.duration||song.duration||0, playing:valid, rate:sample.rate||1};
  else {
    const elapsed=(sample.at-s.at)/1000, delta=sample.position-s.position;
    const rate=Math.max(0.25,Math.min(4,Number(s.rate)||1));
    // Credit only observed movement within elapsed real time; seeking and long gaps are not inferred.
    if(s.playing && valid && elapsed>0 && elapsed<=30 && delta>0 && delta<=elapsed*rate+1.5) {
      const credit=Math.floor(Math.min(elapsed,delta/rate)*1000);
      if (credit>0) {s.ms += credit; s.end=sample.at; interval={id:`${s.id}:${sample.at}`,visit:s.id,track:s.song.id,start:sample.at-credit,end:sample.at,ms:credit,zone:sample.zone||'UTC'};}
    }
    s={...s,song,position:sample.position,at:sample.at,duration:sample.duration||s.duration,playing:valid,rate:sample.rate||1};
  }
  return {state:s,interval,record:{...s,effective:s.ms>=Math.min(30000,s.duration>0?s.duration*500:30000)}};
}
