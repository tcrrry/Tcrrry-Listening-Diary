package com.tcrrry.diary;

import java.util.UUID;

/** Pure state machine. Elapsed time, never seek distance, is listening time. */
public final class ListeningClock {
    public static final long MAX_GAP_MS = 45000;
    public static final class Sample {
        public final String id,title,artist,album,device,routeSource;
        public final long wall,elapsed,position,duration;
        public final boolean playing;
        public final double speed; public final int volume;
        public Sample(String id,String title,String artist,String album,long wall,long elapsed,long position,long duration,boolean playing,double speed){
            this(id,title,artist,album,wall,elapsed,position,duration,playing,speed,"","");
        }
        public Sample(String id,String title,String artist,String album,long wall,long elapsed,long position,long duration,boolean playing,double speed,String device,String routeSource){
            this(id,title,artist,album,wall,elapsed,position,duration,playing,speed,device,routeSource,-1);
        }
        public Sample(String id,String title,String artist,String album,long wall,long elapsed,long position,long duration,boolean playing,double speed,String device,String routeSource,int volume){
            this.id=id;this.title=title;this.artist=artist;this.album=album;this.device=device;this.routeSource=routeSource;this.volume=volume;
            this.wall=wall;this.elapsed=elapsed;this.position=position;this.duration=duration;
            this.playing=playing;this.speed=speed;
        }
    }
    public static final class Chunk {
        public final Sample song;public final String visit,reason;public final long start,end,millis;
        Chunk(Sample song,String visit,long start,long millis,String reason){this.song=song;this.visit=visit;this.start=start;this.end=start+millis;this.millis=millis;this.reason=reason;}
    }
    public interface Sink {void save(Chunk chunk);}
    private final Sink sink;
    private Sample previous;
    private String visit;
    public ListeningClock(Sink sink){this.sink=sink;}
    public void reset(){previous=null;visit=null;}
    public void accept(Sample now,String reason){
        if(now==null){reset();return;}
        Sample old=previous;
        long gap=old==null?0:now.elapsed-old.elapsed;
        boolean changed=old==null||!old.id.equals(now.id);
        long remaining=old!=null&&old.duration>0&&old.speed>0?Math.max(0,(long)((old.duration-old.position)/old.speed)):Long.MAX_VALUE;
        boolean repeat=old!=null&&!changed&&old.playing&&now.playing&&old.duration>0&&old.speed>0
            &&gap>0&&gap<=MAX_GAP_MS&&remaining<=gap+1500
            &&old.position-now.position>3000
            &&Math.abs(now.position-(gap-remaining)*old.speed)<2000;
        if(old!=null&&old.playing&&visit!=null&&gap>0&&gap<=MAX_GAP_MS){
            long credit=gap;
            if(old.duration>0&&old.speed>0)credit=Math.min(credit,Math.max(0,(long)((old.duration-old.position)/old.speed)));
            // A controller that stays at the same position is not evidence of playback.
            if(!changed&&now.playing&&gap>=5000&&Math.abs(now.position-old.position)<200&&old.speed>0)credit=0;
            if(credit>0)sink.save(new Chunk(old,visit,old.wall,credit,reason));
        }
        if(changed||repeat||gap<0||gap>MAX_GAP_MS||visit==null||(!old.playing&&now.playing&&gap>1800000))
            visit=UUID.randomUUID().toString();
        if(repeat&&gap>remaining){long head=gap-remaining;sink.save(new Chunk(now,visit,old.wall+remaining,head,"repeat"));}
        previous=now;
    }
}
