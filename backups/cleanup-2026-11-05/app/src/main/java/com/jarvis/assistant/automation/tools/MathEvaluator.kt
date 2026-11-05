package com.jarvis.assistant.automation.tools

import kotlin.math.*

/**
 * Safe mathematical expression evaluator for arithmetic, powers, and percentages.
 */
object MathEvaluator {

    fun evaluate(expression: String): Double {
        val clean = expression.replace(" ", "").replace("x", "*").replace("X", "*").replace("×", "*").replace("÷", "/")
        val result = Parser(clean).parse()
        if (result.isNaN() || result.isInfinite()) throw ArithmeticException("Result is not a finite number")
        return result
    }

    private class Parser(private val expr: String) {
        private var pos = -1
        private var ch = -1

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < expr.length) throw IllegalArgumentException("Unexpected char: " + expr[pos])
            return x
        }

        private fun nextChar() {
            ch = if (++pos < expr.length) expr[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        x /= divisor
                    }
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()

            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                if (!eat(')'.code)) throw IllegalArgumentException("Missing ')'")
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = expr.substring(startPos, pos).toDouble()
            } else if (ch in 'a'.code..'z'.code) {
                while (ch in 'a'.code..'z'.code) nextChar()
                val func = expr.substring(startPos, pos)
                x = parseFactor()
                x = when (func) {
                    "sqrt" -> sqrt(x)
                    "sin" -> sin(Math.toRadians(x))
                    "cos" -> cos(Math.toRadians(x))
                    "tan" -> tan(Math.toRadians(x))
                    "abs" -> abs(x)
                    "log" -> log10(x)
                    "ln" -> ln(x)
                    else -> throw IllegalArgumentException("Unknown function: $func")
                }
            } else {
                throw IllegalArgumentException("Unexpected char: " + if (ch == -1) "end of input" else ch.toChar().toString())
            }

            if (eat('^'.code)) x = x.pow(parseFactor())
            if (eat('%'.code)) x /= 100.0

            return x
        }
    }
}
