package ru.vorontsov.kurs

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.abs

object Fmt {
    /** decimals = -1 — авто: больше знаков для мелких значений. */
    fun num(v: Double, decimals: Int, grouping: Boolean): String {
        val d = if (decimals >= 0) decimals else autoDec(v)
        val sym = DecimalFormatSymbols().apply { decimalSeparator = ','; groupingSeparator = '\u00A0' }
        val df = DecimalFormat(if (d == 0) "#,##0" else "#,##0." + "#".repeat(d), sym)
        df.isGroupingUsed = grouping; df.roundingMode = RoundingMode.HALF_UP
        if (decimals >= 0) { df.minimumFractionDigits = d }
        return df.format(BigDecimal(v).setScale(d, RoundingMode.HALF_UP))
    }
    fun autoDec(v: Double): Int { val a = abs(v); return when { a == 0.0 -> 2; a >= 1 -> 2; a >= 0.01 -> 4; a >= 0.0001 -> 6; else -> 8 } }
}
