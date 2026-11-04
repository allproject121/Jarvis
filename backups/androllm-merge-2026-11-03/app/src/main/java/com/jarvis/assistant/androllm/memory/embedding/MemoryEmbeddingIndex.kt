package com.jarvis.assistant.androllm.memory.embedding

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.memory.TextEmbeddingEngine
import com.jarvis.assistant.ai.memory.VectorMath
import com.jarvis.assistant.androllm.cloud.AndroLlmCloud
import com.jarvis.assistant.androllm.engine.embedding.LiteRtEmbeddingEngine
import com.jarvis.assistant.androllm.engine.support.getOrNull
import com.jarvis.assistant.androllm.memory.MemorySettingsStore
import com.jarvis.assistant.androllm.memory.store.AndroMemoryDatabase
import com.jarvis.assistant.androllm.memory.store.EmbeddingEntity
import com.jarvis.assistant.androllm.memory.util.MemoryLogger
import com.jarvis.assistant.androllm.memory.vector.CosineVectorIndex
import com.jarvis.assistant.androllm.memory.vector.VectorIndex
import com.jarvis.assistant.data.memory.MemoryEntity
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistent neural embedding index for Jarvis memories (the AndroLLM
 * `memory_embeddings` table + [CosineVectorIndex], ported from the AndroLLM
 * MemoryRepository indexing path).
 *
 * Two jobs:
 *  1. **Jarvis embedder hook** — when the user picks a neural [EmbeddingSource],
 *     [applyToJarvis] installs a [TextEmbeddingEngine.ExternalEmbedder] backed by
 *     [RoutingEmbeddingProvider] (hashing stays the default; nothing is installed
 *     for [EmbeddingSource.HASHING]). Persisted vectors are served from a text
 *     cache so a cold start does not re-embed every memory.
 *  2. **Hybrid retrieval** — [search] scores a query against the persisted
 *     vectors, optionally restricted to candidate ids (project/tag filters);
 *     [com.jarvis.assistant.androllm.memory.AndroMemoryPipeline.retrieve] fuses it
 *     with Jarvis' semantic + keyword results.
 *
 * Indexing ([embedPending], [reindexAll]) runs from MemoryMaintenance (manual
 * "Run indexing now" or the opt-in background worker), never on the chat path.
 */
