package com.tcrrry.diary;
import java.util.UUID;
/** A navigation request is not a completed skip until the queue occurrence changes. */
final class SkipTracker {
    static final long WINDOW_MS=8000;
    static final class Command {
        final String kind,from;final long queue,wall,elapsed,position,duration;
        Command(String kind,String from,long queue,long wall,long elapsed,long position,long duration){this.kind=kind;this.from=from;this.queue=queue;this.wall=wall;this.elapsed=elapsed;this.position=position;this.duration=duration;}
    }
    static final class Event {
        final String id=UUID.randomUUID().toString(),to;final Command command;
        Event(Command c,String to){command=c;this.to=to;}
    }
    private Command pending;
    void arm(Command c){pending=c;}
    void reset(){pending=null;}
    Event observe(String track,long queue,long position,long elapsed){
        Command c=pending;if(c==null)return null;
        if(elapsed<c.elapsed)return null;
        if(elapsed-c.elapsed>WINDOW_MS){pending=null;return null;}
        if(queue==c.queue){if("previous".equals(c.kind)&&position+1000<c.position)pending=null;return null;}
        pending=null;return track==null||track.isEmpty()?null:new Event(c,track);
    }
}
