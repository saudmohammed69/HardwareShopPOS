package com.hardwareshop.pos.util

/** Paisa only: never round payment input or turn excess money into revenue. */
data class PaymentSplit(val cashPaisa: Long, val upiPaisa: Long) {
    fun total(): Long { require(cashPaisa>=0 && upiPaisa>=0) { "Payment amounts cannot be negative" }; return Math.addExact(cashPaisa,upiPaisa) }
    fun validate(billTotal:Long) { require(billTotal>=0 && total()==billTotal) { "Cash + UPI must exactly equal the bill total" } }
    fun remaining(billTotal:Long):Long = Math.subtractExact(billTotal,total())
}
