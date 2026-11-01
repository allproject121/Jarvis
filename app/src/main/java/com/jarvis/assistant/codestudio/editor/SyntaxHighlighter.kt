package com.jarvis.assistant.codestudio.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

enum class CodeLanguage(val label: String) {
    HTML("HTML"), CSS("CSS"), JS("JavaScript"), TS("TypeScript"), KOTLIN("Kotlin"), JAVA("Java"),
    PYTHON("Python"), JSON("JSON"), XML("XML"), MARKDOWN("Markdown"), SHELL("Shell"), PLAIN("Text");

    companion object {
        fun fromFileName(name: String): CodeLanguage = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "vue", "svelte" -> HTML
            "css", "scss", "less" -> CSS
            "js", "mjs", "cjs", "jsx" -> JS
            "ts", "tsx" -> TS
            "kt", "kts", "gradle" -> KOTLIN
            "java" -> JAVA
            "py" -> PYTHON
            "json" -> JSON
            "xml", "svg" -> XML
            "md", "markdown" -> MARKDOWN
            "sh", "bash" -> SHELL
            else -> PLAIN
        }
    }
}

data class SyntaxColors(
    val keyword: Color, val string: Color, val comment: Color, val number: Color,
    val tag: Color, val attr: Color, val function: Color, val type: Color, val punctuation: Color,
    val searchMatch: Color, val currentMatch: Color
) {
    companion object {
        val Dark = SyntaxColors(
            keyword = Color(0xFFC792EA), string = Color(0xFFC3E88D), comment = Color(0xFF6A7A8C), number = Color(0xFFF78C6C),
            tag = Color(0xFFFF5370), attr = Color(0xFFFFCB6B), function = Color(0xFF82AAFF), type = Color(0xFF89DDFF),
            punctuation = Color(0xFF89DDFF), searchMatch = Color(0x55FFD54F), currentMatch = Color(0xAAFF9800)
        )
        val Light = SyntaxColors(
            keyword = Color(0xFF7B1FA2), string = Color(0xFF2E7D32), comment = Color(0xFF8A8F98), number = Color(0xFFD84315),
            tag = Color(0xFFC62828), attr = Color(0xFFB26A00), function = Color(0xFF1565C0), type = Color(0xFF00838F),
            punctuation = Color(0xFF455A64), searchMatch = Color(0x66FFEB3B), currentMatch = Color(0xAAFFA726)
        )
    }
}

/** Regex-based highlighter. Rules are applied in order; earlier rules (comments/strings) win. */
object SyntaxHighlighter {

    private const val MAX_HIGHLIGHT_CHARS = 120_000

    private enum class Kind { KEYWORD, STRING, COMMENT, NUMBER, TAG, ATTR, FUNCTION, TYPE, PUNCT }
    private class Rule(val regex: Regex, val kind: Kind, val group: Int = 0)

    private fun kw(words: String) = Regex("\\b(?:" + words.trim().split(Regex("\\s+")).joinToString("|") + ")\\b")