class MemoryEmbeddingIndex private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settingsStore = MemorySettingsStore(appContext)
    private val embeddingDao by lazy { AndroMemoryDatabase.getInstance(appContext).embeddingDao() }
    private val logger = MemoryLogger

    val provider: RoutingEmbeddingProvider by lazy {
        val local = LiteRtEmbeddingProvider(LiteRtEmbeddingEngine.getInstance(), settingsStore, logger)
        val cloud = CloudEmbeddingProvider(AndroLlmCloud.getInstance(appContext).gateway, settingsStore, logger)
        RoutingEmbeddingProvider(cloud, local, settingsStore, logger)
    }

    private val indexMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    private var vectorIndex: CosineVectorIndex? = null

    @Volatile
    private var indexLabel: String = ""

    /** text -> vector for the active label (served to Jarvis' TextEmbeddingEngine). */
    private val textCache: MutableMap<String, FloatArray> =
        Collections.synchronizedMap(object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > TEXT_CACHE_MAX
        })

    // ── Inspector timings ──
    @Volatile var lastEmbeddingMs: Long = 0L
        private set
    @Volatile var totalEmbeddings: Long = 0L
        private set

    val vectorCount: Int get() = vectorIndex?.size ?: 0

    // ── Jarvis embedder hook ─────────────────────────────────────────────────

    /**
     * Installs (or removes) the AndroLLM embedder in Jarvis' [TextEmbeddingEngine]
     * according to [com.jarvis.assistant.androllm.memory.MemorySettings.embeddingSource].
     * Cheap and synchronous: reads SharedPreferences only; no model load and no
     * network until the first embed call.
     */
    fun applyToJarvis() {
        val settings = settingsStore.current()
        val engine = TextEmbeddingEngine.getInstance(appContext)
        val id = when (settings.embeddingSource) {
            EmbeddingSource.HASHING -> null
            EmbeddingSource.LITERT -> settings.embeddingModelPath.takeIf { it.isNotBlank() }?.let { "litert:$it" }
            EmbeddingSource.CLOUD -> settings.cloudEmbeddingModel.takeIf { it.isNotBlank() }?.let { "cloud:$it" }
            EmbeddingSource.AUTO -> listOf(
                settings.cloudEmbeddingModel.takeIf { it.isNotBlank() }?.let { "cloud:$it" },
                settings.embeddingModelPath.takeIf { it.isNotBlank() }?.let { "litert:$it" }
            ).filterNotNull().joinToString("|").ifBlank { null }?.let { "auto:$it" }
        }
        if (id == null) {
            engine.setExternalEmbedder("", null)
            logger.info("Jarvis embedder: built-in hashing")
        } else {
            engine.setExternalEmbedder(id, JarvisEmbedderAdapter())
            logger.info("Jarvis embedder: ${settings.embeddingSource.name} ($id)")
        }
    }

    private inner class JarvisEmbedderAdapter : TextEmbeddingEngine.ExternalEmbedder {
        override suspend fun embed(text: String): FloatArray? {
            val key = text.trim()
            textCache[key]?.let { return it.copyOf() }
            val t0 = System.currentTimeMillis()
            val vector = provider.embed(key).getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            lastEmbeddingMs = System.currentTimeMillis() - t0
            totalEmbeddings++
            textCache[key] = vector
            return vector.copyOf()
        }
    }

    // ── Index lifecycle ──────────────────────────────────────────────────────

    /** Loads persisted vectors of the active label into memory (and the text cache). */
    suspend fun load(memories: List<MemoryEntity>): Int = withContext(Dispatchers.IO) {
        indexMutex.withLock { loadLocked(provider.activeLabel(), memories) }
    }

    private suspend fun loadLocked(label: String, memories: List<MemoryEntity>): Int {
        if (label.isBlank()) {
            vectorIndex = null
            indexLabel = ""
            return 0
        }
        if (vectorIndex != null && indexLabel == label) return vectorIndex?.size ?: 0
        val rows = embeddingDao.getAllForModel(label)
        val textById = memories.associate { it.id to it.rawText.trim() }
        val first = rows.firstOrNull()
        val index = CosineVectorIndex(first?.dimension ?: 0)
        for (row in rows) {
            if (row.dimension != index.dimension) continue
            val vector = VectorMath.byteArrayToFloatArray(row.vector)
            runCatching { index.upsert(row.memoryId.toString(), vector) }
            if (settingsStore.current().passagePrefix.isBlank()) {
                textById[row.memoryId]?.let { textCache[it] = vector }
            }
        }
        vectorIndex = if (rows.isEmpty()) null else index
        indexLabel = label
        return index.size
    }

    /**
     * Embeds every memory without a vector for the active label (the "pending
     * queue"). Returns the number of newly embedded memories (0 when no neural
     * source is configured — memories stay usable via hashing/keyword retrieval).
     */
    suspend fun embedPending(memories: List<MemoryEntity>): Int = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val label = provider.activeLabel()
            if (label.isBlank()) return@withLock 0
            val done = embeddingDao.getEmbeddedIds(label).toSet()
            val pending = memories.filter { it.id !in done && it.rawText.isNotBlank() }
            embedAndStore(label, pending, chunkSize = 8)
        }
    }

    /** Drops vectors from other embedding spaces and re-embeds everything. */
    suspend fun reindexAll(memories: List<MemoryEntity>): Int = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val label = provider.activeLabel()
            if (label.isBlank()) return@withLock 0
            provider.ensureLoaded()
            embeddingDao.deleteByModelPathNot(label)
            indexMutex.withLock {
                vectorIndex = null
                indexLabel = ""
            }
            textCache.clear()
            embedAndStore(label, memories.filter { it.rawText.isNotBlank() }, chunkSize = 16)
        }
    }

    private suspend fun embedAndStore(label: String, memories: List<MemoryEntity>, chunkSize: Int): Int {
        if (memories.isEmpty()) return 0
        val prefix = settingsStore.current().passagePrefix
        var count = 0
        for (chunk in memories.chunked(chunkSize)) {
            val texts = chunk.map { if (prefix.isBlank()) it.rawText.trim() else "$prefix ${it.rawText.trim()}" }
            val t0 = System.currentTimeMillis()
            val vectors = provider.embed(texts).getOrNull()
            if (vectors == null || vectors.size != chunk.size) {
                logger.warn("Embedding batch failed for ${chunk.size} memory(s); will retry on next indexing")
                continue
            }
            lastEmbeddingMs = System.currentTimeMillis() - t0
            val now = System.currentTimeMillis()
            embeddingDao.upsertAll(
                chunk.mapIndexed { i, m ->
                    EmbeddingEntity(
                        memoryId = m.id,
                        vector = VectorMath.floatArrayToByteArray(vectors[i]),
                        dimension = vectors[i].size,
                        modelPath = label,
                        createdAt = now
                    )
                }
            )
            indexMutex.withLock {
                var index = vectorIndex?.takeIf { indexLabel == label && it.dimension == vectors.first().size }
                if (index == null) {
                    index = CosineVectorIndex(vectors.first().size)
                    vectorIndex = index
                    indexLabel = label
                }
                for (i in chunk.indices) {
                    runCatching { index.upsert(chunk[i].id.toString(), vectors[i]) }
                    if (prefix.isBlank()) textCache[chunk[i].rawText.trim()] = vectors[i]
                }
            }
            count += chunk.size
            totalEmbeddings += chunk.size
        }
        logger.info("Embedded $count memory(s) [$label]")
        return count
    }

    /**
     * Scores [query] against the persisted vectors. Returns memory id -> cosine
     * score (best first), restricted to [candidates] when given. Empty when no
     * neural source / no vectors exist.
     */
    suspend fun search(
        query: String,
        topK: Int,
        candidates: Collection<Long>? = null,
        memories: List<MemoryEntity> = emptyList()
    ): List<Pair<Long, Float>> = withContext(Dispatchers.IO) {
        if (query.isBlank() || topK <= 0) return@withContext emptyList()
        val label = provider.activeLabel()
        if (label.isBlank()) return@withContext emptyList()
        val index = indexMutex.withLock {
            loadLocked(label, memories)
            vectorIndex
        } ?: return@withContext emptyList()
        val prefix = settingsStore.current().queryPrefix
        val t0 = System.currentTimeMillis()
        val queryVector = provider.embed(if (prefix.isBlank()) query.trim() else "$prefix ${query.trim()}").getOrNull()
            ?: return@withContext emptyList()
        lastEmbeddingMs = System.currentTimeMillis() - t0
        totalEmbeddings++
        index.search(queryVector, topK, candidates?.map { it.toString() })
            .mapNotNull { scored: VectorIndex.ScoredId -> scored.id.toLongOrNull()?.let { it to scored.score } }
    }

    suspend fun embeddingCount(): Int = runCatching { embeddingDao.count() }.getOrDefault(0)

    /** Forgets the in-memory index (e.g. after the embedding source changed). */
    suspend fun invalidate() {
        indexMutex.withLock {
            vectorIndex = null
            indexLabel = ""
        }
        textCache.clear()
    }

    fun remove(memoryId: Long) {
        vectorIndex?.remove(memoryId.toString())
    }

    companion object {
        private const val TAG = "MemoryEmbeddingIndex"
        private const val TEXT_CACHE_MAX = 4096

        @Volatile
        private var instance: MemoryEmbeddingIndex? = null

        fun getInstance(context: Context): MemoryEmbeddingIndex =
            instance ?: synchronized(this) {
                instance ?: MemoryEmbeddingIndex(context.applicationContext).also { instance = it }
            }

        /**
         * Startup hook (called from Jarvis' VectorMemoryEngine init): installs the
         * configured embedder before the memory core hydrates. No-op for the
         * default hashing embedder; never throws.
         */
        fun installIfConfigured(context: Context) {
            try {
                val settings = MemorySettingsStore(context.applicationContext).current()
                if (settings.embeddingSource == EmbeddingSource.HASHING) return
                getInstance(context).applyToJarvis()
            } catch (e: Exception) {
                Log.w(TAG, "Embedder install skipped: ${e.message}")
            }
        }
    }
}
