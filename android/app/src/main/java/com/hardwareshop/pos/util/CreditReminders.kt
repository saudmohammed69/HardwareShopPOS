package com.hardwareshop.pos.util

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** User-entered terms only; the ledger remains the sole source of the outstanding balance. */
object CreditReminders {
    enum class DueState { NO_BALANCE, NO_DATE, FUTURE, TODAY, OVERDUE }
    fun parseDate(value:String):LocalDate? {
        if(value.isBlank())return null
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {"Use a due date in YYYY-MM-DD format"}
        return try {LocalDate.parse(value)}catch(_:DateTimeParseException){throw IllegalArgumentException("Enter a valid due date")}
    }
    fun state(balancePaisa:Long,date:LocalDate?,today:LocalDate):DueState = when {
        balancePaisa<=0->DueState.NO_BALANCE
        date==null->DueState.NO_DATE
        date<today->DueState.OVERDUE
        date==today->DueState.TODAY
        else->DueState.FUTURE
    }
    fun message(name:String,shop:String,balancePaisa:Long,date:LocalDate?,today:LocalDate):String {
        require(balancePaisa>0) {"This customer has no outstanding balance to remind"}
        val status=state(balancePaisa,date,today)
        val due=when(status) {
            DueState.OVERDUE->" The agreed due date was $date."
            DueState.TODAY->" Payment is due today ($date)."
            DueState.FUTURE->" The saved due date is $date."
            else->""
        }
        return "Hello ${name.trim().ifBlank {"customer"}}, your current outstanding balance at ${shop.trim().ifBlank {"our shop"}} is ₹${CalculationEngine.formatRupeesRaw(balancePaisa)}.$due Please arrange payment when convenient or contact us if you need to discuss it. Thank you."
    }
}