    private val C_COMMENTS = listOf(
        Rule(Regex("/\\*[\\s\\S]*?(?:\\*/|$)"), Kind.COMMENT),
        Rule(Regex("//[^\\n]*"), Kind.COMMENT)
    )
    private val STRINGS = Rule(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?"), Kind.STRING)
    private val NUMBERS = Rule(Regex("\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)[fFlLdD]?\\b"), Kind.NUMBER)
    private val FUNCS = Rule(Regex("\\b([A-Za-z_$][\\w$]*)\\s*(?=\\()"), Kind.FUNCTION, 1)
    private val TYPES = Rule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), Kind.TYPE)

    private val JS_KW = kw("""break case catch class const continue debugger default delete do else export extends finally for function
        if import in instanceof let new return super switch this throw try typeof var void while with yield async await of static get set
        true false null undefined NaN interface type enum implements public private protected readonly as from""")
    private val KT_KW = kw("""package import class interface object fun val var if else when for while do return break continue throw try catch
        finally in is as by this super null true false data sealed enum open abstract override private protected public internal companion
        init constructor suspend inline reified lateinit const vararg typealias annotation where get set out""")
    private val JAVA_KW = kw("""abstract assert boolean break byte case catch char class const continue default do double else enum extends final
        finally float for goto if implements import instanceof int interface long native new package private protected public return short
        static strictfp super switch synchronized this throw throws transient try void volatile while true false null var record""")
    private val PY_KW = kw("""False None True and as assert async await break class continue def del elif else except finally for from global
        if import in is lambda nonlocal not or pass raise return try while with yield self print""")
    private val SH_KW = kw("if then else elif fi for while do done case esac function in echo export local return cd ls pwd")
    private val CSS_AT = Rule(Regex("@[\\w-]+"), Kind.KEYWORD)

    private fun rulesFor(lang: CodeLanguage): List<Rule> = when (lang) {
        CodeLanguage.JS, CodeLanguage.TS -> C_COMMENTS + listOf(
            Rule(Regex("`(?:\\\\.|[^`\\\\])*`?"), Kind.STRING), STRINGS, Rule(JS_KW, Kind.KEYWORD), NUMBERS, FUNCS, TYPES
        )
        CodeLanguage.KOTLIN -> C_COMMENTS + listOf(
            Rule(Regex("\"\"\"[\\s\\S]*?(?:\"\"\"|$)"), Kind.STRING), STRINGS, Rule(Regex("@[A-Za-z_]\\w*"), Kind.ATTR),
            Rule(KT_KW, Kind.KEYWORD), NUMBERS, FUNCS, TYPES
        )
        CodeLanguage.JAVA -> C_COMMENTS + listOf(
            STRINGS, Rule(Regex("@[A-Za-z_]\\w*"), Kind.ATTR), Rule(JAVA_KW, Kind.KEYWORD), NUMBERS, FUNCS, TYPES
        )
        CodeLanguage.PYTHON -> listOf(
            Rule(Regex("(\"\"\"|''')[\\s\\S]*?(?:\\1|$)"), Kind.STRING), Rule(Regex("#[^\\n]*"), Kind.COMMENT), STRINGS,
            Rule(Regex("@[A-Za-z_][\\w.]*"), Kind.ATTR), Rule(PY_KW, Kind.KEYWORD), NUMBERS, FUNCS, TYPES
        )
        CodeLanguage.CSS -> listOf(
            Rule(Regex("/\\*[\\s\\S]*?(?:\\*/|$)"), Kind.COMMENT), STRINGS, CSS_AT,
            Rule(Regex("([\\w-]+)\\s*(?=:[^{};]*;)"), Kind.ATTR, 1),
            Rule(Regex("#[0-9a-fA-F]{3,8}\\b"), Kind.NUMBER),
            Rule(Regex("-?\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|vh|vw|s|ms|deg|fr)?\\b"), Kind.NUMBER),
            Rule(Regex("(^|[\\s,}])([.#]?[A-Za-z_][\\w-]*)(?=[^{};]*\\{)", RegexOption.MULTILINE), Kind.TAG, 2),
            Rule(Regex("[{}();:]"), Kind.PUNCT)
        )
        CodeLanguage.HTML, CodeLanguage.XML -> listOf(
            Rule(Regex("<!--[\\s\\S]*?(?:-->|$)"), Kind.COMMENT),
            Rule(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), Kind.KEYWORD),
            Rule(Regex("\"[^\"\\n]*\"|'[^'\\n]*'"), Kind.STRING),
            Rule(Regex("</?([A-Za-z][\\w:.-]*)"), Kind.TAG, 1),
            Rule(Regex("\\s([A-Za-z_:@][\\w:.-]*)(?=\\s*=)"), Kind.ATTR, 1),
            Rule(Regex("</?|/?>"), Kind.PUNCT)
        )
        CodeLanguage.JSON -> listOf(
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)"), Kind.ATTR),
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), Kind.STRING),
            Rule(kw("true false null"), Kind.KEYWORD), NUMBERS, Rule(Regex("[{}\\[\\],:]"), Kind.PUNCT)
        )
        CodeLanguage.MARKDOWN -> listOf(
            Rule(Regex("```[\\s\\S]*?(?:```|$)"), Kind.STRING),
            Rule(Regex("`[^`\\n]+`"), Kind.STRING),
            Rule(Regex("^#{1,6} .*$", RegexOption.MULTILINE), Kind.KEYWORD),
            Rule(Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__"), Kind.TYPE),
            Rule(Regex("\\[[^\\]\\n]+\\]\\([^)\\n]+\\)"), Kind.FUNCTION),
            Rule(Regex("^\\s*(?:[-*+]|\\d+\\.)\\s", RegexOption.MULTILINE), Kind.NUMBER),
            Rule(Regex("^>.*$", RegexOption.MULTILINE), Kind.COMMENT)
        )
        CodeLanguage.SHELL -> listOf(
            Rule(Regex("#[^\\n]*"), Kind.COMMENT), STRINGS, Rule(Regex("\\$\\{?[\\w]+\\}?"), Kind.ATTR), Rule(SH_KW, Kind.KEYWORD), NUMBERS
        )
        CodeLanguage.PLAIN -> emptyList()
    }

    private fun style(kind: Kind, c: SyntaxColors): SpanStyle = when (kind) {
        Kind.KEYWORD -> SpanStyle(color = c.keyword, fontWeight = FontWeight.SemiBold)
        Kind.STRING -> SpanStyle(color = c.string)
        Kind.COMMENT -> SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)
        Kind.NUMBER -> SpanStyle(color = c.number)
        Kind.TAG -> SpanStyle(color = c.tag)
        Kind.ATTR -> SpanStyle(color = c.attr)
        Kind.FUNCTION -> SpanStyle(color = c.function)
        Kind.TYPE -> SpanStyle(color = c.type)
        Kind.PUNCT -> SpanStyle(color = c.punctuation)
    }

    fun highlight(
        text: String,
        lang: CodeLanguage,
        colors: SyntaxColors,
        matches: List<IntRange> = emptyList(),
        currentMatch: Int = -1
    ): AnnotatedString {
        val builder = AnnotatedString.Builder(text)
        if (text.length <= MAX_HIGHLIGHT_CHARS) {
            val taken = BooleanArray(text.length)
            for (rule in rulesFor(lang)) {
                for (m in rule.regex.findAll(text)) {
                    val g = if (rule.group == 0) m.groups[0] else m.groups[rule.group]
                    val range = g?.range ?: continue
                    if (range.isEmpty()) continue
                    if (taken[range.first]) continue
                    var free = true
                    for (k in range) if (taken[k]) { free = false; break }
                    if (!free) continue
                    for (k in range) taken[k] = true
                    builder.addStyle(style(rule.kind, colors), range.first, range.last + 1)
                }
            }
        }
        matches.forEachIndexed { i, r ->
            if (r.first >= 0 && r.last < text.length && !r.isEmpty()) {
                builder.addStyle(SpanStyle(background = if (i == currentMatch) colors.currentMatch else colors.searchMatch), r.first, r.last + 1)
            }
        }
        return builder.toAnnotatedString()
    }
}

/** Identity-offset transformation that colours the editor text. */
class CodeVisualTransformation(
    private val lang: CodeLanguage,
    private val colors: SyntaxColors,
    private val matches: List<IntRange>,
    private val currentMatch: Int
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(SyntaxHighlighter.highlight(text.text, lang, colors, matches, currentMatch), OffsetMapping.Identity)

    override fun equals(other: Any?): Boolean =
        other is CodeVisualTransformation && other.lang == lang && other.colors == colors &&
            other.matches == matches && other.currentMatch == currentMatch

    override fun hashCode(): Int = ((lang.hashCode() * 31 + colors.hashCode()) * 31 + matches.hashCode()) * 31 + currentMatch
}
