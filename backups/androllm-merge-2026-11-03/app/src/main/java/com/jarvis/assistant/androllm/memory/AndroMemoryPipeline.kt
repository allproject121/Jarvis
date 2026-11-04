package com.jarvis.assistant.androllm.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.memory.MemoryWritePolicy
import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.memory.classify.MemoryClassifier
import com.jarvis.assistant.androllm.memory.context.ContextBuilder
import com.jarvis.assistant.androllm.memory.correction.MemoryCorrectionResolver
import com.jarvis.assistant.androllm.memory.embedding.MemoryEmbeddingIndex
import com.jarvis.assistant.androllm.memory.filter.MemoryContentValidator
import com.jarvis.assistant.androllm.memory.hardening.MemoryHardeningHelper
import com.jarvis.assistant.androllm.memory.intelligence.MemoryIntelligence
import com.jarvis.assistant.androllm.memory.intelligence.RoutingMemoryIntelligence
import com.jarvis.assistant.androllm.memory.store.AndroMemoryStore
import com.jarvis.assistant.androllm.memory.store.MemoryMetaEntity
import com.jarvis.assistant.androllm.memory.store.MemoryMetaRegistry
import com.jarvis.assistant.androllm.memory.util.MemoryLogger
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The AndroLLM memory pipeline running on top of Jarvis' EXISTING memory store
 * ([MemoryRepository] / `jarvis_memory.db` + [VectorMemoryEngine]) — an upgrade,
 * not a parallel silo. Memories it writes are ordinary Jarvis memories (visible
 * in MemoryActivity, Mem0 tools, Gemini system prompt); lifecycle metadata
 * (pinned, priority, type, expiry, access counts) lives in the separate
 * [com.jarvis.assistant.androllm.memory.store.AndroMemoryDatabase].
 *
 * Public surface (provider-independent, best effort — never throws into chat):
 *  - [processExchange] after a response: extract -> policy/security/confidence ->
 *    update-or-insert -> rolling summary. Gated by [AndroLlmFlags.MEMORY_EXTRACTION] (default OFF).
 *  - [buildContext] before generation: ranked memories + summaries as a system block.
 *  - [saveMemory], [getMemories], [setPinned], [setArchived], [deleteMemory], [exportMemories], [stats].
 *
 * LLM calls go through [RoutingMemoryIntelligence] (Jarvis' quiet text routing /
 * on-device model); never through the Gemini Live websocket.
 */
class AndroMemoryPipeline private constructor(context: Context) {

    private val appContext = context.applicationContext
    val settingsStore = MemorySettingsStore(appContext)
    private val store = AndroMemoryStore.getInstance(appContext)
    private val intelligence: MemoryIntelligence =
        RoutingMemoryIntelligence.create(appContext) { settingsStore.current().route }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val jobMutex = Mutex()
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val logger = MemoryLogger
    val embeddingIndex: MemoryEmbeddingIndex by lazy { MemoryEmbeddingIndex.getInstance(appContext) }

    // ── Inspector timings (ported from AndroLLM MemoryRepository) ──
    @Volatile private var lastRetrievalMs = 0L
    @Volatile private var avgRetrievalMs = 0L
    @Volatile private var retrievalSamples = 0L
    @Volatile private var lastExtractionMs = 0L
    @Volatile private var totalExtractions = 0L
    @Volatile private var totalInserted = 0L
    @Volatile private var totalUpdated = 0L

    init {
        logger.minLevel = settingsStore.current().logLevel
    }

    private val app: JarvisApp
        get() = (appContext as? JarvisApp) ?: JarvisApp.instance
    private val repository: MemoryRepository get() = app.memoryRepository
    private val vectorEngine: VectorMemoryEngine get() = app.vectorMemoryEngine

    // ── Hook from Jarvis' MemoryManager ──────────────────────────────────────

    /**
     * Called after every Jarvis turn. No-op unless [AndroLlmFlags.MEMORY_EXTRACTION]
     * is enabled. Runs in the background, serialized, never blocking the caller.
     */
    fun onJarvisTurn(userText: String, assistantText: String, history: List<ChatTurn>) {
        if (!AndroLlmFlags.isEnabled(appContext, AndroLlmFlags.MEMORY_EXTRACTION)) return
        val user = userText.trim()
        val assistant = assistantText.trim()
        if (user.isBlank() || user == AUDIO_QUERY_PLACEHOLDER) return
        scope.launch {
            jobMutex.withLock {
                runCatching {
                    val settings = settingsStore.current()
                    val count = settingsStore.incrementMessageCount(DEFAULT_CONVERSATION_ID, 2)
                    val recent = history
                        .dropLastWhile { it.userTranscript.trim() == user && it.jarvisResponse.trim() == assistant }
                        .takeLast(settings.summarizationInterval / 2)
                        .flatMap { turn ->
                            listOfNotNull(
                                turn.userTranscript.trim().takeIf { it.isNotBlank() && it != AUDIO_QUERY_PLACEHOLDER }?.let { "user" to it },
                                turn.jarvisResponse.trim().takeIf { it.isNotBlank() && it != AUDIO_RESPONSE_PLACEHOLDER }?.let { "assistant" to it }
                            )
                        }
                    processExchange(
                        MemoryExchange(
                            conversationId = DEFAULT_CONVERSATION_ID,
                            userMessage = user,
                            assistantResponse = assistant.takeIf { it != AUDIO_RESPONSE_PLACEHOLDER }.orEmpty(),
                            recentMessages = recent,
                            messageCount = count
                        )
                    )
                }.onFailure { Log.w(TAG, "Background memory processing failed: ${it.message}") }
            }
        }
    }

