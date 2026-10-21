package ru.vorontsov.kurs

/** Простое вычисление выражений: + - * / %, скобки. Возвращает null при ошибке. */
object Calc {
    fun eval(src: String): Double? {
        val s = src.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.').replace(" ", "")
        if (s.isEmpty()) return 0.0
        var p = 0
        fun peek() = if (p < s.length) s[p] else '\u0000'
        lateinit var expr: () -> Double?
        fun num(): Double? {
            if (peek() == '(') { p++; val v = expr(); if (peek() == ')') p++; return v }
            if (peek() == '-') { p++; return num()?.let { -it } }
            val st = p
            while (peek().isDigit() || peek() == '.') p++
            var v = s.substring(st, p).toDoubleOrNull() ?: return null
            if (peek() == '%') { p++; v /= 100 }
            return v
        }
        fun term(): Double? {
            var v = num() ?: return null
            while (peek() == '*' || peek() == '/') {
                val op = s[p++]; val r = num() ?: return v
                v = if (op == '*') v * r else if (r == 0.0) return null else v / r
            }
            return v
        }
        expr = {
            var v = term()
            while (v != null && (peek() == '+' || peek() == '-')) {
                val op = s[p++]; val r = term() ?: break
                v = if (op == '+') v + r else v - r
            }
            v
        }
        val r = expr()
        return if (r == null || r.isNaN() || r.isInfinite()) null else r
    }
    fun hasOp(s: String) = s.drop(1).any { it in "+-−*×/÷%" }
}
