package com.tcrrry.diary;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.text.*;
public class DaySlicesTest {
    @Test public void splitsMidnight()throws Exception{TimeZone z=TimeZone.getTimeZone("Asia/Shanghai");SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");f.setTimeZone(z);long a=f.parse("2026-10-25 23:59:50").getTime();List<String>d=new ArrayList<>();List<Long>m=new ArrayList<>();DaySlices.split(a,a+20000,z,(day,zone,s,e)->{d.add(day);m.add(e-s);});assertEquals(Arrays.asList("2026-10-25","2026-10-26"),d);assertEquals(Arrays.asList(10000L,10000L),m);}
    @Test public void daylightSavingPreservesActualDuration()throws Exception{TimeZone z=TimeZone.getTimeZone("America/New_York");SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm");f.setTimeZone(z);long a=f.parse("2026-03-08 00:00").getTime(),b=f.parse("2026-03-09 00:00").getTime();List<Long>m=new ArrayList<>();DaySlices.split(a,b,z,(day,zone,s,e)->m.add(e-s));assertEquals(23*3600000L,(long)m.get(0));}
}
