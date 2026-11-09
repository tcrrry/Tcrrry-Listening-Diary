package com.tcrrry.diary;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A foreground Diary request wakes this running collector, without launching or controlling the player. */
final class CollectorRequests implements AutoCloseable {
    static final String ACTION="com.tcrrry.listeningdiary.app.REQUEST_SYNC";
    static final String PERMISSION="com.tcrrry.listeningdiary.app.permission.REQUEST_SYNC";
    interface Action { void sync(Runnable finished); }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Runnable> pending=new HashSet<>();
    private boolean closed;
    private final BroadcastReceiver receiver;
    CollectorRequests(Context context,Action action){
        this.context=context;
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
            if(closed||intent==null||!ACTION.equals(intent.getAction()))return;
            PendingResult result=goAsync();AtomicBoolean complete=new AtomicBoolean();
            Runnable done=new Runnable(){public void run(){if(!complete.compareAndSet(false,true))return;main.post(()->{main.removeCallbacks(this);pending.remove(this);});if(result!=null)result.finish();}};
            pending.add(done);main.postDelayed(done,8000);
            try{action.sync(done);}catch(RuntimeException e){done.run();}
        }};
        IntentFilter filter=new IntentFilter(ACTION);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,PERMISSION,main,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver,filter,PERMISSION,main);
    }
    @Override public void close(){if(closed)return;closed=true;context.unregisterReceiver(receiver);for(Runnable done:new ArrayList<>(pending))done.run();pending.clear();main.removeCallbacksAndMessages(null);}
}
