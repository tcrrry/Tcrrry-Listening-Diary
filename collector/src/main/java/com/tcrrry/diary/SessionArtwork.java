package com.tcrrry.diary;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.net.Uri;
import java.io.*;
import java.text.Normalizer;
import java.util.Locale;

/** Reuse only artwork that can be tied conservatively to the observed playback item. */
final class SessionArtwork {
    private static final int MAX_BYTES=8*1024*1024;
    private static String text(MediaMetadata m,String key){try{String s=m.getString(key);return s==null?"":s;}catch(Exception e){return "";}}
    private static String normal(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    static boolean sameTrack(MediaMetadata nativeItem,MediaMetadata session){
        if(nativeItem==null||session==null)return false;
        String a=text(nativeItem,MediaMetadata.METADATA_KEY_MEDIA_ID),b=text(session,MediaMetadata.METADATA_KEY_MEDIA_ID);
        String nt=normal(text(nativeItem,MediaMetadata.METADATA_KEY_TITLE)),st=normal(text(session,MediaMetadata.METADATA_KEY_TITLE));
        if(nt.isEmpty()||!nt.equals(st))return false;
        if(!a.isEmpty()&&!a.equals("0")&&a.equals(b))return true;
        for(String key:new String[]{MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_ALBUM}){
            String left=normal(text(nativeItem,key)),right=normal(text(session,key));
            if(left.isEmpty()||!left.equals(right))return false;
        }
        long x=nativeItem.getLong(MediaMetadata.METADATA_KEY_DURATION),y=session.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return x>0&&y>0&&Math.abs(x-y)<=2000;
    }
    static Uri contentArtUri(MediaMetadata m){
        if(m==null)return null;
        for(String key:new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI,MediaMetadata.METADATA_KEY_ART_URI,MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}){
            try{String raw=text(m,key);if(raw.length()>4096)continue;Uri uri=Uri.parse(raw);if("content".equalsIgnoreCase(uri.getScheme())&&uri.getAuthority()!=null&&!uri.getAuthority().isEmpty())return uri;}catch(Exception ignored){}
        }
        return null;
    }
    static Bitmap load(ContentResolver resolver,Uri uri)throws IOException{
        if(uri==null||!"content".equalsIgnoreCase(uri.getScheme()))return null;
        try(InputStream in=resolver.openInputStream(uri)){
            if(in==null)return null;ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;
            while((n=in.read(buf))!=-1){total+=n;if(total>MAX_BYTES)throw new IOException("Artwork too large");out.write(buf,0,n);}
            return decode(out.toByteArray());
        }
    }
    static Bitmap decode(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)return null;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>20000||bounds.outHeight>20000)return null;
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>1280)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
        return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    }
    private SessionArtwork(){}
}
