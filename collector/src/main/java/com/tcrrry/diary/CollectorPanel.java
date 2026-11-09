package com.tcrrry.diary;
import android.content.Context;import android.content.res.Resources;import android.view.*;import android.widget.*;import android.graphics.drawable.GradientDrawable;import android.os.*;import dev.amenhancer.plugin.api.PluginSettingsSession;

/** A small platform-only entrypoint. All report rendering belongs to the companion process. */
final class CollectorPanel implements PluginSettingsSession {
    interface Actions {String status();boolean enabled();void toggle();void open();void sync();void export();void close(CollectorPanel panel);}
    private final LinearLayout root;private final TextView state,toggle;private final Handler handler=new Handler(Looper.getMainLooper());private final Actions actions;private boolean closed;
    private final Runnable update=new Runnable(){public void run(){if(closed)return;state.setText(actions.status());toggle.setText(actions.enabled()?"暂停记录":"开启记录");handler.postDelayed(this,1500);}};
    CollectorPanel(Context caller,Actions actions){this.actions=actions;Resources.Theme theme=caller.getResources().newTheme();theme.applyStyle(android.R.style.Theme_Material_NoActionBar,true);Context context=new ContextThemeWrapper(caller,theme);root=new LinearLayout(context);root.setOrientation(1);root.setPadding(32,24,32,24);root.setBackgroundColor(0xff11131b);root.setLayoutParams(new ViewGroup.LayoutParams(-1,-2));
        label(context,"听歌日记 · 采集器",23);state=label(context,actions.status(),14);label(context,"复杂报告与动画已移至独立 App。这里持续保存实际播放记录，App 不必保持打开。",14);
        button(context,"打开听歌日记 App",actions::open);toggle=button(context,"",actions::toggle);button(context,"立即补传记录",actions::sync);button(context,"导出完整 JSON 备份",actions::export);
        label(context,"记录不会自动过期。更换插件前请导出备份；请勿清除 AM++ 数据或先卸载旧插件。",12);update.run();
    }
    private TextView label(Context c,String text,int size){TextView t=new TextView(c,null,0,0);t.setText(text);t.setTextSize(size);t.setTextColor(0xffe0d9ed);t.setPadding(12,16,12,16);root.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private TextView button(Context c,String text,Runnable action){TextView t=label(c,text,16);GradientDrawable bg=new GradientDrawable();bg.setColor(0xff292438);bg.setCornerRadius(20);t.setBackground(bg);LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)t.getLayoutParams();lp.topMargin=12;t.setLayoutParams(lp);t.setFocusable(true);t.setOnClickListener(v->action.run());return t;}
    public View getView(){return root;}
    public void close(){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);actions.close(this);}
}
