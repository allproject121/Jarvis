package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes mathematical symbols and operations into spoken words.
 * e.g., "2+2" -> "2 plus 2", "16:9" -> "16 by 9", "10x5" -> "10 times 5", "5%" -> "5 percent".
 */
class MathExpressionNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val TIMES = Pattern.compile("""(?<=\d)[xX*×]+(?=\d)""")
        private val RATIO = Pattern.compile("""(?<=\d):(?=\d)""")
        private val ARROW = Pattern.compile("""(->|=>|→)""")
        private val PLUS = Pattern.compile("""(?<=\d)\s*\+\s*(?=\d)""")
        private val MINUS = Pattern.compile("""(?<=\d|\s)\s*(-|−)\s*(?=\d|\s)""")
        private val EQUALS = Pattern.compile("""(?<=\d)\s*=\s*(?=\d)""")
        private val DIV = Pattern.compile("""(?<=\p{L})\s*/\s*(?=\p{L})""")
        private val APPROX = Pattern.compile("""≈|~|~\s*=|≈\s*=""")
        private val GE = Pattern.compile("""≥|>=""")
        private val LE = Pattern.compile("""≤|<=""")
        private val GT = Pattern.compile(""">""")
        private val LT = Pattern.compile("""<""")
        private val PERCENT = Pattern.compile("""%""")
        private val DEGREE = Pattern.compile("""°""")
    }

    private fun replaceAll(input: String): String {
        var text = input
        text = TIMES.matcher(text).replaceAll(" times ")
        text = RATIO.matcher(text).replaceAll(" by ")
        text = ARROW.matcher(text).replaceAll(" to ")
        text = PLUS.matcher(text).replaceAll(" plus ")
        text = MINUS.matcher(text).replaceAll(" minus ")
        text = EQUALS.matcher(text).replaceAll(" equals ")
        text = DIV.matcher(text).replaceAll(" divided by ")
        text = APPROX.matcher(text).replaceAll(" approximately ")
        text = GE.matcher(text).replaceAll(" greater than or equal to ")
        text = LE.matcher(text).replaceAll(" less than or equal to ")
        text = GT.matcher(text).replaceAll(" greater than ")
        text = LT.matcher(text).replaceAll(" less than ")
        text = PERCENT.matcher(text).replaceAll(" percent ")
        text = DEGREE.matcher(text).replaceAll(" degrees ")
        return text
    }
}
