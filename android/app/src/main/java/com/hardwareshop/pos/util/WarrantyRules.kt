package com.hardwareshop.pos.util

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** Calendar dates are used rather than elapsed milliseconds, including leap years and month ends. */
object WarrantyRules {
    fun date(text: String, label: String = "date"): LocalDate {
        val value = text.trim()
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Enter $label as yyyy-MM-dd" }
        return try {
            LocalDate.parse(value).also { require(it.year in 1900..9999) { "Enter a valid $label" } }
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Enter a valid $label as yyyy-MM-dd")
        }
    }

    fun months(text: String): Int = text.trim().toIntOrNull()?.also {
        require(it in 1..120) { "Warranty must be 1 to 120 months" }
    } ?: throw IllegalArgumentException("Enter warranty months as a whole number")

    fun expiry(saleDate: String, months: Int): LocalDate {
        require(months in 1..120) { "Warranty must be 1 to 120 months" }
        return date(saleDate, "sale date").plusMonths(months.toLong()).also {
            require(it.year <= 9999) { "Warranty expiry is outside the supported date range" }
        }
    }

    /** Coverage includes the expiry date. This is a recorded period, not a warranty eligibility decision. */
    fun expired(saleDate: String, months: Int, today: LocalDate = LocalDate.now()): Boolean =
        today.isAfter(expiry(saleDate, months))

    fun serial(text: String): String = text.trim().also {
        require(it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl)) {
            "Enter a serial number of up to 100 characters"
        }
    }

    fun serialKey(text: String): String = serial(text).lowercase(Locale.ROOT)

    fun duplicate(productId: Long, serial: String, others: Iterable<Pair<Long, String>>): Boolean {
        val key = serialKey(serial)
        return others.any { it.first == productId && serialKey(it.second) == key }
    }

    fun phone(text: String): String = text.trim().also {
        require(it.isEmpty() || (it.length <= 25 && it.count(Char::isDigit) in 7..15 &&
            it.all { char -> char.isDigit() || char in "+ -()" })) { "Enter a valid phone number or leave it blank" }
    }

    fun saleDate(text: String, today: LocalDate = LocalDate.now()): String = date(text, "sale date").also {
        require(!it.isAfter(today)) { "Sale date cannot be in the future" }
    }.toString()

    fun serviceDate(text: String, saleDate: String, today: LocalDate = LocalDate.now()): String =
        date(text, "service date").also {
            require(!it.isBefore(date(saleDate, "sale date"))) { "Service date cannot be before the sale" }
            require(!it.isAfter(today)) { "Service date cannot be in the future" }
        }.toString()
}
