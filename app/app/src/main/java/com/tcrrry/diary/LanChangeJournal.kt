package com.tcrrry.diary

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Ordered local changes for a future paired-device session. Not enabled until pairing. */
internal object LanChangeJournal {
    internal data class Table(val name:String,val keys:List<String>)
    internal data class Change(val sequence:Long,val table:String,val keys:List<String>,val deleted:Boolean)
    internal data class Record(val change:Change,val row:JSONObject?,val clock:Long,val origin:String,val skip:Boolean=false)
    private val tables=listOf(
        Table("tracks",listOf("id")),Table("spans",listOf("remote_id")),
        Table("events",listOf("remote_id")),Table("event_sources",listOf("source")),
        Table("import_batches",listOf("id")),Table("import_tracks",listOf("batch","id")),
        Table("import_spans",listOf("batch","row_key","part"))
    )
    fun tableFor(name:String):Table?=tables.singleOrNull{it.name==name}
    private fun quote(identifier:String):String {require(identifier.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")));return "\"$identifier\""}
    private fun keys(prefix:String,keys:List<String>):String=(0..2).joinToString(","){i->keys.getOrNull(i)?.let{"CAST($prefix.${quote(it)} AS TEXT)"}?:"''"}

    /** Install and backfill in one transaction so an update cannot fall between them. */
    fun install(db:SQLiteDatabase){
        db.beginTransaction()
        try{
            db.execSQL("CREATE TABLE IF NOT EXISTS lan_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT, entity TEXT NOT NULL, key1 TEXT NOT NULL, key2 TEXT NOT NULL, key3 TEXT NOT NULL, deleted INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS lan_state(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS lan_versions(entity TEXT NOT NULL,key1 TEXT NOT NULL,key2 TEXT NOT NULL,key3 TEXT NOT NULL,clock INTEGER NOT NULL,origin TEXT NOT NULL,PRIMARY KEY(entity,key1,key2,key3))")
            db.execSQL("INSERT OR IGNORE INTO lan_state(name,value) VALUES('replica_id',lower(hex(randomblob(16))))")
            db.execSQL("INSERT OR IGNORE INTO lan_state(name,value) VALUES('logical_clock','1')")
            val initialized=db.rawQuery("SELECT 1 FROM lan_state WHERE name='journal_version'",null).use{it.moveToFirst()}
            if(!initialized){
                for(table in tables){
                    val name=quote(table.name);val cols=mutableListOf<String>()
                    db.rawQuery("PRAGMA table_info($name)",null).use{c->while(c.moveToNext())cols+=c.getString(c.getColumnIndexOrThrow("name"))}
                    require(cols.isNotEmpty()&&table.keys.all{it in cols}){"Missing sync table ${table.name}"}
                    fun trigger(suffix:String,event:String,prefix:String,deleted:Int,condition:String=""){
                        val trigger=quote("lan_${table.name}_$suffix")
                        db.execSQL("CREATE TRIGGER $trigger AFTER $event ON $name $condition BEGIN INSERT INTO lan_changes(entity,key1,key2,key3,deleted) VALUES('${table.name}',${keys(prefix,table.keys)},$deleted); END")
                    }
                    trigger("insert","INSERT","NEW",0)
                    val changed=cols.joinToString(" OR "){"OLD.${quote(it)} IS NOT NEW.${quote(it)}"}
                    val rekeyed=table.keys.joinToString(" OR "){"OLD.${quote(it)} IS NOT NEW.${quote(it)}"}
                    trigger("rekey_old","UPDATE","OLD",1,"WHEN $rekeyed")
                    trigger("update","UPDATE","NEW",0,"WHEN $changed")
                    trigger("delete","DELETE","OLD",1)
                    val bootstrap=(0..2).joinToString(","){i->table.keys.getOrNull(i)?.let{"CAST(${quote(it)} AS TEXT)"}?:"''"}
                    db.execSQL("INSERT INTO lan_changes(entity,key1,key2,key3,deleted) SELECT '${table.name}',$bootstrap,0 FROM $name")
                }
                db.execSQL("INSERT INTO lan_state(name,value) VALUES('journal_version','1')")
            }
            val versionBackfill=db.rawQuery("SELECT 1 FROM lan_state WHERE name='versions_ready'",null).use{!it.moveToFirst()}
            for(table in tables){
                val name=quote(table.name)
                val rekeyed=table.keys.joinToString(" OR "){"OLD.${quote(it)} IS NOT NEW.${quote(it)}"}
                // Early previews kept a second tombstone table; logical versions
                // now carry that information without extra writes on every row.
                for(suffix in listOf("delete","rekey","insert"))db.execSQL("DROP TRIGGER IF EXISTS ${quote("lan_${table.name}_tombstone_$suffix")}")
                val bootstrap=(0..2).joinToString(","){i->table.keys.getOrNull(i)?.let{"CAST(${quote(it)} AS TEXT)"}?:"''"}
                if(versionBackfill)db.execSQL("INSERT OR IGNORE INTO lan_versions(entity,key1,key2,key3,clock,origin) SELECT '${table.name}',$bootstrap,1,(SELECT value FROM lan_state WHERE name='replica_id') FROM $name")
                fun versionTrigger(suffix:String,event:String,prefix:String,condition:String=""){
                    val trigger=quote("lan_${table.name}_version_$suffix")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS $trigger AFTER $event ON $name $condition BEGIN UPDATE lan_state SET value=CAST(value AS INTEGER)+1 WHERE name='logical_clock'; INSERT OR REPLACE INTO lan_versions VALUES('${table.name}',${keys(prefix,table.keys)},CAST((SELECT value FROM lan_state WHERE name='logical_clock') AS INTEGER),(SELECT value FROM lan_state WHERE name='replica_id')); END")
                }
                versionTrigger("insert","INSERT","NEW")
                versionTrigger("delete","DELETE","OLD")
                val columns=mutableListOf<String>()
                db.rawQuery("PRAGMA table_info($name)",null).use{c->while(c.moveToNext())columns+=c.getString(c.getColumnIndexOrThrow("name"))}
                val changed=columns.joinToString(" OR "){"OLD.${quote(it)} IS NOT NEW.${quote(it)}"}
                versionTrigger("update","UPDATE","NEW","WHEN $changed")
                versionTrigger("rekey","UPDATE","OLD","WHEN $rekeyed")
            }
            if(versionBackfill)db.execSQL("INSERT INTO lan_state(name,value) VALUES('versions_ready','1')")
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun after(db:SQLiteDatabase,cursor:Long,limit:Int):List<Change>{
        require(cursor>=0&&limit in 1..512)
        val out=ArrayList<Change>()
        db.rawQuery("SELECT seq,entity,key1,key2,key3,deleted FROM lan_changes WHERE seq>? ORDER BY seq LIMIT ?",arrayOf(cursor.toString(),limit.toString())).use{c->
            while(c.moveToNext())out+=Change(c.getLong(0),c.getString(1),listOf(c.getString(2),c.getString(3),c.getString(4)),c.getInt(5)!=0)
        }
        return out
    }
    /** Resolve keys against the current row; missing rows become tombstones. */
    fun recordsAfter(db:SQLiteDatabase,cursor:Long,limit:Int,skipOrigin:String?=null):List<Record>{
        return after(db,cursor,limit).map{change->
            val table=tables.singleOrNull{it.name==change.table}?:error("Unknown journal entity")
            run {
                val version=db.rawQuery("SELECT clock,origin FROM lan_versions WHERE entity=? AND key1=? AND key2=? AND key3=?",arrayOf(change.table,*change.keys.toTypedArray())).use{c->check(c.moveToFirst());c.getLong(0) to c.getString(1)}
                if(skipOrigin!=null&&version.second==skipOrigin)return@run Record(change,null,version.first,version.second,true)
                val where=table.keys.joinToString(" AND "){"${quote(it)}=?"}
                val row=db.rawQuery("SELECT * FROM ${quote(table.name)} WHERE $where",change.keys.take(table.keys.size).toTypedArray()).use{c->
                    if(!c.moveToFirst())null else JSONObject().apply{
                        for(i in 0 until c.columnCount){
                            val value:Any=when(c.getType(i)){
                                android.database.Cursor.FIELD_TYPE_NULL->JSONObject.NULL
                                android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_STRING->c.getString(i)
                                else->error("Unexpected blob in sync row")
                            }
                            put(c.getColumnName(i),value)
                        }
                    }
                }
                Record(change,row,version.first,version.second)
            }
        }
    }
}
