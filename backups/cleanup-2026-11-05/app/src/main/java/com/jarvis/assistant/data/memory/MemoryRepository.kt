package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import com.jarvis.assistant.androllm.memory.Memory
import com.jarvis.assistant.androllm.memory.MemoryCategory
import com.jarvis.assistant.androllm.memory.MemorySummary
import com.jarvis.assistant.androllm.memory.MemoryType
import com.jarvis.assistant.androllm.memory.Project
import com.jarvis.assistant.androllm.memory.Relationship
import com.jarvis.assistant.androllm.memory.Tag
import com.jarvis.assistant.androllm.memory.store.AndroMemoryDatabase
import com.jarvis.assistant.androllm.memory.store.MemoryMetaEntity
import com.jarvis.assistant.androllm.memory.store.MemoryMetaRegistry
import com.jarvis.assistant.androllm.memory.store.MemorySummaryEntity
import com.jarvis.assistant.androllm.memory.store.MemoryTagCrossRef
import com.jarvis.assistant.androllm.memory.store.ProjectEntity
import com.jarvis.assistant.androllm.memory.store.RelationshipEntity
import com.jarvis.assistant.androllm.memory.store.TagEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Jarvis' memory store: the `memories` table (`jarvis_memory.db`) PLUS the
 * AndroLLM sidecar metadata (`androllm_memory_meta.db`: pin / archive /
 * priority / type / expiry / tags / projects / summaries / relationships /
 * embeddings) keyed by `memories.id` and cached in [MemoryMetaRegistry].
 *
 * The former `AndroMemoryStore` was merged into this class (method names kept);
 * both database files and their schemas are unchanged.
 */
class MemoryRepository(private val memoryDao: MemoryDao, context: Context) {

    private val appContext = context.applicationContext

    /**
     * Optional vector-memory index.
     *
     * When attached, every memory written through this repository is indexed
     * for semantic recall. Without it the vector index silently stays empty
     * and `search_memories` degrades to a plain keyword LIKE query.
     */
    @Volatile
    private var vectorEngine: VectorMemoryEngine? = null

