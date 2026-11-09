package com.tcrrry.diary
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*

@Composable internal fun ListeningDaySettings(c:DiaryController){
    var hours by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().testTag("listening-day-settings"),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("听歌日划分",fontSize=18.sp)
        ListeningDayMode.entries.forEach{mode->FilterChip(selected=c.dayPolicy.mode==mode,onClick={c.dayPolicy(c.dayPolicy.copy(mode=mode))},label={Text(mode.label)},modifier=Modifier.testTag("day-mode:${mode.name}"))}
        if(c.dayPolicy.mode==ListeningDayMode.FIXED){
            Box{OutlinedButton(onClick={hours=true},modifier=Modifier.testTag("day-cutoff")){Text("每天 %02d:00 开始".format(c.dayPolicy.hour))}
                DropdownMenu(expanded=hours,onDismissRequest={hours=false},modifier=Modifier.heightIn(max=320.dp)){
                    (0..12).forEach{hour->DropdownMenuItem(text={Text("%02d:00".format(hour))},onClick={hours=false;c.dayPolicy(c.dayPolicy.copy(hour=hour))},modifier=Modifier.testTag("day-hour:$hour"))}
                }
            }
        }
        Text(c.dayPolicy.description,fontSize=12.sp,color=Muted)
        Text("日、周、月、年与总结使用同一规则；只调整统计归属，原始时间和导入数据保留。",fontSize=12.sp,color=Muted)
    }
}
