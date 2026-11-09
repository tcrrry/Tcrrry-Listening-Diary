package com.tcrrry.diary

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Foreground-only request/reply tracking. A received batch, rather than a sent request, confirms success. */
internal class CollectorLink(private val context:Context,private val scope:CoroutineScope,private val changed:()->Unit) {
    companion object {
        const val ACTION="com.tcrrry.listeningdiary.app.REQUEST_SYNC"
        const val PERMISSION="com.tcrrry.listeningdiary.app.permission.REQUEST_SYNC"
        const val WAIT_MS=12000L
        const val STALE_MS=20000L
    }
    private val prefs=context.getSharedPreferences("diary",Context.MODE_PRIVATE)
    private var watching:Job?=null
    private var requesting:Job?=null
    private var epoch=0
    private var awaiting:Long?=null
    private var observedSerial=serial()
    var syncing by mutableStateOf(false);private set
    var status by mutableStateOf<String?>(null);private set
    private fun serial()=prefs.getLong("sync_serial",0)
    private fun send(){
        BridgeIdentity.restoreAccess(context)
        context.sendBroadcast(Intent(ACTION).setPackage(BridgeIdentity.HOST).apply{clipData=ClipData.newRawUri("diary records",BridgeIdentity.URI)}
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
    fun received(){
        val now=serial()
        if(now==observedSerial||now==awaiting)return
        observedSerial=now
        awaiting=null;status="已收到采集器更新";syncing=false
        requesting?.cancel();requesting=null
    }
    fun start(approved:Boolean){
        if(watching?.isActive==true)return
        watching=scope.launch {
            var seen=serial()
            var lastAttempt=android.os.SystemClock.elapsedRealtime()
            while(isActive){
                delay(2000);val now=serial()
                if(now!=seen){seen=now;received();changed()}
                val elapsed=android.os.SystemClock.elapsedRealtime()
                val age=System.currentTimeMillis()-prefs.getLong("last_sync",0)
                if(approved&&!syncing&&age>=STALE_MS&&elapsed-lastAttempt>=STALE_MS){lastAttempt=elapsed;request()}
            }
        }
        if(approved)request()
    }
    fun request(approve:suspend ()->Unit={}){
        requesting?.cancel();val ticket=++epoch;val before=serial();awaiting=before
        syncing=true;status="正在请求采集器同步…"
        requesting=scope.launch {
            try {
                approve();send()
                repeat((WAIT_MS/500).toInt()){step->
                    delay(500)
                    if(serial()!=before){status="已收到采集器更新";changed();return@launch}
                    // A receiver may still be registering as AM++ resumes. Retry only this bounded foreground request.
                    if(step==3||step==11)send()
                }
                changed();status="暂未收到采集器回应，已有记录已保留。AM++ 被关闭时插件无法回应，请点下方按钮打开 AM++ 并确认采集器已启用；返回后自动补同步"
            }catch(e:CancellationException){throw e}
            catch(e:Exception){status="连接未完成，请确认 AM++ 和采集插件已安装启用后重试"}
            finally{if(ticket==epoch){syncing=false;awaiting=null}}
        }
    }
    fun stop(){epoch++;awaiting=null;watching?.cancel();watching=null;requesting?.cancel();requesting=null;syncing=false;status=null}
}
