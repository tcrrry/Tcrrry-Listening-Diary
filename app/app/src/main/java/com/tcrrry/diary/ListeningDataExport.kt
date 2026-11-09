package com.tcrrry.diary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.io.Writer

internal fun writeListeningExport(out:Writer,r:DiaryReport,csv:Boolean){
    fun q(s:Any?)="\""+(s?.toString().orEmpty().replace("\"","\"\""))+"\""
    fun line(values:List<Any?>){out.write(values.joinToString(if(csv)"," else "\t"){if(csv)q(it) else it?.toString().orEmpty().replace('\t',' ').replace('\n',' ')});out.write("\n")}
    line(listOf("听歌日期","开始时间","结束时间","歌曲","歌手","专辑","本次听歌秒数","歌曲时长秒数","有效播放次数","播放设备","音量百分比","记录来源"))
    val format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    for(p in r.history.sortedBy{it.start}){val zone=runCatching{ZoneId.of(p.zone)}.getOrDefault(ZoneId.systemDefault());fun time(v:Long)=Instant.ofEpochMilli(v).atZone(zone).format(format)
        line(listOf(p.listeningDay.ifBlank{Instant.ofEpochMilli(p.start).atZone(zone).toLocalDate().toString()},time(p.start),time(p.end),p.title,p.artist,p.album,"%.1f".format(java.util.Locale.ROOT,p.ms/1000.0),"%.1f".format(java.util.Locale.ROOT,p.duration/1000.0),p.effective,p.device,p.volumePct.takeIf{it>=0},if(p.imported)"导入" else "采集"))
    }
}

@Composable internal fun ListeningDataExport(c:DiaryController){
    var visible by remember{mutableStateOf(false)};var from by remember{mutableStateOf(c.day.toString())};var to by remember{mutableStateOf(c.day.toString())};var mode by remember{mutableIntStateOf(0)};var format by remember{mutableIntStateOf(0)};var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope();var selected by remember{mutableStateOf<DayBounds?>(null)};var all by remember{mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")){uri->if(uri!=null){val b=selected;val full=all;val isCsv=format==0;val policy=c.dayPolicy;scope.launch{try{withContext(Dispatchers.IO){val db=DiaryDatabase.get(c.activity);val report=if(full)db.readAllReportVersioned(policy).first else db.readReportVersioned(b!!,policy).first;c.activity.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use{if(isCsv)it.write('\uFEFF'.code);writeListeningExport(it,report,isCsv)}?:error("无法打开文件")};c.notice="听歌数据已导出"}catch(e:Exception){c.notice="导出失败，请检查存储空间"}}}}
    OutlinedButton(onClick={visible=true},modifier=Modifier.fillMaxWidth()){Text("导出听歌数据（CSV / TXT）")}
    if(visible)AlertDialog(onDismissRequest={visible=false},title={Text("导出听歌数据")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("选择周期，或输入起止日期（含当天）")
        listOf(listOf("日","周","月"),listOf("年","全部","自定义")).forEachIndexed{row,names->Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){names.forEachIndexed{col,name->val i=row*3+col;FilterChip(selected=mode==i,onClick={mode=i},label={Text(name)})}}}
        if(mode!=4){OutlinedTextField(value=from,onValueChange={from=it},label={Text(if(mode==5)"开始日期" else "参考日期")},singleLine=true);if(mode==5)OutlinedTextField(value=to,onValueChange={to=it},label={Text("结束日期")},singleLine=true)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("CSV","TXT").forEachIndexed{i,name->FilterChip(selected=format==i,onClick={format=i},label={Text(name)})}}
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={try{all=mode==4;selected=when(mode){4->null;5->{val a=LocalDate.parse(from);val b=LocalDate.parse(to);require(a<=b);DayBounds(a.toString(),b.toString())};else->bounds(LocalDate.parse(from),DiaryRange.entries[mode])};error="";visible=false;launcher.launch("听歌数据-${LocalDate.now()}.${if(format==0)"csv" else "txt"}")}catch(e:Exception){error="请输入有效日期，格式为 YYYY-MM-DD，结束日期不能早于开始日期"}}){Text("选择保存位置")}},dismissButton={TextButton(onClick={visible=false}){Text("取消")}})
}
