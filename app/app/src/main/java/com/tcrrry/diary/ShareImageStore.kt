package com.tcrrry.diary

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun shareImageIntent(context:Context,images:List<ShareImageFile>):Intent{
    require(images.isNotEmpty())
    val uris=ArrayList(images.map{FileProvider.getUriForFile(context,"${context.packageName}.share-images",it.file)})
    return shareImageIntent(uris)
}
internal fun shareImageIntent(uris:ArrayList<Uri>):Intent{
    require(uris.isNotEmpty()&&uris.all{it.scheme=="content"})
    return Intent(if(uris.size==1)Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply{
        type="image/png"
        if(uris.size==1)putExtra(Intent.EXTRA_STREAM,uris.first())else putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
        clipData=ClipData("听歌日记",arrayOf("image/png"),ClipData.Item(uris.first())).apply{uris.drop(1).forEach{addItem(ClipData.Item(it))}}
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Publish only complete files; remove this batch if any write fails. No broad storage access. */
internal suspend fun saveShareImages(context:Context,images:List<ShareImageFile>):List<Uri>{
    require(images.isNotEmpty())
    val resolver=context.contentResolver;val pending=mutableListOf<Uri>()
    val stamp=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+"-"+java.util.UUID.randomUUID().toString().take(6)
    try{
        for((i,image)in images.withIndex()){
            currentCoroutineContext().ensureActive()
            val values=ContentValues().apply{
                put(MediaStore.Images.Media.DISPLAY_NAME,"听歌日记-$stamp-${i+1}.png")
                put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,"${Environment.DIRECTORY_PICTURES}/听歌日记")
                put(MediaStore.Images.Media.IS_PENDING,1)
            }
            val uri=checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)){"无法创建相册图片"};pending+=uri
            checkNotNull(resolver.openOutputStream(uri)){"无法写入相册"}.buffered(65536).use{out->image.file.inputStream().use{it.copyTo(out)}}
        }
        currentCoroutineContext().ensureActive()
        for(uri in pending)check(resolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)>0){"无法完成图片保存"}
        return pending
    }catch(t:Throwable){pending.forEach{runCatching{resolver.delete(it,null,null)}};throw t}
}