    // ── Write pipeline ───────────────────────────────────────────────────────

    /** extract -> (dedupe/correct) -> update-or-insert -> summarize. */
    suspend fun processExchange(exchange: MemoryExchange): Result<MemoryWriteSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.current()
            if (!settings.extractionEnabled) return@runCatching MemoryWriteSummary()

            val t0 = System.currentTimeMillis()
            val extraction = intelligence.extract(exchange)
            lastExtractionMs = System.currentTimeMillis() - t0
            totalExtractions++
            val items = extraction.getOrElse {
                settingsStore.recordExtraction("Extraction unavailable: ${it.message}")
                logger.warn("Extraction unavailable: ${it.message}")
                emptyList()
            }

            var inserted = 0
            var updated = 0
            var skipped = 0
            val touched = mutableListOf<Long>()
            for (item in items) {
                val result = writeMemory(item, exchange, settings, Memory.SOURCE_EXTRACTED)
                when (result.action) {
                    MemoryWriteAction.INSERTED -> inserted++
                    MemoryWriteAction.UPDATED -> updated++
                    MemoryWriteAction.SKIPPED -> {
                        skipped++
                        logger.debug("Skipped extracted memory: ${result.reason}")
                    }
                }
                if (result.action != MemoryWriteAction.SKIPPED && result.memoryId > 0) touched += result.memoryId
            }
            totalInserted += inserted
            totalUpdated += updated
            // Memories learned from the same exchange are linked ("related_to").
            runCatching { store.linkExchangeMemories(touched) }
                .onFailure { logger.warn("Relationship linking failed: ${it.message}") }
            val summarized = maybeSummarize(exchange, settings)
            if (extraction.isSuccess) {
                settingsStore.recordExtraction("+$inserted ~$updated -$skipped (extracted ${items.size})${if (summarized) ", summarized" else ""}")
            }
            logger.info("Exchange processed: +$inserted ~$updated -$skipped (extracted ${items.size}) summarized=$summarized in ${lastExtractionMs}ms")
            MemoryWriteSummary(inserted, updated, skipped, items.size, summarized)
        }
    }

    /** Manually adds a memory through the same policy/dedupe pipeline (no LLM call). */
    suspend fun saveMemory(
        category: MemoryCategory,
        content: String,
        importance: Int = 3,
        tags: List<String> = emptyList(),
        projectName: String? = null
    ): Result<MemoryWriteResult> = withContext(Dispatchers.IO) {
        runCatching {
            writeMemory(
                ExtractedMemory(content.trim(), category, importance.coerceIn(1, 5), tags, projectName),
                MemoryExchange.manual(),
                settingsStore.current(),
                Memory.SOURCE_MANUAL
            )
        }
    }

    private suspend fun writeMemory(
        item: ExtractedMemory,
        exchange: MemoryExchange,
        settings: MemorySettings,
        source: String
    ): MemoryWriteResult = writeMutex.withLock {
        val now = System.currentTimeMillis()
        var content = item.content.trim().replace(WHITESPACE, " ")
        if (content.isEmpty() || content.length > MAX_MEMORY_LENGTH) return@withLock skipped("empty or too long")
        if (content.length > 280) {
            val truncated = content.take(280).trim()
            val lastSpace = truncated.lastIndexOf(' ')
            content = if (lastSpace > 200) truncated.substring(0, lastSpace) else truncated
        }
        val normalizedItem = item.copy(content = content)

        // Write policy: durable value only; corrections update instead of duplicating.
        val decision = MemoryWritePolicy.decideExtracted(normalizedItem, exchange)
        if (decision is MemoryWritePolicy.Decision.Ignore) return@withLock skipped("policy: ${decision.reason}")

        MemoryContentValidator.validate(content, exchange)?.let { return@withLock skipped("security: $it") }
        if (MemoryHardeningHelper.isTemporaryContext(content, exchange)) return@withLock skipped("temporary context")
        if (!MemoryHardeningHelper.shouldCommit(normalizedItem, exchange)) return@withLock skipped("low confidence")

        // Classification: lifecycle type, priority, expiry, prompt-memory boost.
        val chatId = exchange.conversationId.takeIf { it.isNotBlank() }
        var category = item.category
        var importance = item.importance.coerceIn(1, 5)
        if (MemoryClassifier.isPromptMemory(content)) {
            category = MemoryCategory.PREFERENCES
            importance = maxOf(importance, 4)
        }
        val classified = normalizedItem.copy(category = category, importance = importance)
        val type = MemoryClassifier.classifyType(classified, chatId)
        val priority = MemoryClassifier.computePriority(classified, type)
        val expiryAt = MemoryClassifier.computeExpiry(type, now)
        // Jarvis already keeps current-conversation context in MemoryManager's
        // short-term history; short-term facts are not persisted long-term.
        if (type == MemoryType.SHORT_TERM) return@withLock skipped("short-term context")

        val candidates = loadCandidates()
        val target = classified.copy(importance = priority)

        // 0) Explicit correction: update the memory being corrected (never the new value).
        if (decision is MemoryWritePolicy.Decision.Update) {
            val hit = MemoryCorrectionResolver.findTarget(
                content,
                decision,
                candidates.map { MemoryCorrectionResolver.Candidate(it.id, it.content, it.updatedAt) }
            )
            val existing = hit?.let { h -> candidates.firstOrNull { it.id == h.id } }
            if (existing != null) {
                val winner = MemoryHardeningHelper.resolveConflict(content, priority, now, existing)
                if (winner != null && winner.id == existing.id) return@withLock MemoryWriteResult(existing.id, MemoryWriteAction.SKIPPED, reason = "kept existing")
                return@withLock updateExisting(existing.id, target, type, chatId, priority, expiryAt, now, null, source)
            }
        }

        // 1) Similarity dedupe/merge within the same Jarvis category.
        val best = runCatching {
            vectorEngine.searchSimilarMemories(
                content,
                topK = 1,
                minScore = (settings.similarityThreshold - MERGE_MARGIN).coerceAtLeast(0.3f),
                categoryFilter = category.jarvisCategory,
                recordAccess = false
            ).firstOrNull()
        }.getOrNull()
        if (best != null) {
            val existing = candidates.firstOrNull { it.id == best.memory.memoryId }
            if (existing != null) {
                if (MemoryHardeningHelper.isContradictory(content, existing.content)) {
                    val winner = MemoryHardeningHelper.resolveConflict(content, priority, now, existing)
                    if (winner != null && winner.id == existing.id) {
                        return@withLock MemoryWriteResult(existing.id, MemoryWriteAction.SKIPPED, best.similarityScore, "kept existing")
                    }
                }
                val merge = best.similarityScore < settings.similarityThreshold
                return@withLock updateExisting(existing.id, target, type, chatId, priority, expiryAt, now, best.similarityScore, source, mergeContent = merge)
            }
        }

        // 2) Normalized exact / near-duplicate in the same category.
        findExactDuplicate(content, category, candidates)?.let { existing ->
            if (MemoryHardeningHelper.isContradictory(content, existing.content)) {
                val winner = MemoryHardeningHelper.resolveConflict(content, priority, now, existing)
                if (winner != null && winner.id == existing.id) {
                    return@withLock MemoryWriteResult(existing.id, MemoryWriteAction.SKIPPED, reason = "kept existing")
                }
            }
            return@withLock updateExisting(existing.id, target, type, chatId, priority, expiryAt, now, null, source)
        }

        // 3) Insert as a normal Jarvis memory (+ AndroLLM metadata).
        val id = repository.saveMemory(
            rawText = content,
            category = category.jarvisCategory,
            key = content.take(30).trim(),
            value = content
        )
        try {
            store.upsertMeta(
                MemoryMetaEntity(
                    memoryId = id,
                    androCategory = category.name,
                    priority = priority,
                    importance = priority,
                    type = type.name,
                    expiryAt = expiryAt,
                    chatId = chatId,
                    tags = normalizeTags(item.tags).joinToString(","),
                    projectName = item.projectName,
                    source = source,
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now
                )
            )
        } catch (e: Exception) {
            // The Jarvis row is valid on its own; it just behaves as a legacy memory.
            Log.w(TAG, "Metadata write failed for memory #$id: ${e.message}")
        }
        Log.i(TAG, "Inserted memory #$id category=${category.name} type=${type.name}")
        MemoryWriteResult(id, MemoryWriteAction.INSERTED)
    }

    private suspend fun updateExisting(
        id: Long,
        item: ExtractedMemory,
        type: MemoryType,
        chatId: String?,
        priority: Int,
        expiryAt: Long?,
        now: Long,
        score: Float?,
        source: String,
        mergeContent: Boolean = false
    ): MemoryWriteResult {
        val entity = repository.getMemoryById(id) ?: return MemoryWriteResult(id, MemoryWriteAction.SKIPPED, score, "missing")
        val oldMeta = store.getMeta(id)

        val isDifferent = MemoryHardeningHelper.normalizeForDedupe(entity.rawText) !=
            MemoryHardeningHelper.normalizeForDedupe(item.content)
        val newContent = when {
            mergeContent && isDifferent -> "${entity.rawText} ${item.content}".replace(WHITESPACE, " ").trim().take(280)
            isDifferent -> item.content
            else -> entity.rawText
        }
        if (isDifferent) {
            // Re-indexes the vector entry (MemoryRepository keeps them in lock-step).
            repository.updateMemory(entity.copy(rawText = newContent, value = newContent, timestamp = now))
        }

        val base = oldMeta ?: store.defaultMetaFor(entity)
        val mergedTags = (base.tagList + normalizeTags(item.tags)).distinct().take(10)
        val newPriority = maxOf(priority, if (oldMeta != null) base.priority else 1).coerceIn(1, 5)
        store.upsertMeta(
            base.copy(
                androCategory = if (oldMeta == null) item.category.name else base.androCategory,
                priority = newPriority,
                importance = newPriority,
                type = type.name,
                expiryAt = if (base.isPinned) null else expiryAt ?: base.expiryAt,
                chatId = chatId ?: base.chatId,
                tags = mergedTags.joinToString(","),
                projectName = base.projectName ?: item.projectName,
                source = if (oldMeta == null) base.source else base.source.ifBlank { source },
                updatedAt = now,
                lastAccessedAt = now
            )
        )
        Log.i(TAG, "Updated memory #$id (score=${score ?: "exact"}) -> '${newContent.take(40)}'")
        return MemoryWriteResult(id, MemoryWriteAction.UPDATED, score)
    }

    private fun findExactDuplicate(content: String, category: MemoryCategory, candidates: List<Memory>): Memory? {
        val norm = MemoryHardeningHelper.normalizeForDedupe(content)
        val sameCategory = candidates.filter { it.jarvisCategory == category.jarvisCategory }
        sameCategory.firstOrNull { MemoryHardeningHelper.normalizeForDedupe(it.content) == norm }?.let { return it }
        // Near-duplicate for stable categories: high word overlap = same fact being restated.
        if (category == MemoryCategory.PREFERENCES || category == MemoryCategory.IDENTITY || category == MemoryCategory.PROJECTS) {
            val contentWords = norm.split(" ").filter { it.length > 3 }.toSet()
            if (contentWords.size >= 2) {
                for (existing in sameCategory) {
                    val existingWords = MemoryHardeningHelper.normalizeForDedupe(existing.content)
                        .split(" ").filter { it.length > 3 }.toSet()
                    if (existingWords.isEmpty()) continue
                    val overlap = contentWords.intersect(existingWords).size
                    val union = contentWords.union(existingWords).size
                    val jaccard = if (union > 0) overlap.toFloat() / union else 0f
                    val overlapRatio = overlap.toFloat() / contentWords.size
                    if ((jaccard >= 0.5f || overlapRatio >= 0.6f) && overlap >= 2) return existing
                }
            }
        }
        return null
    }

    private suspend fun maybeSummarize(exchange: MemoryExchange, settings: MemorySettings): Boolean {
        if (exchange.conversationId.isBlank()) return false
        if (exchange.messageCount < settings.summarizationInterval) return false
        val last = store.getSummary(exchange.conversationId)
        if (last != null && exchange.messageCount < last.messageCount + settings.summarizationInterval) return false
        val messages = exchange.recentMessages + listOfNotNull(
            exchange.userMessage.takeIf { it.isNotBlank() }?.let { "user" to it },
            exchange.assistantResponse.takeIf { it.isNotBlank() }?.let { "assistant" to it }
        )
        val summary = intelligence.summarize(exchange.conversationId, last?.summary, messages).getOrNull()
            ?: return false
        val now = System.currentTimeMillis()
        store.upsertSummary(
            MemorySummary(
                conversationId = exchange.conversationId,
                summary = summary,
                messageCount = exchange.messageCount,
                createdAt = last?.createdAt ?: now,
                updatedAt = now
            )
        )
        return true
    }

    // ── Retrieval / context ──────────────────────────────────────────────────

    /**
     * Top relevant memories — hybrid retrieval: Jarvis' semantic index
     * (hashing or the installed neural embedder) + the persisted AndroLLM
     * neural index (when an embedding source is configured) + keyword/tag
     * matches + pinned memories, joined with metadata and narrowed by [filters].
     */
    suspend fun retrieve(
        query: String,
        topK: Int = 5,
        filters: MemorySearchFilters = MemorySearchFilters()
    ): List<MemorySearchResult> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val clean = query.trim()
        val byId = LinkedHashMap<Long, MemorySearchResult>()
        val all = repository.getAllMemories()
        val entitiesById = all.associateBy { it.id }
        val allowed: Set<Long>? = if (filters.isEmpty) null else filterIds(all, filters)
        fun admit(id: Long) = allowed == null || id in allowed

        val semantic = if (clean.isBlank()) emptyList() else runCatching {
            vectorEngine.searchSimilarMemories(clean, topK = topK.coerceAtLeast(1) * 2, minScore = RESCUE_THRESHOLD)
        }.getOrDefault(emptyList())
        for (match in semantic) {
            val entity = entitiesById[match.memory.memoryId] ?: continue
            if (!admit(entity.id)) continue
            byId[entity.id] = MemorySearchResult(toDomain(entity), match.similarityScore)
        }
        // Persisted neural vectors (AndroLLM CosineVectorIndex) — fused by max score.
        if (clean.isNotBlank()) {
            val neural = runCatching {
                embeddingIndex.search(clean, topK.coerceAtLeast(1) * 2, allowed, all)
            }.onFailure { logger.warn("Neural retrieval failed: ${it.message}") }.getOrDefault(emptyList())
            for ((id, score) in neural) {
                if (score < RESCUE_THRESHOLD) continue
                val entity = entitiesById[id] ?: continue
                val existing = byId[id]
                if (existing == null || existing.score < score) {
                    byId[id] = existing?.copy(score = score) ?: MemorySearchResult(toDomain(entity), score)
                }
            }
        }
        if (clean.length >= 3) {
            val keywordIds = LinkedHashSet<Long>()
            repository.searchMemories(clean).take(topK).forEach { keywordIds += it.id }
            runCatching { store.memoryIdsForTagQuery(clean) }.getOrDefault(emptySet()).forEach { keywordIds += it }
            for (id in keywordIds) {
                val entity = entitiesById[id] ?: continue
                if (!admit(id)) continue
                val existing = byId[id]
                byId[id] = existing?.copy(matchedByKeyword = true, score = existing.score + HYBRID_KEYWORD_BOOST)
                    ?: MemorySearchResult(toDomain(entity), KEYWORD_FALLBACK_SCORE, matchedByKeyword = true)
            }
        }
        val now = System.currentTimeMillis()
        for ((id, meta) in MemoryMetaRegistry.snapshot()) {
            if (!meta.isPinned || byId.containsKey(id) || MemoryMetaRegistry.isHidden(id, now)) continue
            if (!admit(id)) continue
            val entity = entitiesById[id] ?: continue
            byId[id] = MemorySearchResult(toDomain(entity), 0f)
        }
        val results = byId.values
            .filter { filters.includeArchived || !it.memory.isArchived }
            .sortedWith(compareByDescending<MemorySearchResult> { it.memory.isPinned }.thenByDescending { it.score })
            .toList()
        val elapsed = System.currentTimeMillis() - t0
        lastRetrievalMs = elapsed
        avgRetrievalMs = if (retrievalSamples == 0L) elapsed else (avgRetrievalMs * retrievalSamples + elapsed) / (retrievalSamples + 1)
        retrievalSamples++
        logger.debug("Retrieved ${results.size} memory(s) in ${elapsed}ms")
        results
    }

    /** Ids of memories matching [filters] (metadata pre-filtering). */
    private fun filterIds(all: List<MemoryEntity>, filters: MemorySearchFilters): Set<Long> {
        val metas = MemoryMetaRegistry.snapshot()
        val now = System.currentTimeMillis()
        val projectName = filters.projectId?.let { pid -> projectNameCache[pid] }
        val wantedTags = filters.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val minImportance = maxOf(filters.minImportance, filters.minPriority)
        return all.asSequence().filter { e ->
            val m = AndroMemoryStore.toDomain(e, metas[e.id])
            (filters.category == null || m.category == filters.category) &&
                (filters.projectId == null || (projectName != null && m.projectName == projectName)) &&
                (filters.chatId == null || m.chatId == filters.chatId) &&
                (filters.type == null || m.type == filters.type) &&
                (!filters.pinnedOnly || m.isPinned) &&
                (filters.includeArchived || !m.isArchived) &&
                (filters.includeExpired || m.expiryAt == null || m.expiryAt > now || m.isPinned) &&
                m.effectivePriority >= minImportance &&
                (wantedTags.isEmpty() || m.tags.any { it in wantedTags })
        }.map { it.id }.toSet()
    }

    /** project id -> name, refreshed by [getProjects]. */
    private val projectNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Memories + summaries formatted for injection into a system prompt.
     * Never throws — returns an empty context on any failure so chat continues.
     */
    suspend fun buildContext(
        userQuery: String,
        conversationId: String? = null,
        maxChars: Int = ContextBuilder.DEFAULT_MAX_CHARS
    ): MemoryContext = try {
        val t0 = System.currentTimeMillis()
        val settings = settingsStore.current()
        val memories = retrieve(userQuery, settings.maxContextMemories.coerceAtLeast(1))
        val summaries = if (settings.maxContextSummaries <= 0) emptyList() else {
            val own = conversationId?.let { store.getSummary(it) }
            (listOfNotNull(own) + store.recentSummaries(settings.maxContextSummaries))
                .distinctBy { it.conversationId }
                .take(settings.maxContextSummaries)
        }
        val selected = ContextBuilder.selectMemories(memories, settings.maxContextMemories)
        val text = ContextBuilder.buildSystemText(selected, summaries, settings.maxContextMemories, settings.maxContextSummaries, maxChars)
        MemoryContext(selected, summaries, System.currentTimeMillis() - t0, text)
    } catch (e: Exception) {
        Log.w(TAG, "buildContext failed: ${e.message}")
        MemoryContext()
    }

    // ── CRUD for the settings section ────────────────────────────────────────

    suspend fun getMemories(): List<Memory> = withContext(Dispatchers.IO) {
        val metas = MemoryMetaRegistry.snapshot()
        repository.getAllMemories()
            .map { AndroMemoryStore.toDomain(it, metas[it.id]) }
            .sortedWith(compareByDescending<Memory> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    /** Memories narrowed by [filters] (project / tag / category / pinned ...). */
    suspend fun getMemories(filters: MemorySearchFilters): List<Memory> = withContext(Dispatchers.IO) {
        if (filters.isEmpty) return@withContext getMemories()
        if (filters.projectId != null && projectNameCache[filters.projectId] == null) getProjects()
        val all = repository.getAllMemories()
        val allowed = filterIds(all, filters)
        val metas = MemoryMetaRegistry.snapshot()
        all.filter { it.id in allowed }
            .map { AndroMemoryStore.toDomain(it, metas[it.id]) }
            .sortedWith(compareByDescending<Memory> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    // ── Projects / tags / relationships ──────────────────────────────────────

    suspend fun getProjects(): List<Project> = withContext(Dispatchers.IO) {
        store.getProjects().also { list ->
            projectNameCache.clear()
            list.forEach { projectNameCache[it.id] = it.name }
        }
    }

    suspend fun createProject(name: String, description: String = ""): Result<Project> = withContext(Dispatchers.IO) {
        runCatching { store.createProject(name, description).also { projectNameCache[it.id] = it.name } }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { store.deleteProject(projectId) }
    }

    suspend fun getTags(): List<Tag> = withContext(Dispatchers.IO) { store.getTags() }

    suspend fun deleteTag(tagId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { store.deleteTag(tagId) }
    }

    suspend fun getRelationships(): List<Relationship> = withContext(Dispatchers.IO) { store.getRelationships() }

    suspend fun addRelationship(fromMemoryId: Long, toMemoryId: Long, type: String): Result<Relationship?> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(repository.getMemoryById(fromMemoryId) != null) { "Memory #$fromMemoryId not found" }
                require(repository.getMemoryById(toMemoryId) != null) { "Memory #$toMemoryId not found" }
                store.addRelationship(fromMemoryId, toMemoryId, type)
            }
        }

    suspend fun deleteRelationship(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { store.deleteRelationship(id) }
    }

    /** Assigns (or clears, with null/blank) a memory's project. */
    suspend fun setProject(memoryId: Long, projectName: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            val base = store.getMeta(memoryId) ?: store.defaultMetaFor(entity)
            store.upsertMeta(base.copy(projectName = projectName?.trim()?.take(64)?.ifBlank { null }, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Replaces a memory's tags. */
    suspend fun setTags(memoryId: Long, tags: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            val base = store.getMeta(memoryId) ?: store.defaultMetaFor(entity)
            store.upsertMeta(base.copy(tags = normalizeTags(tags).distinct().take(10).joinToString(","), updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun setPinned(memoryId: Long, pinned: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            store.setPinned(entity, pinned)
        }
    }

    suspend fun setArchived(memoryId: Long, archived: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            store.setArchived(entity, archived)
        }
    }

    suspend fun deleteMemory(memoryId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            repository.deleteMemoryById(memoryId)
            store.deleteMeta(memoryId)
            embeddingIndex.remove(memoryId)
        }
    }

    /** Writes every memory (+ metadata and summaries) to a JSON file and returns it. */
    suspend fun exportMemories(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val metas = MemoryMetaRegistry.snapshot()
            val items = repository.getAllMemories().map { e ->
                val m = metas[e.id]
                MemoryExportItem(
                    id = e.id,
                    category = e.category,
                    key = e.key,
                    value = e.value,
                    content = e.rawText,
                    timestamp = e.timestamp,
                    androCategory = m?.androCategory,
                    pinned = m?.isPinned ?: false,
                    archived = m?.isArchived ?: false,
                    priority = m?.priority ?: 1,
                    type = m?.type ?: MemoryType.LONG_TERM.name,
                    expiryAt = m?.expiryAt,
                    accessCount = m?.accessCount ?: 0,
                    tags = m?.tagList ?: emptyList(),
                    project = m?.projectName,
                    source = m?.source ?: Memory.SOURCE_JARVIS
                )
            }
            val summaries = store.recentSummaries(1000).map {
                MemoryExportSummary(it.conversationId, it.summary, it.messageCount, it.updatedAt)
            }
            val relationships = store.getRelationships().map {
                MemoryExportRelationship(from = it.fromMemoryId, to = it.toMemoryId, type = it.type)
            }
            val projects = store.getProjects().map { MemoryExportProject(it.name, it.description) }
            val payload = MemoryExportFile(EXPORT_VERSION, System.currentTimeMillis(), items, summaries, relationships, projects)
            val dir = (appContext.getExternalFilesDir("exports") ?: File(appContext.filesDir, "exports")).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "jarvis_memories_$stamp.json")
            file.writeText(json.encodeToString(MemoryExportFile.serializer(), payload))
            logger.info("Exported ${items.size} memories, ${relationships.size} relationship(s)")
            file
        }
    }

    /**
     * Imports memories from a JSON export (this format, v1 or v2). Every item
     * goes through the normal write pipeline (policy, security, dedupe), so
     * duplicates update existing rows instead of creating new ones; pin/archive
     * flags, tags, project, priority and relationships are restored. Ported from
     * AndroLLM MemoryRepository.importMemories.
     */
    suspend fun importMemories(file: File): Result<MemoryImportResult> = withContext(Dispatchers.IO) {
        runCatching { importMemories(file.readText()) }
    }

    suspend fun importMemories(rawJson: String): MemoryImportResult = withContext(Dispatchers.IO) {
        val dto = json.decodeFromString(MemoryExportFile.serializer(), rawJson)
        val settings = settingsStore.current()
        var inserted = 0
        var updated = 0
        var skipped = 0
        val idMap = HashMap<Long, Long>()
        dto.projects.forEach { p -> runCatching { store.createProject(p.name, p.description) } }
        for (item in dto.memories) {
            val text = item.content.ifBlank { item.value }.trim()
            if (text.isEmpty()) {
                skipped++
                continue
            }
            val category = item.androCategory?.let { MemoryCategory.fromName(it) } ?: MemoryCategory.fromJarvisCategory(item.category)
            val result = writeMemory(
                ExtractedMemory(text, category, item.priority.coerceIn(1, 5), item.tags, item.project),
                MemoryExchange.manual(),
                settings,
                item.source.ifBlank { Memory.SOURCE_MANUAL }
            )
            when (result.action) {
                MemoryWriteAction.INSERTED -> {
                    inserted++
                    // Restore export fidelity: pin/archive flags, type, tags, project.
                    store.getMeta(result.memoryId)?.let { meta ->
                        store.upsertMeta(
                            meta.copy(
                                isPinned = item.pinned,
                                isArchived = item.archived,
                                type = item.type,
                                expiryAt = if (item.pinned) null else item.expiryAt ?: meta.expiryAt,
                                tags = (meta.tagList + normalizeTags(item.tags)).distinct().take(10).joinToString(","),
                                projectName = meta.projectName ?: item.project,
                                createdAt = item.timestamp.takeIf { it > 0 } ?: meta.createdAt
                            )
                        )
                    }
                }
                MemoryWriteAction.UPDATED -> updated++
                MemoryWriteAction.SKIPPED -> skipped++
            }
            if (result.memoryId > 0) idMap[item.id] = result.memoryId
        }
        var relationships = 0
        for (rel in dto.relationships) {
            val from = idMap[rel.from] ?: continue
            val to = idMap[rel.to] ?: continue
            if (runCatching { store.addRelationship(from, to, rel.type) }.getOrNull() != null) relationships++
        }
        totalInserted += inserted
        totalUpdated += updated
        logger.info("Import: +$inserted ~$updated -$skipped, $relationships relationship(s)")
        MemoryImportResult(inserted, updated, skipped, relationships)
    }

    /** Snapshot for the Memory Inspector. */
    suspend fun inspectorStats(): MemoryInspectorStats = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val ids = runCatching { repository.getAllMemoryIds() }.getOrDefault(emptyList())
        val signature = runCatching {
            com.jarvis.assistant.ai.memory.TextEmbeddingEngine.getInstance(appContext).embedderSignature()
        }.getOrDefault("")
        MemoryInspectorStats(
            memoryCount = ids.size,
            embeddingCount = embeddingIndex.embeddingCount(),
            vectorCount = runCatching { vectorEngine.indexedMemoryCount() }.getOrDefault(0) + embeddingIndex.vectorCount,
            projectCount = runCatching { store.projectCount() }.getOrDefault(0),
            tagCount = runCatching { store.tagCount() }.getOrDefault(0),
            summaryCount = runCatching { store.summaryCount() }.getOrDefault(0),
            relationshipCount = runCatching { store.relationshipCount() }.getOrDefault(0),
            avgRetrievalMs = avgRetrievalMs,
            lastRetrievalMs = lastRetrievalMs,
            lastEmbeddingMs = embeddingIndex.lastEmbeddingMs,
            lastExtractionMs = lastExtractionMs,
            totalExtractions = totalExtractions,
            totalEmbeddings = embeddingIndex.totalEmbeddings,
            totalInserted = totalInserted,
            totalUpdated = totalUpdated,
            retrievalCount = settings.maxContextMemories,
            similarityThreshold = settings.similarityThreshold,
            embeddingSource = settings.embeddingSource.label,
            embedderSignature = signature,
            embeddingModelPath = settings.embeddingModelPath,
            cloudEmbeddingModel = settings.cloudEmbeddingModel,
            embeddingDimension = embeddingIndex.provider.dimension,
            embeddingModelLoaded = embeddingIndex.provider.isModelLoaded(),
            enabled = settings.extractionEnabled,
            logs = logger.recent(60)
        )
    }

    /** Applies the inspector log level. */
    fun setLogLevel(level: MemoryLogLevel) {
        settingsStore.setLogLevel(level)
        logger.minLevel = level
    }

    suspend fun stats(): MemoryStats = withContext(Dispatchers.IO) {
        val allMemories = runCatching { repository.getAllMemories() }.getOrDefault(emptyList())
        val ids = allMemories.map { it.id }.toSet()
        var indexed = runCatching { vectorEngine.indexedMemoryCount() }.getOrDefault(0)
        if (indexed < ids.size && allMemories.isNotEmpty()) {
            runCatching { vectorEngine.syncWithRoomMemories(allMemories) }
            indexed = runCatching { vectorEngine.indexedMemoryCount() }.getOrDefault(ids.size)
        }
        val metas = MemoryMetaRegistry.snapshot().filterKeys { it in ids }.values
        MemoryStats(
            memoryCount = ids.size,
            indexedCount = indexed,
            pinnedCount = metas.count { it.isPinned },
            archivedCount = metas.count { it.isArchived },
            extractedCount = metas.count { it.source == Memory.SOURCE_EXTRACTED },
            summaryCount = runCatching { store.summaryCount() }.getOrDefault(0),
            lastIndexingAt = settingsStore.lastIndexingAt(),
            lastIndexingResult = settingsStore.lastIndexingResult(),
            lastExtractionAt = settingsStore.lastExtractionAt(),
            lastExtractionResult = settingsStore.lastExtractionResult()
        )
    }

    /** Explicitly synchronizes and re-indexes all Room memories into the Vector Engine. */
    suspend fun syncAllMemories(memories: List<com.jarvis.assistant.data.memory.MemoryEntity>): Int = withContext(Dispatchers.IO) {
        val all = if (memories.isNotEmpty()) memories else runCatching { repository.getAllMemories() }.getOrDefault(emptyList())
        runCatching { vectorEngine.syncWithRoomMemories(all) }
        runCatching { vectorEngine.indexedMemoryCount() }.getOrDefault(all.size)
    }

    // ── helpers ──

    private suspend fun loadCandidates(): List<Memory> {
        val metas = MemoryMetaRegistry.snapshot()
        return repository.getAllMemories()
            .filterNot { metas[it.id]?.isArchived == true }
            .map { AndroMemoryStore.toDomain(it, metas[it.id]) }
    }

    private suspend fun toDomain(entity: MemoryEntity): Memory =
        AndroMemoryStore.toDomain(entity, store.getMeta(entity.id))

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase().replace(",", " ").take(32) }.filter { it.isNotEmpty() }

    private fun skipped(reason: String): MemoryWriteResult {
        Log.d(TAG, "Skipped memory: $reason")
        return MemoryWriteResult(0L, MemoryWriteAction.SKIPPED, reason = reason)
    }

    companion object {
        private const val TAG = "AndroMemoryPipeline"
        const val DEFAULT_CONVERSATION_ID = "jarvis_main"
        private const val AUDIO_QUERY_PLACEHOLDER = "(Audio query)"
        private const val AUDIO_RESPONSE_PLACEHOLDER = "(Audio response)"
        private const val EXPORT_VERSION = 2
        private const val MAX_MEMORY_LENGTH = 800
        private const val MERGE_MARGIN = 0.08f
        /** Rescue floor: semantic hits below the strict threshold but above this are kept. */
        private const val RESCUE_THRESHOLD = 0.30f
        private const val KEYWORD_FALLBACK_SCORE = 0.6f
        private const val HYBRID_KEYWORD_BOOST = 0.06f
        private val WHITESPACE = Regex("\\s+")

        @Volatile
        private var instance: AndroMemoryPipeline? = null

        fun getInstance(context: Context): AndroMemoryPipeline =
            instance ?: synchronized(this) {
                instance ?: AndroMemoryPipeline(context.applicationContext).also { instance = it }
            }

        /**
         * Static entry point for Jarvis' MemoryManager (which has no Context).
         * Cheap flag check first; never throws.
         */
        fun onJarvisTurnIfEnabled(userText: String, assistantText: String, history: List<ChatTurn>) {
            runCatching {
                val app = JarvisApp.instance
                if (!AndroLlmFlags.isEnabled(app, AndroLlmFlags.MEMORY_EXTRACTION)) return
                getInstance(app).onJarvisTurn(userText, assistantText, history)
            }.onFailure { Log.w(TAG, "onJarvisTurnIfEnabled failed: ${it.message}") }
        }
    }
}

