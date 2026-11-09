package com.tcrrry.diary
internal data class RepeatPlays(val songs:List<Play>){val key get()=songs.minBy{it.start}.visit;val start get()=songs.minOf{it.start};val end get()=songs.maxOf{it.end}}
/** Only neighboring visits in one already-separated session may collapse. No database records are merged. */
internal fun repeatPlays(songs:List<Play>):List<RepeatPlays>{val groups=mutableListOf<MutableList<Play>>();songs.forEach{p->if(groups.lastOrNull()?.lastOrNull()?.id==p.id)groups.last().add(p)else groups.add(mutableListOf(p))};return groups.map{RepeatPlays(it.toList())}}
