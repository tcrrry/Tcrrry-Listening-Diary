package com.tcrrry.diary
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.database.Cursor
import org.json.JSONObject
import java.security.MessageDigest

object BridgeIdentity {
    const val HOST="com.apple.android.music"
    val URI:Uri=Uri.parse("content://com.tcrrry.listeningdiary.bridge/records")
    fun fingerprint(context:Context,packageName:String):String {
        val info=context.packageManager.getPackageInfo(packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        return info.signingInfo!!.apkContentsSigners.map{sig->MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString(""){"%02x".format(it)}}.sorted().joinToString(":")
    }
    fun approve(context:Context){val cert=fingerprint(context,HOST);context.getSharedPreferences("bridge",0).edit().putString("certificate",cert).commit();context.grantUriPermission(HOST,URI,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
    // The saved certificate outlives temporary URI grants and process/package restarts.
    // Restore access only for the installation the user already approved.
    fun restoreAccess(context:Context){if(approved(context))context.grantUriPermission(HOST,URI,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
    fun approved(context:Context):Boolean=runCatching{context.getSharedPreferences("bridge",0).getString("certificate",null)==fingerprint(context,HOST)}.getOrDefault(false)
}
class BridgeProvider:ContentProvider() {
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        val c=context?:throw IllegalStateException();val uid=Binder.getCallingUid()
        val packages=c.packageManager.getPackagesForUid(uid).orEmpty()
        if(!packages.contains(BridgeIdentity.HOST))throw SecurityException("Only the approved AM++ host can provide records")
        val approved=BridgeIdentity.approved(c)
        if(method=="hello")return Bundle().apply{putBoolean("approved",approved);putInt("eventSupport",1);putString("generation",DiaryDatabase.get(c).generation);if(approved)putString("neededArt",DiaryDatabase.get(c).neededArtwork())}
        if(!approved)throw SecurityException("Open Diary and approve this AM++ installation first")
        require(method=="upsert"){"Unknown method"}
        val payload=extras?.getString("payload")?:throw IllegalArgumentException("Missing batch")
        require(payload.toByteArray(Charsets.UTF_8).size<=380000){"Batch too large"}
        val count=DiaryDatabase.get(c).ingest(JSONObject(payload))
        c.contentResolver.notifyChange(BridgeIdentity.URI,null)
        return Bundle().apply{putBoolean("accepted",true);putInt("rows",count)}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=throw UnsupportedOperationException()
    override fun getType(uri:Uri)="application/vnd.tcrrry.diary.batch+json"
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
