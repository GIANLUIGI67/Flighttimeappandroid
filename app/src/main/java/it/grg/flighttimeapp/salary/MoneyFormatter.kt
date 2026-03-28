package it.grg.flighttimeapp.salary

import java.text.NumberFormat
import java.util.Locale

object MoneyFormatter {
    fun format(value: Double, currencyCode: String): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
        formatter.currency = java.util.Currency.getInstance(currencyCode)
        return formatter.format(value)
    }
}
