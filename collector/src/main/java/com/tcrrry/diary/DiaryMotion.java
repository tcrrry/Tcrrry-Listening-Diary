package com.tcrrry.diary;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.View;
import java.util.function.Consumer;

/** Interruptible critically damped-ish spring, in the view's frame clock. No perpetual animator. */
final class DiaryMotion implements Runnable {
    private final View owner;private final Consumer<Float> update;
    float value,velocity,target;private long previous;private boolean scheduled,closed;
    DiaryMotion(View owner,float value,Consumer<Float> update){this.owner=owner;this.value=this.target=value;this.update=update;}
    void to(float next){if(closed)return;target=next;if(!ValueAnimator.areAnimatorsEnabled()){snap(next);return;}if(!scheduled){previous=SystemClock.uptimeMillis();scheduled=true;owner.postOnAnimation(this);}}
    void snap(float next){owner.removeCallbacks(this);scheduled=false;value=target=next;velocity=0;update.accept(value);}
    static float[] integrate(float x,float v,float target,float dt){float step=Math.min(.032f,Math.max(.001f,dt));for(int i=0;i<2;i++){v+=((target-x)*260-v*27)*step/2;x+=v*step/2;}return new float[]{x,v};}
    public void run(){if(closed)return;long now=SystemClock.uptimeMillis();float[] state=integrate(value,velocity,target,(now-previous)/1000f);previous=now;value=state[0];velocity=state[1];if(Math.abs(target-value)<.0008f&&Math.abs(velocity)<.008f){value=target;velocity=0;scheduled=false;}update.accept(value);if(scheduled)owner.postOnAnimation(this);}
    void close(){closed=true;scheduled=false;owner.removeCallbacks(this);}
}
