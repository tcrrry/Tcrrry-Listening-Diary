package com.tcrrry.diary

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ShareImageFile(val file:File,val width:Int,val height:Int)
internal data class SharePalette(val paper:Int,val top:Int,val ink:Int,val muted:Int,val accent:Int,val panel:Int){
    companion object{
        fun create(dark:Boolean,cover:Bitmap?):SharePalette{
            val seed=cover?.let{coverColors(it).firstOrNull()}?:0xffcb7e9e.toInt()
            val primary=androidx.compose.ui.graphics.Color(seed)
            val paper=if(dark)darkAlbumColor(primary,true)else albumPaper(primary,true)
            val top=if(dark)darkAlbumColor(primary,false)else albumPaper(primary,false)
            fun argb(c:androidx.compose.ui.graphics.Color)=c.toArgb()
            return SharePalette(argb(paper),argb(top),if(dark)0xfff3f0fa.toInt()else 0xff161619.toInt(),if(dark)0xffb9b3c1.toInt()else 0xff77727b.toInt(),if(dark)0xfffb8ab1.toInt()else 0xffcf315c.toInt(),if(dark)0x60151a24 else 0x9cffffff.toInt())
        }
    }
}

/** Deterministic print layout, independent of live Compose animations or scroll position. */
internal class ShareImagePainter(private val document:ShareDocument,private val palette:SharePalette,private val face:Typeface?,private val artwork:(String,Int)->Bitmap?,private val decoration:Bitmap?=null){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val images=linkedMapOf<String,Bitmap?>()
    private val dark=Color.luminance(palette.paper)<.5f
    private val accent=palette.accent
    private val coral=if(dark)0xffffbbbf.toInt()else 0xffff8f98.toInt()
    private fun alpha(color:Int,level:Int)=(color and 0x00ffffff) or (level.coerceIn(0,255) shl 24)
    private fun blend(a:Int,b:Int,f:Float)=androidx.core.graphics.ColorUtils.blendARGB(a,b,f)
    private fun image(id:String,edge:Int):Bitmap?=images.getOrPut("$id:$edge"){artwork(id,edge)}
    fun close(){images.values.filterNotNull().distinct().forEach{it.recycle()};images.clear()}
    private fun round(canvas:Canvas,box:RectF,radius:Float,color:Int){paint.shader=null;paint.alpha=255;paint.color=color;paint.style=Paint.Style.FILL;canvas.drawRoundRect(box,radius,radius,paint)}
    private fun text(canvas:Canvas,value:String,x:Float,y:Float,width:Float,size:Float,color:Int=palette.ink,bold:Boolean=false,lines:Int=1,font:Typeface?=null){
        textPaint.shader=null;textPaint.textSize=size;textPaint.color=color;textPaint.typeface=Typeface.create(font?:face?:Typeface.DEFAULT,if(bold)Typeface.BOLD else Typeface.NORMAL)
        val layout=StaticLayout.Builder.obtain(value,0,value.length,textPaint,width.toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        canvas.save();canvas.translate(x,y);layout.draw(canvas);canvas.restore()
    }
    private fun fitted(canvas:Canvas,value:String,x:Float,y:Float,width:Float,size:Float,color:Int=palette.ink,bold:Boolean=true,gradient:Boolean=false,font:Typeface?=null){
        textPaint.shader=null;textPaint.textSize=size;textPaint.typeface=Typeface.create(font?:face?:Typeface.DEFAULT,if(bold)Typeface.BOLD else Typeface.NORMAL)
        val fit=minOf(size,size*width/textPaint.measureText(value).coerceAtLeast(1f))
        textPaint.textSize=fit;textPaint.color=color
        if(gradient)textPaint.shader=LinearGradient(x,y,x+width,y+fit, intArrayOf(accent,coral,accent),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        val layout=StaticLayout.Builder.obtain(value,0,value.length,textPaint,width.toInt().coerceAtLeast(1)+1).setIncludePad(false).setMaxLines(1).build()
        canvas.save();canvas.translate(x,y);layout.draw(canvas);canvas.restore();textPaint.shader=null
    }
    private fun stroke(canvas:Canvas,path:Path,color:Int,width:Float){paint.shader=null;paint.alpha=255;paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=width;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND;canvas.drawPath(path,paint);paint.style=Paint.Style.FILL}
    private fun wave(canvas:Canvas,x:Float,y:Float,scale:Float,color:Int){
        listOf(3f,7f,13f,5f,10f,16f,8f,12f,4f).forEachIndexed{i,h->paint.shader=null;paint.color=color;paint.strokeWidth=1.2f*scale;paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(x+i*3.8f*scale,y-h*scale/2,x+i*3.8f*scale,y+h*scale/2,paint)}
    }
    private fun star(canvas:Canvas,x:Float,y:Float,r:Float,color:Int){
        stroke(canvas,Path().apply{moveTo(x-r,y);quadTo(x,y,x,y-r);quadTo(x,y,x+r,y);quadTo(x,y,x,y+r);quadTo(x,y,x-r,y)},color,.55f)
        stroke(canvas,Path().apply{moveTo(x-r*.6f,y-r*.8f);lineTo(x+r*.6f,y+r*.8f)},alpha(color,150),.35f)
    }
    private fun decor(canvas:Canvas,foreground:Boolean=false){
        val bitmap=decoration?:return
        fun draw(){paint.shader=null;paint.color=Color.WHITE;paint.alpha=if(dark)190 else 210;canvas.drawBitmap(bitmap,null,RectF(0f,0f,360f,480f),paint);paint.alpha=255}
        if(!foreground)draw()else{
            canvas.save();canvas.clipRect(0f,185f,14f,335f);draw();canvas.restore()
            canvas.save();canvas.clipRect(345f,130f,360f,335f);draw();canvas.restore()
        }
    }
    private fun background(canvas:Canvas){
        val base=blend(palette.paper,if(dark)0xff17141d.toInt()else 0xfffffdfc.toInt(),.86f)
        canvas.drawColor(base)
        fun glow(x:Float,y:Float,r:Float,color:Int){paint.shader=RadialGradient(x,y,r,intArrayOf(color,alpha(color,0)),null,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,360f,480f,paint);paint.shader=null}
        glow(332f,162f,240f,alpha(palette.top,if(dark)200 else 85));glow(6f,260f,145f,alpha(blend(accent,palette.top,.7f),if(dark)65 else 38));glow(350f,10f,110f,alpha(0xffaeb5f5.toInt(),if(dark)65 else 45))
        decor(canvas)
    }
    private fun art(canvas:Canvas,id:String?,box:RectF,radius:Float,edge:Int=320,border:Boolean=true){
        if(border){paint.shader=null;paint.color=alpha(Color.WHITE,if(dark)110 else 255);paint.setShadowLayer(4f,0f,2f,alpha(0xff7b527b.toInt(),35));canvas.drawRoundRect(box,radius,radius,paint);paint.clearShadowLayer()}
        val path=Path().apply{addRoundRect(box,radius,radius,Path.Direction.CW)}
        canvas.save();canvas.clipPath(path);round(canvas,box,radius,blend(palette.top,palette.paper,.5f))
        val bitmap=id?.let{image(it,edge)}
        if(bitmap!=null){
            val scale=maxOf(box.width()/bitmap.width,box.height()/bitmap.height);val w=bitmap.width*scale;val h=bitmap.height*scale
            paint.color=Color.WHITE;paint.shader=null;paint.alpha=255
            canvas.drawBitmap(bitmap,null,RectF(box.centerX()-w/2,box.top-(h-box.height())*.35f,box.centerX()+w/2,box.top-(h-box.height())*.35f+h),paint)
        }else{
            paint.color=alpha(accent,120);paint.style=Paint.Style.STROKE;paint.strokeWidth=.9f;canvas.drawCircle(box.centerX(),box.centerY(),box.width()*.23f,paint);canvas.drawCircle(box.centerX(),box.centerY(),box.width()*.055f,paint);paint.style=Paint.Style.FILL
        }
        canvas.restore()
        if(border){paint.shader=null;paint.color=alpha(Color.WHITE,if(dark)130 else 245);paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f;canvas.drawRoundRect(box,radius,radius,paint);paint.style=Paint.Style.FILL}
    }
    private fun vinyl(canvas:Canvas){
        val cx=335f;val cy=307f;val r=33f;val color=blend(palette.top,0xffb6a0ed.toInt(),.55f)
        paint.shader=RadialGradient(cx-10,cy-10,r*1.8f,intArrayOf(alpha(color,45),alpha(color,160)),null,Shader.TileMode.CLAMP);canvas.drawCircle(cx,cy,r,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.35f;paint.color=alpha(Color.WHITE,150)
        for(i in 12..31 step 3)canvas.drawCircle(cx,cy,i.toFloat(),paint)
        paint.style=Paint.Style.FILL;paint.color=alpha(Color.WHITE,200);canvas.drawCircle(cx,cy,10f,paint);paint.color=alpha(accent,80);canvas.drawCircle(cx,cy,1.1f,paint)
    }
    private fun feature(canvas:Canvas,feature:ShareFeature?,box:RectF,angle:Float){
        canvas.save();canvas.rotate(angle,box.centerX(),box.centerY());art(canvas,feature?.id,box,9f,if(box.width()>120)720 else 320)
        if(feature!=null&&box.width()>120){
            canvas.save();canvas.clipPath(Path().apply{addRoundRect(box,9f,9f,Path.Direction.CW)})
            paint.shader=LinearGradient(0f,box.bottom-25f,0f,box.bottom,intArrayOf(Color.TRANSPARENT,0x66000000),null,Shader.TileMode.CLAMP);canvas.drawRect(box,paint);paint.shader=null
            text(canvas,feature.title,box.left+8f,box.bottom-22f,box.width()-16f,10f,Color.WHITE,true)
            text(canvas,feature.artist,box.left+8f,box.bottom-10f,box.width()-16f,6.5f,Color.WHITE)
            canvas.restore()
        }
        canvas.restore()
    }
    private fun hero(canvas:Canvas){
        val album=document.heroAlbum
        val artistId="artist:${document.heroArtist}"
        val heroId=artistId.takeIf{document.heroArtist.isNotBlank()&&image(it,720)!=null}?:album?.id?:document.covers.firstOrNull()
        val photo=RectF(188f,9f,352f,219f)
        canvas.save();canvas.rotate(5f,photo.centerX(),photo.centerY());art(canvas,heroId,photo,19f,720)
        canvas.save();canvas.clipPath(Path().apply{addRoundRect(photo,19f,19f,Path.Direction.CW)})
        paint.shader=LinearGradient(0f,155f,0f,219f,intArrayOf(Color.TRANSPARENT,0x70000000),null,Shader.TileMode.CLAMP);canvas.drawRect(photo,paint);paint.shader=null;canvas.restore();canvas.restore()
        // Photo content is real cached metadata; neither names nor statistics are baked into the art.
        if(album!=null){art(canvas,album.id,RectF(190f,167f,238f,215f),9f,320);text(canvas,document.heroArtist.ifBlank{album.artist},245f,176f,95f,8.5f,Color.WHITE,true);text(canvas,album.title,245f,188f,96f,11f,Color.WHITE,true,2)}
        stroke(canvas,Path().apply{moveTo(324f,67f);cubicTo(318f,53f,310f,52f,314f,63f);cubicTo(315f,69f,324f,75f,324f,75f);cubicTo(326f,69f,336f,60f,332f,57f);cubicTo(329f,55f,326f,61f,324f,67f)},alpha(Color.WHITE,190),.6f)
        text(canvas,document.heroArtist,197f,87f,125f,12f,alpha(Color.WHITE,210),font=Typeface.create("cursive",Typeface.NORMAL))
    }
    private fun summary(canvas:Canvas){
        fitted(canvas,if(document.options.signature)"听歌日记"else"音乐记忆",18f,10f,72f,19f,accent)
        wave(canvas,96f,19f,.55f,accent)
        text(canvas,document.caption,18f,32f,164f,9.5f,palette.muted,true)
        val title=when(document.options.range){DiaryRange.DAY->"这一天的声音";DiaryRange.WEEK->"这一周的声音";DiaryRange.MONTH->"这一月的声音";DiaryRange.YEAR->"这一年的声音"}
        fitted(canvas,title,17f,48f,174f,29f)
        stroke(canvas,Path().apply{moveTo(20f,84f);cubicTo(47f,76f,87f,72f,64f,81f);quadTo(80f,80f,122f,80f)},alpha(coral,125),1.3f)
        val overview=document.sections.firstOrNull{it.module==ShareModule.OVERVIEW}
        if(overview!=null){
            val stats=overview.rows;val minutes=stats.getOrNull(1)?.value?:"0"
            fitted(canvas,minutes,14f,85f,132f,80f,accent,gradient=true,font=Typeface.create("sans-serif-condensed",Typeface.BOLD))
            fitted(canvas,"分钟",149f,131f,38f,18f,accent)
            stroke(canvas,Path().apply{moveTo(69f,157f);lineTo(119f,146f);cubicTo(127f,144f,114f,158f,137f,152f);lineTo(164f,145f)},alpha(coral,150),.65f)
            round(canvas,RectF(14f,163f,173f,208f),11f,if(dark)0xb52a2231.toInt()else 0xc4f9eef7.toInt())
            val selected=listOf(stats.getOrNull(0),stats.getOrNull(2),stats.getOrNull(3))
            selected.forEachIndexed{i,row->val x=25f+i*53f;fitted(canvas,row?.value?:"0",x,166f,42f,23f,accent,gradient=true);text(canvas,when(i){0->"次播放";1->"首歌曲";else->"位歌手"},x+2,193f,44f,9f,palette.muted)}
            paint.color=alpha(accent,85);paint.strokeWidth=.35f;for(x in listOf(69f,123f))canvas.drawLine(x,173f,x,199f,paint)
        }else{
            val word=when(document.options.range){DiaryRange.DAY->"DAY";DiaryRange.WEEK->"WEEK";DiaryRange.MONTH->"MONTH";DiaryRange.YEAR->"YEAR"}
            fitted(canvas,word,18f,104f,148f,55f,accent,gradient=true)
            text(canvas,"属于我的音乐记忆",21f,174f,150f,12f,palette.muted)
        }
        star(canvas,168f,100f,5f,alpha(accent,160))
    }
    private fun collage(canvas:Canvas){
        vinyl(canvas)
        feature(canvas,document.features.getOrNull(0),RectF(18f,214f,163f,326f),-4f)
        feature(canvas,document.features.getOrNull(1),RectF(166f,224f,257f,315f),3f)
        feature(canvas,document.features.getOrNull(2),RectF(263f,230f,330f,297f),-4f)
        canvas.save();canvas.rotate(-10f,300f,312f);text(canvas,"Good Music",266f,302f,77f,10.5f,accent,font=Typeface.create("cursive",Typeface.NORMAL));text(canvas,"Good Day ♡",274f,315f,72f,10.5f,accent,font=Typeface.create("cursive",Typeface.NORMAL));canvas.restore()
        star(canvas,8f,213f,5f,alpha(accent,190));star(canvas,190f,325f,4f,alpha(accent,190));decor(canvas,true)
    }
    private fun heading(canvas:Canvas,section:ShareSection){
        val period=when(document.options.range){DiaryRange.DAY->"今日";DiaryRange.WEEK->"本周";DiaryRange.MONTH->"本月";DiaryRange.YEAR->"本年"}
        val title=when(section.module){ShareModule.SONGS->"${period}常听";ShareModule.ALBUMS->"${period}专辑";ShareModule.ARTISTS->"${period}歌手";ShareModule.TREND->"收听节奏";ShareModule.HABITS->"收听习惯";ShareModule.STYLES->"音乐风格";else->"音乐留下的瞬间"}
        text(canvas,title,17f,337f,160f,16f,bold=true)
        val start=if(title.length>6)157f else 84f
        paint.color=alpha(accent,160);paint.strokeWidth=.45f;canvas.drawLine(start,346f,228f,346f,paint);canvas.drawLine(273f,346f,344f,346f,paint);wave(canvas,234f,346f,1f,alpha(coral,210))
    }
    private fun body(canvas:Canvas,section:ShareSection){
        heading(canvas,section)
        val rowPanel=if(dark)0xd02d2434.toInt()else 0xddfaedf5.toInt()
        when(section.module){
            ShareModule.SONGS,ShareModule.ALBUMS,ShareModule.ARTISTS->section.rows.forEachIndexed{i,row->
                val y=356f+i*34f;round(canvas,RectF(16f,y,344f,y+32.5f),9f,rowPanel)
                fitted(canvas,"${section.startRank+i}".padStart(2,'0'),25f,y+6.5f,24f,15f,accent,gradient=true)
                art(canvas,row.image,RectF(56f,y+3f,82f,y+29f),if(section.module==ShareModule.ARTISTS)13f else 4f,160,false)
                text(canvas,row.title,91f,y+5f,202f,10.5f,bold=true)
                text(canvas,row.subtitle,91f,y+18f,199f,8f,palette.muted)
                val match=Regex("(.+?)(次|分钟|小时|秒)$").matchEntire(row.value)
                if(match!=null){
                    val number=match.groupValues[1];val unit=match.groupValues[2];val unitWidth=if(unit.length>1)14f else 8f;val unitX=339f-unitWidth
                    textPaint.typeface=Typeface.create(face?:Typeface.DEFAULT,Typeface.BOLD);textPaint.textSize=17f
                    val numberWidth=minOf(33f,textPaint.measureText(number).coerceAtLeast(1f))
                    fitted(canvas,number,unitX-3-numberWidth,y+6.5f,numberWidth,17f,accent,gradient=true);text(canvas,unit,unitX,y+15f,unitWidth,if(unit.length>1)6.5f else 7f,accent)
                }else text(canvas,row.value,302f,y+12f,36f,9f,accent,true)
            }
            ShareModule.TREND->{
                round(canvas,RectF(16f,356f,344f,457f),11f,rowPanel)
                val n=section.values.size.coerceAtLeast(1);val step=300f/n;val max=(section.values.maxOrNull()?:0).coerceAtLeast(1)
                text(canvas,"实际聆听 · 分钟",26f,362f,150f,7f,palette.muted)
                section.values.forEachIndexed{i,v->val x=27f+i*step;val h=maxOf(1.5f,58f*v/max);round(canvas,RectF(x+step*.2f,438f-h,x+step*.8f,438f),3f,if(v>0)accent else alpha(accent,40));text(canvas,section.labels.getOrElse(i){""},x+step*.12f,443f,step,if(n>10)5.7f else 7f,palette.muted)}
            }
            ShareModule.HABITS->{
                round(canvas,RectF(16f,356f,344f,457f),11f,rowPanel)
                section.rows.forEachIndexed{i,row->val y=365f+i*22f;text(canvas,row.title,28f,y,67f,8.5f,palette.muted);text(canvas,row.value,99f,y,229f,9f,bold=i==2)}
            }
            ShareModule.STYLES->{
                round(canvas,RectF(16f,356f,344f,457f),11f,rowPanel)
                section.rows.forEachIndexed{i,row->val y=365f+i*27f;text(canvas,row.title,28f,y,240f,9f,bold=true);text(canvas,row.value,303f,y,30f,8.5f,accent);round(canvas,RectF(28f,y+16f,331f,y+19f),2f,alpha(accent,24));if(row.fraction>0)round(canvas,RectF(28f,y+16f,28f+303f*row.fraction,y+19f),2f,accent)}
                text(canvas,"按已匹配曲风 · 占本期总聆听时长",28f,449f,296f,5.5f,palette.muted)
            }
            ShareModule.OVERVIEW->{
                round(canvas,RectF(16f,358f,344f,456f),12f,rowPanel)
                fitted(canvas,"每一次播放",31f,372f,291f,22f,accent)
                fitted(canvas,"都在收藏生活的片刻",31f,405f,291f,18f)
                text(canvas,"这份音乐记忆，只属于你。",33f,437f,290f,8f,palette.muted)
            }
        }
    }
    private fun footer(canvas:Canvas,index:Int,total:Int){
        paint.shader=null;paint.color=alpha(accent,180);paint.strokeWidth=.45f;canvas.drawLine(14f,466f,140f,466f,paint);canvas.drawLine(225f,466f,346f,466f,paint)
        wave(canvas,145f,466f,.35f,alpha(accent,170));wave(canvas,211f,466f,.35f,alpha(accent,170))
        text(canvas,if(document.options.signature)"听歌日记"else"音乐记忆",163f,461.5f,49f,9f,accent,true)
        if(total>1)text(canvas,"${index+1}/$total",329f,474f,25f,5f,palette.muted)
        if(document.demo){round(canvas,RectF(278f,4f,350f,15f),5f,0xd9ffffff.toInt());text(canvas,"示例数据",293f,6f,50f,6.5f,0xff77727b.toInt())}
    }
    fun draw(page:SharePage,index:Int,total:Int):Bitmap{
        val bitmap=Bitmap.createBitmap(1080,1440,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.scale(3f,3f)
        background(canvas);hero(canvas);summary(canvas);collage(canvas);page.sections.firstOrNull()?.let{body(canvas,it)};footer(canvas,index,total)
        return bitmap
    }
}

internal suspend fun renderShareImages(cacheDir:File,document:ShareDocument,dark:Boolean,typeface:Typeface?=null,decoration:Bitmap?=null,artwork:(String,Int)->Bitmap?):List<ShareImageFile>{
    val directory=File(cacheDir,"share-images").apply{mkdirs()};val token=UUID.randomUUID().toString();val files=mutableListOf<ShareImageFile>()
    directory.listFiles()?.filter{it.isFile&&it.name.endsWith(".png")&&it.lastModified()<System.currentTimeMillis()-48*3600000L}?.forEach{it.delete()}
    val cover=(document.heroAlbum?.id?:document.covers.firstOrNull())?.let{artwork(it,160)}
    val palette=try{SharePalette.create(dark,cover)}finally{cover?.recycle()}
    val painter=ShareImagePainter(document,palette,typeface,artwork,decoration)
    try{
        val pages=sharePages(document)
        for((index,page)in pages.withIndex()){
            currentCoroutineContext().ensureActive()
            val file=File(directory,"diary-$token-${index+1}.png")
            try{val bitmap=painter.draw(page,index,pages.size);try{file.outputStream().buffered(65536).use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)){"图片编码失败"}}}finally{bitmap.recycle()}}catch(t:Throwable){file.delete();throw t}
            files+=ShareImageFile(file,1080,page.height*3)
        }
        return files
    }catch(t:Throwable){files.forEach{it.file.delete()};throw t}finally{painter.close()}
}
