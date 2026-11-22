package com.tcrrry.diary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI

internal object DiaryUpdateRules {
    const val repository="tcrrry/Tcrrry-Listening-Diary"
    fun version(tag:String):String?=tag.removePrefix("v").takeIf { tag.startsWith("v") && it.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) }
    fun newer(candidate:String,current:String):Boolean {
        fun parts(s:String):List<Long> { return s.split('.').map{it.toLongOrNull() ?: return emptyList()} }
        val a=parts(candidate);val b=parts(current)
        if(a.isEmpty()||b.isEmpty())return false
        for(i in 0 until maxOf(a.size,b.size)){val comparison=a.getOrElse(i){0}.compareTo(b.getOrElse(i){0});if(comparison!=0)return comparison>0}
        return false
    }
    fun shouldNotify(automatic:Boolean,tag:String,ignored:String?,notified:String?,snooze:Long,now:Long)=automatic&&ignored!=tag&&(notified!=tag||(snooze>0&&now>=snooze))
    fun parse(body:String):String {
        val data=JSONObject(body)
        require(!data.optBoolean("draft")&&!data.optBoolean("prerelease"))
        return data.getString("tag_name").also{require(version(it)!=null)}
    }
    fun redirectTag(location:String?):String? {
        val raw=location ?: return null
        val uri=runCatching { URI(raw) }.getOrNull() ?: return null
        if(uri.isAbsolute && (uri.scheme != "https" || !uri.host.equals("github.com",ignoreCase=true) || uri.port != -1 || uri.userInfo != null))return null
        if(!uri.isAbsolute && uri.rawAuthority!=null)return null
        if(uri.rawQuery!=null || uri.rawFragment!=null)return null
        val prefix="/$repository/releases/tag/"
        val path=uri.rawPath ?: return null
        if(!path.startsWith(prefix))return null
        return path.removePrefix(prefix).takeIf { version(it)!=null }
    }
}

internal object DiaryReleaseLookup {
    private fun connection(url:String):HttpURLConnection=(URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout=5000;readTimeout=8000;instanceFollowRedirects=false
        setRequestProperty("Accept","application/vnd.github+json")
        setRequestProperty("User-Agent","Tcrrry-Listening-Diary/${BuildConfig.VERSION_NAME}")
    }
    fun latest():String {
        val api=runCatching {
            val c=connection("https://api.github.com/repos/${DiaryUpdateRules.repository}/releases/latest")
            try {
                check(c.responseCode==200) { "API HTTP ${c.responseCode}" }
                DiaryUpdateRules.parse(c.inputStream.bufferedReader().use { it.readText().also { text->check(text.length<=256*1024) } })
            } finally { c.disconnect() }
        }
        api.getOrNull()?.let{return it}
        android.util.Log.w("DiaryUpdates","API lookup failed: ${api.exceptionOrNull()?.javaClass?.simpleName}")
        val c=connection("https://github.com/${DiaryUpdateRules.repository}/releases/latest")
        return try {
            check(c.responseCode in listOf(301,302,303,307,308)) { "Release redirect HTTP ${c.responseCode}" }
            checkNotNull(DiaryUpdateRules.redirectTag(c.getHeaderField("Location"))) { "Invalid release redirect" }
        } finally { c.disconnect() }
    }
}

