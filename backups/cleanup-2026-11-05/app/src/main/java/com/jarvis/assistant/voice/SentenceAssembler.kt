package com.jarvis.assistant.voice

/**
 * Natural speech chunk with absolute character offset in the streamed answer.
 */
data class SpokenChunk(
    val text: String,
    val startOffset: Int
)

/**
 * Buffers a streaming token stream into natural sentence and clause chunks
 * so TTS can begin speaking smoothly before the LLM finishes generation.
 * Eliminates choppy sentence splitting and dropped punctuation.
 */
class SentenceAssembler(
    private val maxWords: Int = MAX_WORDS,
    private val minChunkChars: Int = MIN_CHUNK_CHARS
) {

    private val buffer = StringBuilder()
    private var charsFed = 0

    fun feed(delta: String): List<SpokenChunk> {
        buffer.append(delta)
        charsFed += delta.length
        val text = buffer.toString()
        val chunks = mutableListOf<SpokenChunk>()
        var start = 0

        for (i in text.indices) {
            val c = text[i]
            // Punctuation only ends a chunk when followed by whitespace, so "3.14", "14:30",
            // "1,000", "+91-98…", URLs and emails reach the normalizer in one piece.
            // At the end of the buffer we wait for the next token to decide.
            val followedBySpace = i + 1 < text.length && text[i + 1].isWhitespace()
            if (c == '\n' || c == '।' || (isSentenceEnd(c) && followedBySpace)) {
                val end = if (c == '\n') i else i + 1
                emit(text, start, end, chunks)
                start = i + 1
            } else if (isClauseBoundary(c) && followedBySpace && (i - start) >= MIN_CLAUSE_CHARS) {
                emit(text, start, i + 1, chunks)
                start = i + 1
            }
        }

        buffer.setLength(0)
        buffer.append(text.substring(start))

        if (countWords(buffer) >= maxWords) {
            val whole = buffer.toString()
            val trimmed = whole.trim()
            buffer.setLength(0)
            if (trimmed.isNotEmpty()) {
                val leading = whole.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                chunks.add(SpokenChunk(trimmed, charsFed - whole.length + leading))
            }
        }
        return chunks
    }

    fun flush(force: Boolean = false): SpokenChunk? {
        val raw = buffer.toString()
        buffer.setLength(0)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length < minChunkChars && !force) return null
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return SpokenChunk(trimmed, charsFed - raw.length + leading)
    }

    private fun emit(text: String, start: Int, end: Int, out: MutableList<SpokenChunk>) {
        val raw = text.substring(start, end)
        val trimmed = raw.trim()
        if (trimmed.length < minChunkChars) return
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        out.add(SpokenChunk(trimmed, charsFed - text.length + start + leading))
    }

    private fun countWords(s: CharSequence): Int {
        if (s.isBlank()) return 0
        return s.split(Regex("\\s+")).size
    }

    private fun isSentenceEnd(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '\n' || c == '।'

    private fun isClauseBoundary(c: Char): Boolean =
        c == ',' || c == ';' || c == ':' || c == '-'

    companion object {
        const val MAX_WORDS = 14
        const val MIN_CHUNK_CHARS = 2
        const val MIN_CLAUSE_CHARS = 20
    }
}
