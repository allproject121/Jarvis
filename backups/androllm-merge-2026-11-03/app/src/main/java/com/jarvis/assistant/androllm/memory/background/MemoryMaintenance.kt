package com.jarvis.assistant.androllm.memory.background

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.memory.TextEmbeddingEngine
import com.jarvis.assistant.androllm.memory.MemorySettingsStore
import com.jarvis.assistant.androllm.memory.store.AndroMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Memory indexing / maintenance pass (Jarvis counterpart of AndroLLM's
 * `embedPendingMemories`). Purely local — no LLM or network calls:
 *
 *  1. Embeds every Room memory missing from the vector index (the "pending queue");
 *     re-embeds everything when the embedding backend changed.
 *  2. Prunes vector entries whose Room rows were deleted.
 *  3. Drops orphaned AndroLLM metadata rows.
 *  4. Archives expired SESSION/PROJECT memories (nothing is deleted).
 *
 * Runs from [MemoryIndexingWorker] (only when background indexing is enabled)
 * or on demand from the settings section.
 */
object MemoryMaintenance {

    private const val TAG = "AndroMemMaintenance"
    private val mutex = Mutex()

    data class Report(
        val total: Int,
        val indexed: Int,
        val newlyIndexed: Int,
        val pruned: Int,
        val orphanedMeta: Int,
        val archivedExpired: Int,
        val reembedded: Boolean,
        /** Memories newly embedded into the persisted AndroLLM neural index. */
        val neuralEmbedded: Int = 0
    ) {
        fun describe(): String = buildString {
            append("$indexed/$total indexed")
            if (reembedded) append(", re-embedded all")
            else if (newlyIndexed > 0) append(", +$newlyIndexed new")
            if (pruned > 0) append(", pruned $pruned stale")
            if (orphanedMeta > 0) append(", cleaned $orphanedMeta orphan meta")
            if (archivedExpired > 0) append(", archived $archivedExpired expired")
            if (neuralEmbedded > 0) append(", +$neuralEmbedded neural vectors")
        }
    }

    suspend fun run(context: Context): Report = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val app = (appContext as? JarvisApp) ?: JarvisApp.instance
            val repository = app.memoryRepository
            val vectorEngine = app.vectorMemoryEngine
            val store = AndroMemoryStore.getInstance(appContext)
            val settings = MemorySettingsStore(appContext)

            // Make sure the registry reflects the database before ranking/archiving.
            store.loadRegistry()
            // Keep Jarvis' embedder in sync with the chosen embedding source.
            val embeddingIndex = com.jarvis.assistant.androllm.memory.embedding.MemoryEmbeddingIndex.getInstance(appContext)
            runCatching { embeddingIndex.applyToJarvis() }

            val all = repository.getAllMemories()
            val ids = all.map { it.id }.toSet()
            val before = vectorEngine.indexedMemoryCount()

            val signature = TextEmbeddingEngine.getInstance(appContext).embedderSignature()
            val reembed = settings.indexedEmbedderSignature().let { it.isNotEmpty() && it != signature }
            if (reembed) {
                vectorEngine.reindexAll(all)
            } else {
                vectorEngine.syncWithRoomMemories(all)
            }
            settings.setIndexedEmbedderSignature(signature)
            val pruned = vectorEngine.pruneMissing(ids)
            val newlyIndexed = if (reembed) all.size else (vectorEngine.indexedMemoryCount() + pruned - before).coerceAtLeast(0)

            val orphans = store.getAllMeta().map { it.memoryId }.filter { it !in ids }
            store.deleteMeta(orphans)

            val now = System.currentTimeMillis()
            var archived = 0
            for (meta in store.getExpired(now)) {
                if (meta.memoryId !in ids) continue
                store.upsertMeta(meta.copy(isArchived = true, updatedAt = now))
                archived++
            }

            // Persisted neural vectors (only when an embedding source is configured).
            val neural = runCatching {
                if (reembed) embeddingIndex.reindexAll(all) else embeddingIndex.embedPending(all)
            }.onFailure { Log.w(TAG, "Neural embedding pass failed: ${it.message}") }.getOrDefault(0)
            // Mirror metadata tags/projects into the graph tables (schema v2).
            runCatching { store.rebuildGraph() }.onFailure { Log.w(TAG, "Graph rebuild failed: ${it.message}") }

            val report = Report(
                total = all.size,
                indexed = vectorEngine.indexedMemoryCount(),
                newlyIndexed = newlyIndexed,
                pruned = pruned,
                orphanedMeta = orphans.size,
                archivedExpired = archived,
                reembedded = reembed,
                neuralEmbedded = neural
            )
            settings.recordIndexing(report.describe(), now)
            Log.i(TAG, "Memory maintenance: ${report.describe()}")
            com.jarvis.assistant.androllm.memory.util.MemoryLogger.info("Maintenance: ${report.describe()}")
            report
        }
    }
}
