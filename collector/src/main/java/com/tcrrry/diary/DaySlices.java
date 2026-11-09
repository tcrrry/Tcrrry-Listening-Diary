package com.tcrrry.diary;
import java.text.SimpleDateFormat;
import java.util.*;
public final class DaySlices {
    public interface Part {void accept(String day,String zone,long start,long end);}
    public static void split(long start,long end,TimeZone zone,Part part){
        SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);fmt.setTimeZone(zone);
        while(start<end){Calendar cal=Calendar.getInstance(zone);cal.setTimeInMillis(start);cal.add(Calendar.DAY_OF_MONTH,1);cal.set(Calendar.HOUR_OF_DAY,0);cal.set(Calendar.MINUTE,0);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);long next=Math.min(end,cal.getTimeInMillis());if(next<=start)throw new IllegalArgumentException("Invalid day boundary");part.accept(fmt.format(new Date(start)),zone.getID(),start,next);start=next;}
    }
}
