package com.tcrrry.diary;
import org.json.*;
import java.util.*;

/** Order is presentation only: never rewrites the ledger or deduplicates its visits. */
final class DailyRows {
    static long effective(long ms,long duration){return ms>=Math.min(30000,duration>0?Math.max(1,duration/2):30000)?1:0;}
    static List<JSONObject> order(JSONArray source,String mode)throws JSONException{
        List<JSONObject> rows=new ArrayList<>();boolean grouped="count".equals(mode)||"duration".equals(mode);Map<String,JSONObject> songs=new LinkedHashMap<>();
        for(int i=0;i<source.length();i++){JSONObject raw=source.getJSONObject(i);long play=effective(raw.optLong("ms"),raw.optLong("duration"));JSONObject s=new JSONObject(raw.toString()).put("plays",play).put("occurrences",1).put("grouped",grouped);
            if(!grouped){rows.add(s);continue;}String key=s.optString("id");JSONObject g=songs.get(key);if(g==null){songs.put(key,s);continue;}g.put("ms",g.optLong("ms")+s.optLong("ms")).put("plays",g.optLong("plays")+play).put("occurrences",g.optInt("occurrences")+1).put("start",Math.min(g.optLong("start"),s.optLong("start"))).put("end",Math.max(g.optLong("end"),s.optLong("end")));
        }
        if(grouped)rows.addAll(songs.values());
        Comparator<JSONObject> time=Comparator.comparingLong(s->s.optLong("start"));
        if("latest".equals(mode))rows.sort(time.reversed());else if("count".equals(mode))rows.sort(Comparator.<JSONObject>comparingLong(s->s.optLong("plays")).reversed().thenComparing(Comparator.<JSONObject>comparingLong(s->s.optLong("ms")).reversed()).thenComparing(time));else if("duration".equals(mode))rows.sort(Comparator.<JSONObject>comparingLong(s->s.optLong("ms")).reversed().thenComparing(time));else rows.sort(time);
        return rows;
    }
}
