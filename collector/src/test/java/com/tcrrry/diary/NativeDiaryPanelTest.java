package com.tcrrry.diary;
import android.content.Context;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class NativeDiaryPanelTest {
    NativeDiaryPanel panel;JSONObject data;boolean toggled;int exports;String today;
    private static TextView find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return(TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    @Before public void setup()throws Exception{
        today=java.time.LocalDate.now().toString();
        data=new JSONObject().put("today",today).put("selected",today).put("zone","Asia/Shanghai").put("enabled",true).put("status","正在记录").put("nowTitle","Test song").put("nowArtist","Test artist").put("summary",new JSONObject().put("ms",45000).put("tracks",1).put("artists",1)).put("plays",1).put("songs",new JSONArray()).put("artists",new JSONArray()).put("timeline",new JSONArray()).put("recent",new JSONArray()).put("heat",new JSONArray());
        panel=new NativeDiaryPanel(RuntimeEnvironment.getApplication(),new NativeDiaryPanel.Actions(){
            public void report(String period,String day,Consumer<JSONObject> ready,Consumer<String> error){try{data.put("selected",day);ready.accept(data);}catch(Exception e){throw new RuntimeException(e);}}
            public void enabled(boolean on){toggled=!on;}
            public void exportData(){exports++;}
            public void clearData(Runnable done){}
            public void artwork(String id,Consumer<android.graphics.Bitmap> ready){}
        });
    }
    @After public void close(){if(panel!=null)panel.close();}
    private void layout(){View root=panel.getView();root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1800);}
    @Test public void overviewConstructsAndLaysOutWithoutWebView(){layout();assertNotNull(find(panel.getView(),"听歌日记"));assertNotNull(find(panel.getView(),"Test song"));assertFalse(containsWebView(panel.getView()));}
    private boolean containsWebView(View root){if(root.getClass().getName().contains("WebView"))return true;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(containsWebView(((ViewGroup)root).getChildAt(i)))return true;return false;}
    @Test public void timelineEmptyStateWorks(){find(panel.getView(),"时间线").performClick();layout();assertNotNull(find(panel.getView(),"这一天，等待音乐落笔"));}
    @Test public void rankingEmptyStateWorks(){find(panel.getView(),"常听").performClick();layout();assertNotNull(find(panel.getView(),"偏爱，值得慢慢积累"));}
    @Test public void settingsAndExportWork(){find(panel.getView(),"设置").performClick();layout();assertNotNull(find(panel.getView(),"本地听歌记录：已开启  ●"));find(panel.getView(),"导出完整 JSON 备份 ↗").performClick();assertEquals(1,exports);}
    @Test public void dayNavigationUsesRealCalendar(){find(panel.getView(),"‹").performClick();assertEquals(java.time.LocalDate.parse(today).minusDays(1).toString(),data.optString("selected"));find(panel.getView(),"›").performClick();assertEquals(today,data.optString("selected"));}
    @Test public void repeatedCloseIsSafe(){panel.close();panel.close();}
    @Test public void populatedTimelineAndRankingsRender()throws Exception{
        JSONObject song=new JSONObject().put("id","track").put("title","Long song title / 测试歌曲").put("artist","Artist").put("ms",45000).put("start",1792880000000L).put("end",1792880045000L).put("zone","Asia/Shanghai");
        data.put("timeline",new JSONArray().put(song)).put("songs",new JSONArray().put(song)).put("artists",new JSONArray().put(new JSONObject().put("artist","Artist").put("tracks",1).put("ms",45000)));
        find(panel.getView(),"时间线").performClick();layout();assertNotNull(find(panel.getView(),"Long song title / 测试歌曲"));
        find(panel.getView(),"常听").performClick();layout();assertNotNull(find(panel.getView(),"1  Artist"));assertFalse(containsWebView(panel.getView()));
    }
}
