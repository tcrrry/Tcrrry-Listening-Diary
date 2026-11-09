package com.tcrrry.diary;

import android.content.*;import android.os.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class CollectorRequestsTest {
    private CollectorRequests requests;private Context context;private int calls;private Runnable done;private String permission;private int flags;
    @Before public void setup(){context=new ContextWrapper(RuntimeEnvironment.getApplication()){
        @Override public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Handler h,int export){permission=p;flags=export;return super.registerReceiver(r,f,p,h,export);}
        @Override public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Handler h){permission=p;return super.registerReceiver(r,f,p,h);}
    };requests=new CollectorRequests(context,finish->{calls++;done=finish;});((org.robolectric.shadows.ShadowApplication)org.robolectric.shadow.api.Shadow.extract(RuntimeEnvironment.getApplication())).grantPermissions(CollectorRequests.PERMISSION);}
    @After public void close(){requests.close();}
    private void send(){context.sendBroadcast(new Intent(CollectorRequests.ACTION).setPackage(context.getPackageName()));org.robolectric.shadows.ShadowLooper.idleMainLooper();}
    @Test public void exportedReceiverRequiresTheDiarySignaturePermission(){assertEquals(CollectorRequests.PERMISSION,permission);assertEquals(Context.RECEIVER_EXPORTED,flags);send();assertEquals(1,calls);assertNotNull(done);done.run();done.run();}
    @Test @Config(sdk=31) public void androidTwelveAlsoRequiresSenderPermission(){assertEquals(CollectorRequests.PERMISSION,permission);send();assertEquals(1,calls);done.run();}
    @Test public void stopUnregistersReceiverAndCompletesPendingWork(){send();assertEquals(1,calls);requests.close();requests.close();send();assertEquals(1,calls);done.run();}
    @Test public void unrelatedBroadcastDoesNotRequestSync(){context.sendBroadcast(new Intent("unrelated"));org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(0,calls);}
}
