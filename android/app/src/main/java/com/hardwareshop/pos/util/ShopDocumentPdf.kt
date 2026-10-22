package com.hardwareshop.pos.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Paginated shop documents; generated files are shared only through an explicit Android chooser. */
object ShopDocumentPdf {
    data class Text(val value:String,val bold:Boolean=false)
    fun create(context:Context,title:String,lines:List<Text>,prefix:String):File {
        require(prefix.matches(Regex("[a-z-]{1,30}"))) {"Invalid document name"}
        require(lines.size<=20000 && lines.all {it.value.length<=10000} && lines.sumOf {it.value.length.toLong()}<=2000000) {
            "PDF is too large. Choose a smaller reporting period or share the CSV."
        }
        val directory=File(context.cacheDir,"shop-documents")
        check(directory.exists() || directory.mkdirs()) {"Could not create PDF folder"}
        val file=File(directory,"$prefix-${UUID.randomUUID()}.pdf")
        val document=PdfDocument()
        val normal=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=11f}
        val bold=Paint(normal).apply {typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)}
        var page:PdfDocument.Page?=null
        var pageNumber=0
        var y=42f
        fun newPage() {
            require(pageNumber<500) {"PDF exceeds 500 pages. Choose a smaller reporting period or share the CSV."}
            page?.let(document::finishPage);page=null;pageNumber++
            page=document.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create())
            page!!.canvas.drawText(title.take(70),36f,36f,bold)
            page!!.canvas.drawText("Page $pageNumber",490f,36f,normal)
            y=62f
        }
        fun write(value:String,paint:Paint) {
            if(page==null || y>790f)newPage()
            page!!.canvas.drawText(value,36f,y,paint);y+=17f
        }
        try {
            lines.forEach {line->
                val brush=if(line.bold)bold else normal
                line.value.split('\n').forEach {source->
                    var remaining=ReceiptLayout.clean(source)
                    if(remaining.isEmpty())write("",brush)
                    while(remaining.isNotEmpty()) {
                        var count=brush.breakText(remaining,true,520f,null).coerceAtLeast(1)
                        if(count<remaining.length) {
                            val space=remaining.lastIndexOf(' ',count-1)
                            if(space>0)count=space
                            if(count>0 && count<remaining.length && Character.isHighSurrogate(remaining[count-1]))count--
                            if(count==0)count=2.coerceAtMost(remaining.length)
                        }
                        write(remaining.take(count),brush);remaining=remaining.drop(count).trimStart()
                    }
                }
            }
            if(page==null)newPage()
            page?.let(document::finishPage);page=null
            file.outputStream().use(document::writeTo)
        }catch(e:Exception){file.delete();throw e}
        finally {page?.let(document::finishPage);document.close()}
        return file
    }
    fun share(context:Context,file:File,title:String) {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_SUBJECT,title)
            clipData=android.content.ClipData.newRawUri(title,uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent,title))
    }
}
