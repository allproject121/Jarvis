package com.jarvis.assistant.ai.memory

import kotlin.math.sqrt

/**
 * High-performance vector math operations for on-device semantic similarity and RAG.
 */
object VectorMath {

    /**
     * Computes the Cosine Similarity between two embedding vectors.
     * Returns a float value between -1.0 (opposite) and +1.0 (identical semantic meaning).
     */
    fun cosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        if (vectorA.size != vectorB.size || vectorA.isEmpty()) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in vectorA.indices) {
            val a = vectorA[i]
            val b = vectorB[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator <= 0.0000001f) return 0.0f

        return (dotProduct / denominator).coerceIn(-1.0f, 1.0f)
    }

    /**
     * Normalizes a vector in-place to unit length (L2 norm = 1.0).
     */
    fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vector) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 0.0000001f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    /**
     * Converts a FloatArray into a compact ByteArray for persistent storage.
     */
    fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(floats.size * 4)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    /**
     * Converts a stored ByteArray back into a FloatArray.
     */
    fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }
}