@Serializable
internal data class MemoryExportFile(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val memories: List<MemoryExportItem> = emptyList(),
    val summaries: List<MemoryExportSummary> = emptyList(),
    /** v2+: typed links between memories (ids are the exported ids). */
    val relationships: List<MemoryExportRelationship> = emptyList(),
    /** v2+: named projects (descriptions survive a round trip). */
    val projects: List<MemoryExportProject> = emptyList()
)

/** A relationship in the export file (ported from AndroLLM MemoryExportRelationship). */
@Serializable
internal data class MemoryExportRelationship(
    val from: Long,
    val to: Long,
    val type: String
)

@Serializable
internal data class MemoryExportProject(
    val name: String,
    val description: String = ""
)

@Serializable
internal data class MemoryExportItem(
    val id: Long = 0L,
    val category: String = "general",
    val key: String = "",
    val value: String = "",
    val content: String = "",
    val timestamp: Long = 0L,
    val androCategory: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val priority: Int = 1,
    val type: String = "LONG_TERM",
    val expiryAt: Long? = null,
    val accessCount: Int = 0,
    val tags: List<String> = emptyList(),
    val project: String? = null,
    val source: String = "jarvis"
)

@Serializable
internal data class MemoryExportSummary(
    val conversationId: String,
    val summary: String,
    val messageCount: Int,
    val updatedAt: Long
)