internal class DiaryUpdateChecker(private val activity:Activity,private val scope:CoroutineScope,private val isForeground:()->Boolean){
    private val prefs=activity.getSharedPreferences("release_updates",Context.MODE_PRIVATE)
    var automatic by mutableStateOf(prefs.getBoolean("automatic",true));private set
    var checking by mutableStateOf(false);private set
    var message by mutableStateOf<String?>(null);private set
    var dialogTag by mutableStateOf<String?>(null);private set
    fun automatic(value:Boolean){automatic=value;prefs.edit().putBoolean("automatic",value).apply()}
    private fun shouldNotify(tag:String,now:Long)=DiaryUpdateRules.shouldNotify(automatic,tag,prefs.getString("ignored",null),prefs.getString("notified",null),prefs.getLong("snooze",0),now)
    private fun newer(tag:String)=DiaryUpdateRules.version(tag)?.let{DiaryUpdateRules.newer(it,BuildConfig.VERSION_NAME)}==true
    private fun show(tag:String){dialogTag=tag;prefs.edit().putString("notified",tag).putLong("snooze",0).apply()}
    fun later(){val tag=dialogTag?:return;prefs.edit().putString("notified",tag).putLong("snooze",System.currentTimeMillis()+24*60*60*1000L).apply();dialogTag=null}
    fun ignore(){val tag=dialogTag?:return;prefs.edit().putString("ignored",tag).putLong("snooze",0).apply();dialogTag=null}
    fun disable(){automatic(false);dialogTag=null}
    fun open(){val tag=dialogTag?:return;dialogTag=null;runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/${DiaryUpdateRules.repository}/releases/tag/$tag")))}.onFailure{message="无法打开发布页"}}
    fun check(manual:Boolean){
        if(checking||(!manual&&!automatic))return
        val now=System.currentTimeMillis()
        if(!manual){
            val cached=prefs.getString("latest",null)
            if(cached!=null&&newer(cached)&&shouldNotify(cached,now)){show(cached);return}
            if(now-prefs.getLong("checked",0)<6*60*60*1000L)return
        }
        checking=true;if(manual)message="正在检查更新…"
        prefs.edit().putLong("checked",now).apply()
        scope.launch {
            val result=withContext(Dispatchers.IO){runCatching { DiaryReleaseLookup.latest() }}
            checking=false
            val tag=result.getOrNull()
            if(tag==null){android.util.Log.w("DiaryUpdates","Release lookup failed: ${result.exceptionOrNull()?.javaClass?.simpleName}");if(manual)message="无法完成更新检查，请检查网络后重试";return@launch}
            prefs.edit().putString("latest",tag).apply()
            if(!newer(tag)){if(manual)message="当前已是最新正式版"}
            else if(manual||(isForeground()&&shouldNotify(tag,System.currentTimeMillis()))){message=null;show(tag)}
        }
    }
}

@Composable internal fun DiaryUpdateDialog(checker:DiaryUpdateChecker){
    val tag=checker.dialogTag ?: return
    AlertDialog(onDismissRequest={checker.later()},title={Text("发现新版本 ${DiaryUpdateRules.version(tag)}")},text={
        Column {
            Text("当前版本 ${BuildConfig.VERSION_NAME}",fontSize=13.sp,color=Muted)
            TextButton(onClick={checker.open()},modifier=Modifier.fillMaxWidth()){Text("查看并下载更新")}
            TextButton(onClick={checker.later()},modifier=Modifier.fillMaxWidth()){Text("稍后一天提醒")}
            TextButton(onClick={checker.ignore()},modifier=Modifier.fillMaxWidth()){Text("忽略此版本")}
            TextButton(onClick={checker.disable()},modifier=Modifier.fillMaxWidth()){Text("不再自动提醒")}
        }
    },confirmButton={})
}

@Composable internal fun DiaryUpdateSettings(checker:DiaryUpdateChecker){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("软件更新",fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
        Text("当前版本 ${BuildConfig.VERSION_NAME}",fontSize=12.sp,color=Muted)
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("自动提醒新版本");Text("关闭后仍可手动检查",fontSize=12.sp,color=Muted)};Switch(checker.automatic,{checker.automatic(it)})}
        OutlinedButton(onClick={checker.check(true)},enabled=!checker.checking,modifier=Modifier.fillMaxWidth().testTag("check-updates")){Text(if(checker.checking)"正在检查…" else "检查更新")}
        checker.message?.let{Text(it,fontSize=12.sp,color=Muted)}
    }
}
