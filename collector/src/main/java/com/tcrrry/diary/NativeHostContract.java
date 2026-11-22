package com.tcrrry.diary;

import android.os.Handler;
import java.lang.reflect.*;
import java.util.*;

/** Bounded structural profiles. No APK scanning, host mutation, or guessed class names. */
final class NativeHostContract {
    final Class<?> service,controller,queue,listener;
    final Field handler,manager,player,activeController;
    final Method convert,toItem,isPlaying,addListener,removeListener;
    final List<Method> lifecycle;
    final Method next,previous,select;
    final String converterName;
    final boolean explicitVersion;
    private NativeHostContract(ClassLoader loader,String converterName,boolean explicitVersion)throws ReflectiveOperationException {
        this.converterName=converterName;this.explicitVersion=explicitVersion;
        service=loader.loadClass("com.apple.android.music.player.MediaPlaybackService");
        controller=loader.loadClass("com.apple.android.music.playback.controller.MediaPlayerController");
        queue=loader.loadClass("com.apple.android.music.playback.model.PlayerQueueItem");
        listener=loader.loadClass("com.apple.android.music.playback.controller.MediaPlayerController$Listener");
        require(listener.isInterface(),"listener is not an interface");
        boolean callback=false;
        for(Method m:listener.getMethods()){
            require(m.getReturnType()==void.class,"listener return contract changed");
            if(m.getName().equals("onCurrentItemChanged"))callback=true;
        }
        require(callback,"item callback missing");
        handler=instanceField(service,"x");require(Handler.class.isAssignableFrom(handler.getType()),"owner handler changed");
        manager=instanceField(service,"Q");player=instanceField(service,"M");
        activeController=instanceField(manager.getType(),"h");
        require(controller.isAssignableFrom(activeController.getType()),"controller field changed");
        isPlaying=instanceMethod(player.getType(),"isPlaying",boolean.class);
        addListener=instanceMethod(controller,"addListener",void.class,listener);
        removeListener=instanceMethod(controller,"removeListener",void.class,listener);
        instanceMethod(controller,"getCurrentItem",queue);
        instanceMethod(controller,"getCurrentPosition",long.class);
        instanceMethod(controller,"getDuration",long.class);
        instanceMethod(controller,"getPlaybackRate",float.class);
        instanceMethod(controller,"isBuffering",boolean.class);
        instanceMethod(queue,"getPlaybackQueueId",long.class);
        Method media=queue.getMethod("getItem");require(!Modifier.isStatic(media.getModifiers()),"queue item changed");
        for(String name:new String[]{"getTitle","getArtistName","getAlbumTitle"})instanceMethod(media.getReturnType(),name,String.class);
        Class<?> local=loader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController");
        require(controller.isAssignableFrom(local),"local controller changed");
        next=declaredCommand(local,"skipToNextItem");previous=declaredCommand(local,"skipToPreviousItem");select=declaredCommand(local,"skipToQueueItemWithId",long.class);
        Class<?> converter=loader.loadClass(converterName),raw=loader.loadClass("z3.y");
        convert=converter.getMethod("f",controller,queue);toItem=converter.getMethod("b",raw);
        require(Modifier.isStatic(convert.getModifiers())&&convert.getReturnType()==raw,"raw conversion changed");
        require(Modifier.isStatic(toItem.getModifiers()),"item conversion changed");
        instanceMethod(toItem.getReturnType(),"getId",String.class);
        lifecycle=new ArrayList<>();
        for(Method m:service.getDeclaredMethods()){
            if(m.getName().equals("onCreate")&&Arrays.equals(m.getParameterTypes(),new Class<?>[0])&&m.getReturnType()==void.class)lifecycle.add(m);
            if(m.getName().equals("onStartCommand")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{android.content.Intent.class,int.class,int.class})&&m.getReturnType()==int.class)lifecycle.add(m);
            if(m.getName().equals("handleMessage")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{android.os.Message.class})&&m.getReturnType()==boolean.class)lifecycle.add(m);
        }
        require(!lifecycle.isEmpty(),"service lifecycle missing");
    }
    static NativeHostContract resolve(ClassLoader loader,long version){
        String preferred=version==1609?"O":version==1613?"M":version==1622||version==1626?"P":null;
        if(preferred!=null)return candidate(loader,preferred,true);
        NativeHostContract found=null;
        for(String name:new String[]{"P","M","O"}){
            NativeHostContract candidate=candidate(loader,name,false);
            if(candidate==null)continue;
            if(found!=null)return null; // Ambiguous profiles must not install any hooks.
            found=candidate;
        }
        return found;
    }
    private static NativeHostContract candidate(ClassLoader loader,String name,boolean explicit){
        try{return new NativeHostContract(loader,"com.apple.android.music.player."+name,explicit);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError rejected){return null;}
    }
    private static void require(boolean condition,String reason)throws NoSuchMethodException{if(!condition)throw new NoSuchMethodException(reason);}
    private static Field instanceField(Class<?> type,String name)throws ReflectiveOperationException{
        Field field=type.getDeclaredField(name);require(!Modifier.isStatic(field.getModifiers()),"static field "+name);field.setAccessible(true);return field;
    }
    private static Method instanceMethod(Class<?> type,String name,Class<?> returns,Class<?>...parameters)throws ReflectiveOperationException{
        Method method=type.getMethod(name,parameters);require(!Modifier.isStatic(method.getModifiers())&&method.getReturnType()==returns,"method "+name);return method;
    }
    private static Method declaredCommand(Class<?> type,String name,Class<?>...parameters)throws ReflectiveOperationException{
        Method method=type.getDeclaredMethod(name,parameters);require(Modifier.isPublic(method.getModifiers())&&!Modifier.isStatic(method.getModifiers())&&method.getReturnType()==void.class,"command "+name);return method;
    }
}