    // ── AndroLLM metadata database (opened lazily; same file, same schema) ──
    private val metaDb by lazy { AndroMemoryDatabase.getInstance(appContext) }
    private val metaDao by lazy { metaDb.metaDao() }
    private val summaryDao by lazy { metaDb.summaryDao() }
    private val projectDao by lazy { metaDb.projectDao() }
    private val tagDao by lazy { metaDb.tagDao() }
    private val relationshipDao by lazy { metaDb.relationshipDao() }
    private val embeddingDao by lazy { metaDb.embeddingDao() }
    private val graphMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        MemoryMetaRegistry.accessSink = { ids ->
            ioScope.launch {
                runCatching { metaDao.recordAccess(ids, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "recordAccess failed: ${it.message}") }
            }
        }
    }

    /**
     * Attaches the vector memory engine so saved memories become semantically
     * searchable. Safe to call more than once.
     */
    fun attachVectorEngine(engine: VectorMemoryEngine?) {
        vectorEngine = engine
    }

    // ── Jarvis `memories` table (unchanged API) ──────────────────────────────

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> = memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = ""
    ): Long {
        val calculatedKey = key.ifBlank {
            rawText.take(30).trim()
        }
        val calculatedValue = value.ifBlank { rawText }

        val entity = MemoryEntity(
            category = category,
            key = calculatedKey,
            value = calculatedValue,
            rawText = rawText
        )
        val id = memoryDao.insertMemory(entity)

        // Keep the semantic index in lock-step with the database so that
        // `search_memories` can actually recall what was just learned.
        vectorEngine?.let { engine ->
            runCatching { engine.indexMemory(id, rawText, category) }
        }

        return id
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        memoryDao.updateMemory(memory)
        vectorEngine?.let { engine ->
            runCatching { engine.indexMemory(memory.id, memory.rawText, memory.category) }
        }
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteMemoryById(id)
        vectorEngine?.removeMemory(id)
    }

    suspend fun deleteMemoriesByKeyword(keyword: String): Int {
        // Same WHERE clause as the delete: capture ids first so the vector
        // index does not keep recalling deleted memories.
        val matchedIds = memoryDao.searchMemories(keyword).map { it.id }
        val deleted = memoryDao.deleteMemoriesByKeyword(keyword)
        vectorEngine?.let { engine -> matchedIds.forEach { engine.removeMemory(it) } }
        return deleted
    }

    suspend fun getMemoryById(id: Long): MemoryEntity? = memoryDao.getMemoryById(id)

    suspend fun getAllMemoryIds(): List<Long> = memoryDao.getAllMemoryIds()

    suspend fun countMemories(): Int = memoryDao.countMemories()

    suspend fun clearAll() {
        memoryDao.clearAllMemories()
        vectorEngine?.clearIndex()
    }

    // ── AndroLLM metadata (former AndroMemoryStore) ──────────────────────────

    /** Loads every metadata row into [MemoryMetaRegistry]. */
    suspend fun loadRegistry() {
        val all = metaDao.getAll()
        MemoryMetaRegistry.replaceAll(all)
        Log.i(TAG, "Loaded ${all.size} AndroLLM memory metadata row(s)")
    }

    suspend fun getMeta(memoryId: Long): MemoryMetaEntity? =
        MemoryMetaRegistry.get(memoryId) ?: metaDao.getById(memoryId)

    suspend fun getAllMeta(): List<MemoryMetaEntity> = metaDao.getAll()

    suspend fun upsertMeta(meta: MemoryMetaEntity) {
        metaDao.upsert(meta)
        MemoryMetaRegistry.put(meta)
        syncGraph(meta)
    }

    suspend fun upsertAllMeta(metas: List<MemoryMetaEntity>) {
        if (metas.isEmpty()) return
        metaDao.upsertAll(metas)
        metas.forEach { MemoryMetaRegistry.put(it) }
        metas.forEach { syncGraph(it) }
    }

    /** Pins a memory; creates a metadata row for legacy Jarvis memories on first use. */
    suspend fun setPinned(entity: MemoryEntity, pinned: Boolean) {
        val now = System.currentTimeMillis()
        val meta = (getMeta(entity.id) ?: defaultMetaFor(entity)).copy(isPinned = pinned, updatedAt = now)
        upsertMeta(meta)
    }

    suspend fun setArchived(entity: MemoryEntity, archived: Boolean) {
        val now = System.currentTimeMillis()
        val meta = (getMeta(entity.id) ?: defaultMetaFor(entity)).copy(isArchived = archived, updatedAt = now)
        upsertMeta(meta)
    }

    suspend fun deleteMeta(memoryId: Long) = deleteMeta(listOf(memoryId))

    /** Removes metadata AND every dependent graph row (tags, relationships, embeddings). */
    suspend fun deleteMeta(memoryIds: List<Long>) {
        if (memoryIds.isEmpty()) return
        memoryIds.chunked(500).forEach { chunk ->
            metaDao.deleteByIds(chunk)
            runCatching {
                tagDao.deleteCrossRefsForMemories(chunk)
                relationshipDao.deleteForMemories(chunk)
                embeddingDao.deleteByIds(chunk)
            }.onFailure { Log.w(TAG, "Graph cleanup failed: ${it.message}") }
        }
        memoryIds.forEach { MemoryMetaRegistry.remove(it) }
        runCatching { tagDao.deleteUnused() }
    }

    // ── Memory graph: projects / tags / relationships (schema v2) ──

    /**
     * Mirrors a metadata row's `projectName` / `tags` into the normalized
     * project + tag tables so they can be listed, filtered and exported.
     * The metadata columns stay the source of truth (backwards compatible).
     */
    suspend fun syncGraph(meta: MemoryMetaEntity) {
        graphMutex.withLock {
            runCatching {
                meta.projectName?.takeIf { it.isNotBlank() }?.let { resolveProjectLocked(it, meta.updatedAt) }
                val tagIds = resolveTagIdsLocked(meta.tagList)
                tagDao.deleteCrossRefsForMemory(meta.memoryId)
                if (tagIds.isNotEmpty()) tagDao.insertCrossRefs(tagIds.map { MemoryTagCrossRef(meta.memoryId, it) })
            }.onFailure { Log.w(TAG, "syncGraph failed for #${meta.memoryId}: ${it.message}") }
        }
    }

    /** Rebuilds the tag/project tables from every metadata row (maintenance). */
    suspend fun rebuildGraph(): Int {
        val all = metaDao.getAll()
        all.forEach { syncGraph(it) }
        runCatching { tagDao.deleteUnused() }
        return all.size
    }

    suspend fun getProjects(): List<Project> {
        val counts = MemoryMetaRegistry.snapshot().values
            .mapNotNull { meta -> meta.projectName?.trim()?.takeIf { it.isNotEmpty() } }
            .groupingBy { it }.eachCount()
        return projectDao.getAll().map {
            Project(it.id, it.name, it.description, it.createdAt, counts[it.name] ?: 0)
        }
    }

    suspend fun createProject(name: String, description: String = ""): Project = graphMutex.withLock {
        val trimmed = name.trim().take(64)
        require(trimmed.isNotEmpty()) { "Project name is empty" }
        val existing = projectDao.getByName(trimmed)
        val entity = existing?.copy(description = description.trim().ifBlank { existing.description })
            ?: ProjectEntity(UUID.randomUUID().toString(), trimmed, description.trim(), System.currentTimeMillis())
        projectDao.upsert(entity)
        Project(entity.id, entity.name, entity.description, entity.createdAt)
    }

    /** Deletes a project and detaches its memories (the memories themselves are kept). */
    suspend fun deleteProject(projectId: String) {
        val project = projectDao.getById(projectId) ?: return
        val now = System.currentTimeMillis()
        val detached = metaDao.getAll().filter { it.projectName == project.name }
            .map { it.copy(projectName = null, updatedAt = now) }
        if (detached.isNotEmpty()) {
            metaDao.upsertAll(detached)
            detached.forEach { MemoryMetaRegistry.put(it) }
        }
        projectDao.deleteById(projectId)
    }

    suspend fun getTags(): List<Tag> =
        tagDao.getTagUsage().map { Tag(it.id, it.name, it.memoryCount) }

    /** Deletes a tag everywhere (join rows + metadata tag lists). */
    suspend fun deleteTag(tagId: String) {
        val tag = tagDao.getById(tagId) ?: return
        val now = System.currentTimeMillis()
        val ids = tagDao.getMemoryIdsForTag(tagId).toSet()
        val updated = metaDao.getAll().filter { it.memoryId in ids || tag.name in it.tagList }.map { meta ->
            meta.copy(tags = meta.tagList.filterNot { it == tag.name }.joinToString(","), updatedAt = now)
        }
        if (updated.isNotEmpty()) {
            metaDao.upsertAll(updated)
            updated.forEach { MemoryMetaRegistry.put(it) }
        }
        tagDao.deleteCrossRefsForTag(tagId)
        tagDao.deleteById(tagId)
    }

    /** Memory ids carrying any tag whose name contains [query]. */
    suspend fun memoryIdsForTagQuery(query: String): Set<Long> =
        if (query.isBlank()) emptySet() else tagDao.searchMemoryIdsByTag(query.trim().lowercase()).toSet()

    suspend fun getRelationships(): List<Relationship> = relationshipDao.getAll().map { it.toDomain() }

    suspend fun getRelationshipsFor(memoryId: Long): List<Relationship> =
        relationshipDao.getForMemory(memoryId).map { it.toDomain() }

    /** Adds a typed link (deduplicated by from/to/type). Returns null when it already exists. */
    suspend fun addRelationship(fromMemoryId: Long, toMemoryId: Long, type: String = RELATED_TO): Relationship? {
        if (fromMemoryId == toMemoryId) return null
        val cleanType = type.trim().lowercase().replace(WHITESPACE, "_").ifBlank { RELATED_TO }.take(32)
        val exists = relationshipDao.getForMemory(fromMemoryId).any {
            it.type == cleanType && (
                (it.fromMemoryId == fromMemoryId && it.toMemoryId == toMemoryId) ||
                    (cleanType == RELATED_TO && it.fromMemoryId == toMemoryId && it.toMemoryId == fromMemoryId)
                )
        }
        if (exists) return null
        val entity = RelationshipEntity(UUID.randomUUID().toString(), fromMemoryId, toMemoryId, cleanType, System.currentTimeMillis())
        relationshipDao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun deleteRelationship(id: String) {
        relationshipDao.deleteById(id)
    }

    /**
     * Links memories written in the same exchange with "related_to"
     * relationships (ported from AndroLLM linkExchangeMemories).
     */
    suspend fun linkExchangeMemories(ids: List<Long>) {
        val distinct = ids.filter { it > 0 }.distinct()
        if (distinct.size < 2) return
        for (i in distinct.indices) {
            for (j in i + 1 until distinct.size) {
                addRelationship(distinct[i], distinct[j], RELATED_TO)
            }
        }
    }

    suspend fun projectCount(): Int = projectDao.count()
    suspend fun tagCount(): Int = tagDao.count()
    suspend fun relationshipCount(): Int = relationshipDao.count()

    private suspend fun resolveProjectLocked(name: String, now: Long): String {
        val trimmed = name.trim().take(64)
        projectDao.getByName(trimmed)?.let { return it.id }
        val project = ProjectEntity(UUID.randomUUID().toString(), trimmed, "", now)
        projectDao.upsert(project)
        return project.id
    }

    private suspend fun resolveTagIdsLocked(names: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (name in names.distinct().take(10)) {
            val trimmed = name.trim().lowercase().take(32)
            if (trimmed.isEmpty()) continue
            val existing = tagDao.getByName(trimmed)
            if (existing != null) {
                result += existing.id
            } else {
                val tag = TagEntity(UUID.randomUUID().toString(), trimmed)
                tagDao.upsert(tag)
                result += tag.id
            }
        }
        return result
    }

    suspend fun getExpired(now: Long): List<MemoryMetaEntity> = metaDao.getExpired(now)

    // ── Summaries ──

    suspend fun getSummary(conversationId: String): MemorySummary? =
        summaryDao.getForConversation(conversationId)?.toDomain()

    suspend fun recentSummaries(limit: Int): List<MemorySummary> =
        summaryDao.getRecent(limit.coerceAtLeast(0)).map { it.toDomain() }

    suspend fun upsertSummary(summary: MemorySummary) = summaryDao.upsert(
        MemorySummaryEntity(
            conversationId = summary.conversationId,
            summary = summary.summary,
            messageCount = summary.messageCount,
            createdAt = summary.createdAt,
            updatedAt = summary.updatedAt
        )
    )

    suspend fun summaryCount(): Int = summaryDao.count()

    suspend fun deleteAllSummaries() = summaryDao.deleteAll()

    /** Metadata for a legacy Jarvis memory (no AndroLLM row yet). */
    fun defaultMetaFor(entity: MemoryEntity): MemoryMetaEntity = MemoryMetaEntity(
        memoryId = entity.id,
        androCategory = MemoryCategory.fromJarvisCategory(entity.category).name,
        priority = 1,
        importance = 1,
        type = MemoryType.LONG_TERM.name,
        source = Memory.SOURCE_JARVIS,
        createdAt = entity.timestamp,
        updatedAt = entity.timestamp
    )

    companion object {
        private const val TAG = "MemoryRepository"
        const val RELATED_TO = "related_to"
        private val WHITESPACE = Regex("\\s+")

        /** Joins a Jarvis row with its (optional) AndroLLM metadata into the domain model. */
        fun toDomain(entity: MemoryEntity, meta: MemoryMetaEntity?): Memory {
            val category = meta?.let { MemoryCategory.fromName(it.androCategory) }
                ?: MemoryCategory.fromJarvisCategory(entity.category)
            return Memory(
                id = entity.id,
                category = category,
                jarvisCategory = entity.category,
                content = entity.rawText,
                importance = meta?.importance ?: 1,
                tags = meta?.tagList ?: emptyList(),
                projectName = meta?.projectName,
                isPinned = meta?.isPinned ?: false,
                isArchived = meta?.isArchived ?: false,
                accessCount = meta?.accessCount ?: 0,
                createdAt = meta?.createdAt ?: entity.timestamp,
                updatedAt = maxOf(entity.timestamp, meta?.updatedAt ?: 0L),
                lastAccessedAt = meta?.lastAccessedAt,
                chatId = meta?.chatId,
                type = MemoryType.fromName(meta?.type),
                priority = meta?.priority ?: 1,
                expiryAt = meta?.expiryAt,
                source = meta?.source ?: Memory.SOURCE_JARVIS
            )
        }

        private fun RelationshipEntity.toDomain() = Relationship(id, fromMemoryId, toMemoryId, type, createdAt)

        private fun MemorySummaryEntity.toDomain() = MemorySummary(
            conversationId = conversationId,
            summary = summary,
            messageCount = messageCount,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
