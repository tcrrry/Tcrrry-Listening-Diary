package com.tcrrry.diary;
import android.os.Handler;
import java.util.concurrent.atomic.AtomicBoolean;
/** A delayed native sample must not block the durable outbox. */
final class SyncCheckpoint {
    static Runnable bounded(Handler main,long waitMs,Runnable action){
        AtomicBoolean ran=new AtomicBoolean();
        Runnable once=new Runnable(){public void run(){if(!ran.compareAndSet(false,true))return;main.removeCallbacks(this);action.run();}};
        main.postDelayed(once,waitMs);
        return once;
    }
}
