@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.tcrrry.diary
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.*

@Composable internal fun AppleImportSettings(c:DiaryController,choose:()->Unit){
    LaunchedEffect(Unit){c.loadAppleBatches()};var removal by remember{mutableStateOf<AppleBatch?>(null)};var help by rememberSaveable{mutableStateOf(false)}
    val uriHandler=LocalUriHandler.current
    Column(Modifier.fillMaxWidth().animateContentSize(spring(.9f,300f)),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Apple 官方历史",fontSize=18.sp)
        OutlinedButton(onClick=choose,enabled=!c.appleBusy,modifier=Modifier.fillMaxWidth().testTag("apple-import-choose")){Text(if(c.appleBusy)"正在处理…"else "导入 Apple 官方数据")}
        Text("支持播放活动CSV或官方ZIP，自动补全可用资料。每批可单独移除，无需整个文件夹。",fontSize=12.sp,color=Muted)
        TextButton(onClick={help=!help}){Text(if(help)"收起文件说明"else "需要哪些文件？",fontSize=13.sp)}
        AnimatedVisibility(help,enter=expandVertically(spring(.9f,300f))+fadeIn(),exit=shrinkVertically()+fadeOut()){Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text("从 Apple 获取历史数据",fontSize=14.sp)
            Text("前往 Apple 数据与隐私网站，登录 Apple 账户并申请获取数据副本，选择 Apple Music 相关数据。收到下载通知后，下载数据文件，再返回此处选择导入。",fontSize=12.sp,color=Muted)
            TextButton(onClick={uriHandler.openUri("https://privacy.apple.com/")}){Text("打开 privacy.apple.com",fontSize=13.sp)}
            Text("必需：Apple Music Play Activity.csv",fontSize=12.sp)
            Text("可选：Apple Music Library Tracks.json.zip",fontSize=12.sp,color=Muted)
            Text("可选：Apple Music - Play History Daily Tracks.csv",fontSize=12.sp,color=Muted)
            Text("三份可放进同一个ZIP。其余账户、设备、收藏和推荐资料都不需要。每日汇总仅用于补资料，不重复增加播放。",fontSize=12.sp,color=Muted)
        }}
        c.appleError?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
        c.appleBatches.forEach{batch->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Muted.copy(alpha=.06f)).padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(batch.label,fontSize=14.sp,maxLines=2);Text("${batch.from} — ${batch.to}",fontSize=12.sp,color=Muted);Text("${batch.rows}段 · ${shortDuration(batch.ms)}",fontSize=12.sp,color=Muted)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={c.viewApple(batch)}){Text("查看")};TextButton(onClick={removal=batch},enabled=!c.appleBusy,modifier=Modifier.testTag("apple-remove:${batch.id}")){Text("移除此批",color=MaterialTheme.colorScheme.error)}}
        }}
    }
    removal?.let{batch->AlertDialog(onDismissRequest={removal=null},title={Text("移除此批导入？",fontSize=22.sp)},text={Text("只移除这批 Apple 历史。现有采集记录、其他导入批次和图片缓存会保留，原文件可再次导入。")},confirmButton={TextButton(onClick={removal=null;c.removeApple(batch.id)},modifier=Modifier.testTag("apple-remove-confirm")){Text("移除")}},dismissButton={TextButton(onClick={removal=null}){Text("取消")}})}
}
@Composable internal fun AppleImportReview(c:DiaryController){
    val plan=c.applePlan?:return
    ModalBottomSheet(onDismissRequest={c.cancelApple()},containerColor=MaterialTheme.colorScheme.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).verticalScroll(rememberScrollState()).testTag("apple-import-review"),verticalArrangement=Arrangement.spacedBy(13.dp)){
            Text("导入 Apple 历史",fontSize=26.sp);Text("${plan.from} — ${plan.to}",fontSize=17.sp)
            Text("${plan.rows.size}段播放 · ${shortDuration(plan.ms)}",fontSize=20.sp)
            Text("${plan.unknownArtist}段缺少可靠歌手资料，${plan.unknownAlbum}段缺少专辑。未知字段保留为空，不影响导入。",fontSize=13.sp,color=Muted)
            if(plan.inferredStarts>0)Text("${plan.inferredStarts}段的起点由结束时间与报送时长推得。",fontSize=12.sp,color=Muted)
            Text("仅导入播放结束记录，忽略开始、歌词展示和汇总表；已排除${plan.duplicates}条重复事件。时间按导出时区，时长按 Apple 报送值。",fontSize=12.sp,color=Muted)
            Text("这批历史单独保存，可在设置中移除。与现有记录日期重叠时，请核对来源，避免多设备记录被当作单设备统计。",fontSize=12.sp,color=Muted)
            c.appleError?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
            Button(onClick={c.commitApple()},enabled=!c.appleBusy,modifier=Modifier.fillMaxWidth().testTag("apple-import-confirm")){Text(if(c.appleBusy)"正在导入…"else "确认导入")}
            TextButton(onClick={c.cancelApple()},enabled=!c.appleBusy,modifier=Modifier.fillMaxWidth()){Text("取消")}
        }
    }
}
