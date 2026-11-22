package com.tcrrry.diary;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class NativeHostContractTest {
 private final ClassLoader parent=getClass().getClassLoader();
 @Test public void explicitProfilesValidateAllDependencies(){
  for(int version:new int[]{1609,1613,1622,1626}){
   NativeHostContract contract=NativeHostContract.resolve(parent,version);
   assertNotNull("host "+version,contract);assertTrue(contract.explicitVersion);
  }
 }
 @Test public void unknownAmbiguousHostFailsClosed(){assertNull(NativeHostContract.resolve(parent,1700));}
 @Test public void unknownHostWithOneCompleteProfileIsAccepted(){
  NativeHostContract contract=NativeHostContract.resolve(hide("com.apple.android.music.player.M","com.apple.android.music.player.O"),1700);
  assertNotNull(contract);assertFalse(contract.explicitVersion);assertTrue(contract.converterName.endsWith(".P"));
 }
 @Test public void explicitProfileDoesNotGuessAnotherConverterWhenItsContractIsMissing(){
  assertNull(NativeHostContract.resolve(hide("com.apple.android.music.player.P"),1626));
 }
 @Test public void missingListenerOrRawConversionFallsBackForEveryVersion(){
  assertNull(NativeHostContract.resolve(hide("z3.y"),1626));
  assertNull(NativeHostContract.resolve(hide("com.apple.android.music.playback.controller.MediaPlayerController$Listener"),1700));
 }
 @Test public void unsafePrimitiveListenerReturnIsRejected(){
  ClassLoader changed=new ClassLoader(parent){@Override public Class<?> loadClass(String name)throws ClassNotFoundException{
   if(name.equals("com.apple.android.music.playback.controller.MediaPlayerController$Listener"))return BrokenListener.class;
   return super.loadClass(name);
  }};
  assertNull(NativeHostContract.resolve(changed,1626));
 }
 public interface BrokenListener {void onCurrentItemChanged();boolean unexpectedQuery();}
 private ClassLoader hide(String...names){return new ClassLoader(parent){@Override public Class<?> loadClass(String name)throws ClassNotFoundException{
  for(String hidden:names)if(hidden.equals(name))throw new ClassNotFoundException(name);
  return super.loadClass(name);
 }};}
}
