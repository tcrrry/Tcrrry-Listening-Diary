package com.tcrrry.diary
import android.content.*
import android.content.pm.*
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class BridgeAccessTest {
    @Test fun restoreLostAccessOnlyForAlreadyApprovedCertificate(){
        val app=RuntimeEnvironment.getApplication()
        val info=PackageInfo().apply{packageName=BridgeIdentity.HOST;applicationInfo=ApplicationInfo().apply{packageName=BridgeIdentity.HOST;uid=12345};signingInfo=SigningInfo().also{Shadows.shadowOf(it).setSignatures(arrayOf(Signature("12345678")))}}
        Shadows.shadowOf(app.packageManager).installPackage(info)
        app.getSharedPreferences("bridge",0).edit().clear().commit()
        var grants=0
        val c=object:ContextWrapper(app){override fun grantUriPermission(pkg:String,uri:Uri,flags:Int){assertEquals(BridgeIdentity.HOST,pkg);assertEquals(BridgeIdentity.URI,uri);assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);grants++}}
        BridgeIdentity.restoreAccess(c);assertEquals(0,grants)
        BridgeIdentity.approve(c);assertEquals(1,grants)
        BridgeIdentity.restoreAccess(c);assertEquals(2,grants)
        Shadows.shadowOf(info.signingInfo).setSignatures(arrayOf(Signature("87654321")))
        BridgeIdentity.restoreAccess(c);assertEquals(2,grants);assertFalse(BridgeIdentity.approved(c))
    }
}
