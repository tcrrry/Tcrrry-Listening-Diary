package com.tcrrry.diary;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

/** Report-owned blurred backdrop. Does not capture or mutate the player's Window/Theme. */
final class DiaryGlass extends Drawable {
    interface Source {Bitmap backdrop(); View root();}
    private final View owner;private final Source source;private final Paint paint=new Paint(3);private final float radius;
    private RuntimeShader shader;private Bitmap input;private int alpha=255;float press;
    // Independent rounded-rectangle distance-field lens. Inspired by Backdrop's lens pipeline,
    // without copying its Compose runtime, shader text, or host ABI.
    static final String AGSL="uniform shader content;uniform float2 size;uniform float2 offset;uniform float2 scale;uniform float radius;uniform float touch;"
        +"float sd(float2 p){float2 q=abs(p-size*.5)-(size*.5-radius);return length(max(q,0.))+min(max(q.x,q.y),0.)-radius;}"
        +"half4 main(float2 p){float d=sd(p);float2 n=normalize(float2(sd(p+float2(1,0))-sd(p-float2(1,0)),sd(p+float2(0,1))-sd(p-float2(0,1)))+float2(.001));"
        +"float rim=1.-smoothstep(0.,18.,-d);float2 q=(p+offset-n*rim*rim*(7.+touch*3.))*scale;"
        +"half4 col=content.eval(q);half hi=half(pow(rim,5.)*.24);col.rgb=mix(col.rgb,half3(.14,.13,.19),.30)+hi;col.a=1.;return col;}";
    DiaryGlass(View owner,Source source,float radius){this.owner=owner;this.source=source;this.radius=radius;}
    @Override public void draw(Canvas c){Rect b=getBounds();if(b.isEmpty())return;int[] a=new int[2],r=new int[2];owner.getLocationOnScreen(a);source.root().getLocationOnScreen(r);Bitmap bitmap=source.backdrop();float rw=Math.max(1,source.root().getWidth()),rh=Math.max(1,source.root().getHeight());boolean rendered=false;
        if(bitmap!=null&&!bitmap.isRecycled()){
            if(Build.VERSION.SDK_INT>=33&&c.isHardwareAccelerated())try{if(shader==null)shader=new RuntimeShader(AGSL);if(input!=bitmap){shader.setInputShader("content",new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));input=bitmap;}
                shader.setFloatUniform("size",(float)b.width(),(float)b.height());shader.setFloatUniform("offset",(float)(a[0]-r[0]+b.left),(float)(a[1]-r[1]+b.top));shader.setFloatUniform("scale",bitmap.getWidth()/rw,bitmap.getHeight()/rh);shader.setFloatUniform("radius",Math.min(radius,b.height()/2f));shader.setFloatUniform("touch",press);paint.setShader(shader);paint.setAlpha(alpha);c.save();c.translate(b.left,b.top);c.drawRoundRect(0,0,b.width(),b.height(),radius,radius,paint);c.restore();rendered=true;
            }catch(Throwable ignored){shader=null;}
            if(!rendered){BitmapShader fallback=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);Matrix matrix=new Matrix();matrix.setScale(rw/bitmap.getWidth(),rh/bitmap.getHeight());matrix.postTranslate(r[0]-a[0],r[1]-a[1]);fallback.setLocalMatrix(matrix);paint.setShader(fallback);paint.setAlpha(alpha);c.drawRoundRect(new RectF(b),radius,radius,paint);}
        }
        paint.setShader(null);paint.setColor(rendered?0x102c253d:0x963b344a);paint.setAlpha(Color.alpha(paint.getColor())*alpha/255);c.drawRoundRect(new RectF(b),radius,radius,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setShader(new LinearGradient(0,0,b.width(),b.height(),new int[]{0x90ffffff,0x12ffffff,0x48ffffff},null,Shader.TileMode.CLAMP));paint.setAlpha(alpha);c.drawRoundRect(new RectF(b.left+.8f,b.top+.8f,b.right-.8f,b.bottom-.8f),radius,radius,paint);paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setAlpha(255);
    }
    @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
