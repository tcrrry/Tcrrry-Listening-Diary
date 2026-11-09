package com.tcrrry.diary

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun diaryClock(ms:Long,twelve:Boolean,zone:ZoneId=ZoneId.systemDefault()):String =
    Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(if(twelve)"a h:mm" else "HH:mm",Locale.CHINA))
