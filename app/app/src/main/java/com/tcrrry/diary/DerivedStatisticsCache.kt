package com.tcrrry.diary

import android.content.Context
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException

/** Disposable derived results. Original listening records remain in diary.db. */
internal object DerivedStatisticsCache {
    private data class Entry(val stamp:String,val value:Serializable):Serializable
    private fun file(context:Context,key:String):File {
        val folder=File(context.filesDir,"derived-statistics-v1").apply{mkdirs()}
        return File(folder,ArtworkKeys.hash(key)+".bin")
    }
    @Synchronized fun read(context:Context,key:String,stamp:String):Serializable?=runCatching {
        val source=file(context,key)
        if(source.length() !in 1..(64L*1024*1024))return null
        // Own the stream even if a corrupt header prevents constructing ObjectInputStream.
        val saved=source.inputStream().buffered().use{raw->ObjectInputStream(raw).use{it.readObject() as Entry}}
        saved.value.takeIf{saved.stamp==stamp}
    }.getOrNull()
    @Synchronized fun write(context:Context,key:String,stamp:String,value:Serializable){
        runCatching {
            val target=file(context,key)
            val temporary=File.createTempFile("derived-",".tmp",target.parentFile)
            try {
                FileOutputStream(temporary).use{raw->
                    ObjectOutputStream(BufferedOutputStream(SnapshotOutputStream(raw),65536)).use{it.writeObject(Entry(stamp,value))}
                    raw.fd.sync()
                }
                require(temporary.length()<=64L*1024*1024)
                try{Files.move(temporary.toPath(),target.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)}
                catch(_:AtomicMoveNotSupportedException){Files.move(temporary.toPath(),target.toPath(),REPLACE_EXISTING)}
            }finally{temporary.delete()}
        }.onFailure{android.util.Log.w("DiaryDerivedCache","Could not save derived statistics",it)}
    }
}
