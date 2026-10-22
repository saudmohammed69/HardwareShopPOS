package com.hardwareshop.pos.util

object ReceiptFooter {
    const val DEFAULT="Thank You!\nVisit Again Soon"
    fun validate(value:String):String {
        require(value.codePointCount(0,value.length)<=300) {"Footer must be at most 300 characters"}
        require(value.lines().size<=6) {"Footer supports up to six lines"}
        val clean=value.lines().joinToString("\n") {ReceiptLayout.clean(it)}.trim()
        require(clean.isNotBlank()) {"Enter a footer or reset to the default"}
        return clean
    }
    fun lines(value:String)=validate(value).lines().flatMap(ReceiptLayout::wrap)
}
