package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.memory.MemoryWritePolicy
import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.Memory
import com.jarvis.assistant.androllm.memory.MemoryCategory
import com.jarvis.assistant.androllm.memory.MemoryContext
import com.jarvis.assistant.androllm.memory.MemoryExchange
import com.jarvis.assistant.androllm.memory.MemoryImportResult
import com.jarvis.assistant.androllm.memory.MemoryInspectorStats
import com.jarvis.assistant.androllm.memory.MemoryLogLevel
import com.jarvis.assistant.androllm.memory.MemorySearchFilters
import com.jarvis.assistant.androllm.memory.MemorySearchResult
import com.jarvis.assistant.androllm.memory.MemorySettings
import com.jarvis.assistant.androllm.memory.MemorySettingsStore
import com.jarvis.assistant.androllm.memory.MemoryStats
import com.jarvis.assistant.androllm.memory.MemorySummary
import com.jarvis.assistant.androllm.memory.MemoryType
import com.jarvis.assistant.androllm.memory.MemoryWriteAction
import com.jarvis.assistant.androllm.memory.MemoryWriteResult
import com.jarvis.assistant.androllm.memory.MemoryWriteSummary
import com.jarvis.assistant.androllm.memory.Project
import com.jarvis.assistant.androllm.memory.Relationship
import com.jarvis.assistant.androllm.memory.Tag
import com.jarvis.assistant.androllm.memory.classify.MemoryClassifier
import com.jarvis.assistant.androllm.memory.context.ContextBuilder
import com.jarvis.assistant.androllm.memory.correction.MemoryCorrectionResolver
import com.jarvis.assistant.androllm.memory.embedding.MemoryEmbeddingIndex
import com.jarvis.assistant.androllm.memory.filter.MemoryContentValidator
import com.jarvis.assistant.androllm.memory.hardening.MemoryHardeningHelper
import com.jarvis.assistant.androllm.memory.intelligence.MemoryIntelligence
import com.jarvis.assistant.androllm.memory.intelligence.RoutingMemoryIntelligence
import com.jarvis.assistant.androllm.memory.store.MemoryMetaEntity
import com.jarvis.assistant.androllm.memory.store.MemoryMetaRegistry
import com.jarvis.assistant.androllm.memory.util.MemoryLogger
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Jarvis' memory manager: short-term dialogue memory, rule-based preference
 * learning, direct recall responses and the Gemini system-prompt context —
 * PLUS the AndroLLM memory pipeline that used to live in `AndroMemoryPipeline`
 * (LLM extraction → policy/security/confidence → update-or-insert → rolling
 * summary, hybrid retrieval, context building, CRUD for the settings section,
 * projects / tags / relationships, export / import, inspector stats).
 *
 * Everything writes ordinary Jarvis memories through [MemoryRepository]
 * (`jarvis_memory.db`); lifecycle metadata lives in the AndroLLM sidecar
 * database behind the same repository. Both schemas are unchanged.
 *
 * LLM calls go through [RoutingMemoryIntelligence] (Jarvis' quiet text routing /
 * on-device model); never through the Gemini Live websocket.
 */
class MemoryManager(
    private val repository: MemoryRepository,
    private val preferences: AppPreferences,
    private val chatRepository: com.jarvis.assistant.data.repository.ChatRepository,
    private val scope: CoroutineScope,
    context: Context
) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val MAX_SHORT_TERM_TURNS = 30

        // ── Former AndroMemoryPipeline companion ──
        private const val PIPELINE_TAG = "AndroMemoryPipeline"
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

        /** Consecutive extraction failures before per-turn extraction is paused. */
        private const val EXTRACTION_FAILURE_LIMIT = 3
        /** How long per-turn extraction stays paused after repeated failures. */
        private const val EXTRACTION_BACKOFF_MS = 10 * 60_000L

        /**
         * Process-wide accessor (replaces `AndroMemoryPipeline.getInstance(context)`):
         * there is exactly one MemoryManager, owned by [JarvisApp].
         */
        fun getInstance(context: Context): MemoryManager =
            ((context.applicationContext as? JarvisApp) ?: JarvisApp.instance).memoryManager
    }

    private val appContext: Context = context.applicationContext

    // --- Short-Term Memory (Preloaded with persistent history) ---
    private val _shortTermHistory = MutableStateFlow<List<ChatTurn>>(emptyList())
    val shortTermHistory: StateFlow<List<ChatTurn>> = _shortTermHistory.asStateFlow()
    private val shortTermSummary = StringBuilder()

    // ── Former AndroMemoryPipeline state ─────────────────────────────────────
    val settingsStore = MemorySettingsStore(appContext)
    private val intelligence: MemoryIntelligence by lazy {
        RoutingMemoryIntelligence.create(appContext) { settingsStore.current().route }
    }
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val jobMutex = Mutex()
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val logger = MemoryLogger
    val embeddingIndex: MemoryEmbeddingIndex by lazy { MemoryEmbeddingIndex.getInstance(appContext) }

    // Inspector timings (ported from AndroLLM MemoryRepository)
    @Volatile private var lastRetrievalMs = 0L
    @Volatile private var avgRetrievalMs = 0L
    @Volatile private var retrievalSamples = 0L
    @Volatile private var lastExtractionMs = 0L
    @Volatile private var totalExtractions = 0L
    @Volatile private var totalInserted = 0L
    @Volatile private var totalUpdated = 0L

    // Extraction failure backoff (a dead cloud key must not spam every voice turn).
    @Volatile private var consecutiveExtractionFailures = 0
    @Volatile private var extractionPausedUntil = 0L

    /** project id -> name, refreshed by [getProjects]. */
    private val projectNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val vectorEngine: VectorMemoryEngine
        get() = ((appContext as? JarvisApp) ?: JarvisApp.instance).vectorMemoryEngine

    init {
        logger.minLevel = settingsStore.current().logLevel

        // Preload recent persistent chat turns so memory is continuous across app restarts
        scope.launch(Dispatchers.IO) {
            chatRepository.awaitLoaded()
            val savedTurns = chatRepository.turns.value
            _shortTermHistory.value = savedTurns.takeLast(MAX_SHORT_TERM_TURNS)
            Log.i(TAG, "Preloaded ${_shortTermHistory.value.size} persistent turns into active memory.")
        }
    }

    fun addShortTermTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()
        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val current = _shortTermHistory.value.toMutableList()
        val turn = ChatTurn(
            userTranscript = cleanUser,
            jarvisResponse = cleanJarvis
        )
        current.add(turn)

        if (current.size > MAX_SHORT_TERM_TURNS) {
            val pruned = current.removeAt(0)
            if (shortTermSummary.length < 800) {
                shortTermSummary.append("${pruned.userTranscript} -> ${pruned.jarvisResponse}; ")
            }
        }
        _shortTermHistory.value = current

        // Trigger automatic semantic extraction on user input
        learnPreferencesFromConversation(cleanUser)

        // AndroLLM LLM memory extraction + rolling summary (background; ON unless
        // AndroLlmFlags.MEMORY_EXTRACTION was switched off in Settings).
        runCatching { onJarvisTurn(cleanUser, cleanJarvis, current) }
            .onFailure { Log.w(PIPELINE_TAG, "onJarvisTurn failed: ${it.message}") }
    }

    fun clearShortTermMemory() {
        _shortTermHistory.value = emptyList()
        shortTermSummary.clear()
    }

    fun getShortTermContextSummary(): String {
        return shortTermSummary.toString().trim()
    }

    // --- Long-Term Memory (Persistent in SQLite Room Database) ---

    suspend fun rememberFact(rawInput: String): String {
        val clean = rawInput
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(remember that|remember to|remember|yaad rakhna ki|yaad rakhna)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return "I didn't catch what you'd like me to remember, Sir."
        }

        val decision = com.jarvis.assistant.ai.memory.MemoryWritePolicy.decide(clean)
        if (decision is com.jarvis.assistant.ai.memory.MemoryWritePolicy.Decision.Ignore) {
            if (decision.reason.contains("secret")) {
                return "Sir, for your security, credentials and API keys are not stored in memory."
            }
            if (decision.reason == "injection") {
                return "Sir, that looks like an instruction override, so I have not stored it in memory."
            }
        }

        // If this is a correction of a previous fact, update rather than creating duplicates.
        // Bug fix: the old code searched for the text AFTER the correction signal
        // (the NEW value), which never matched the old memory. The resolver finds
        // the superseded memory via contradiction detection, the named old value,
        // or the corrected subject.
        if (decision is com.jarvis.assistant.ai.memory.MemoryWritePolicy.Decision.Update) {
            val target = com.jarvis.assistant.androllm.memory.correction.MemoryCorrectionResolver.findTarget(
                newStatement = clean,
                decision = decision,
                candidates = repository.getAllMemories().map {
                    com.jarvis.assistant.androllm.memory.correction.MemoryCorrectionResolver.Candidate(it.id, it.rawText, it.timestamp)
                }
            )
            val oldMem = target?.let { repository.getMemoryById(it.id) }
            if (oldMem != null) {
                repository.updateMemory(oldMem.copy(rawText = clean, value = clean, timestamp = System.currentTimeMillis()))
                val updateResponse = "Sir, I have updated your record from '${oldMem.rawText}' to: $clean."
                chatRepository.addTurn(clean, updateResponse)
                return updateResponse
            }
        }

        learnPreferencesFromConversation(clean)

        val lower = clean.lowercase(Locale.ROOT)
        val category = when {
            lower.contains("favorite") || lower.contains("prefer") || lower.contains("like") || lower.contains("song") || lower.contains("music") || lower.contains("app") || lower.contains("pasand") ->
                MemoryEntity.CATEGORY_PREFERENCES
            lower.contains("routine") || lower.contains("gym") || lower.contains("work") || lower.contains("wake up") || lower.contains("sleep") || lower.contains("at ") || lower.contains("pm") || lower.contains("am") ->
                MemoryEntity.CATEGORY_ROUTINES
            lower.contains("name") || lower.contains("birthday") || lower.contains("live in") || lower.contains("age") || lower.contains("i am") || lower.contains("my ") || lower.contains("mera") || lower.contains("meri") ->
                MemoryEntity.CATEGORY_PERSONAL
            lower.contains("call") || lower.contains("contact") || lower.contains("phone") || lower.contains("number") || lower.contains("friend") || lower.contains("brother") || lower.contains("sister") || lower.contains("mom") || lower.contains("dad") || lower.contains("bhai") || lower.contains("dost") ->
                MemoryEntity.CATEGORY_CONTACTS
            else ->
                MemoryEntity.CATEGORY_NOTES
        }

        val key = clean.take(40).trim()
        repository.saveMemory(
            rawText = clean,
            category = category,
            key = key,
            value = clean
        )
        Log.i(TAG, "Saved long-term memory in category '$category': $clean")
        val response = "Sir, I have permanently committed that to memory: $clean."
        chatRepository.addTurn(clean, response)
        return response
    }

    suspend fun forgetFact(query: String): String {
        val clean = query
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(forget that|forget my|forget about|forget|bhool jao)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return "Please tell me what you would like me to forget, Sir."
        }

        val deletedCount = repository.deleteMemoriesByKeyword(clean)
        val response = if (deletedCount > 0) {
            "Sir, I have erased $clean from my persistent memory."
        } else {
            "I couldn't find any memory matching '$clean'."
        }
        chatRepository.addTurn(clean, response)
        return response
    }

    suspend fun getWhatDoYouRemember(): String {
        val all = repository.getAllMemories()
        if (all.isEmpty()) {
            return "My memory core is currently clear, Sir. Tell me anything about your life, habits, or preferences and I will never forget it."
        }

        val count = all.size
        val topFacts = all.take(6).joinToString(". ") { it.rawText }
        val response = "Sir, I remember $count key facts about you in my Memory Core, including: $topFacts."
        chatRepository.addTurn("What do you remember about me?", response)
        return response
    }

    // --- Direct Recall Responses ---

    suspend fun getBirthdayResponse(): String {
        val bday = preferences.userBirthday
        if (bday.isNotBlank()) {
            val reply = "Sir, your birthday is $bday."
            chatRepository.addTurn("When is my birthday?", reply)
            return reply
        }
        val memories = repository.searchMemories("birthday")
        val mem = memories.firstOrNull()
        if (mem != null) {
            preferences.userBirthday = mem.value
            val reply = "Sir, your birthday is ${mem.value}."
            chatRepository.addTurn("When is my birthday?", reply)
            return reply
        }
        val notSet = "Sir, you haven't told me your birthday yet. You can tell me 'My birthday is 15th August' and I will save it."
        chatRepository.addTurn("When is my birthday?", notSet)
        return notSet
    }

    fun getFavoriteSongResponse(): String {
        val song = resolveSongQuery("my favorite song")
        val reply = "Sir, your favorite song is $song."
        chatRepository.addTurn("What is my favorite song?", reply)
        return reply
    }

    suspend fun getRoutineResponse(): String {
        val allMemories = repository.getAllMemories()
        val routines = allMemories.filter { it.category == MemoryEntity.CATEGORY_ROUTINES }
        if (routines.isNotEmpty()) {
            val summary = routines.joinToString("; ") { it.rawText }
            val reply = "Sir, here is your recorded daily routine: $summary."
            chatRepository.addTurn("What is my routine?", reply)
            return reply
        }
        if (preferences.dailyRoutine.isNotBlank()) {
            val reply = "Sir, your daily routine is: ${preferences.dailyRoutine}."
            chatRepository.addTurn("What is my routine?", reply)
            return reply
        }
        val notSet = "Sir, you haven't recorded your routine yet. Tell me 'I wake up at 7 am' or 'I go to the gym at 6 pm' and I will save it."
        chatRepository.addTurn("What is my routine?", notSet)
        return notSet
    }

    // --- Automatic Habit & Preference Learning ---

    fun trackSongPlay(rawQuery: String) {
        val clean = rawQuery
            .replace(Regex("(?i)\\s+(song|video|gana|track|music|audio)$"), "")
            .replace(Regex("(?i)^(play|chalao|bajao)\\s+"), "")
            .trim()

        if (clean.isBlank() || clean.equals("my favorite song", ignoreCase = true) || clean.equals("favorite song", ignoreCase = true)) return

        val currentFav = preferences.recordSongPlay(clean)
        Log.i(TAG, "Tracked song play: '$clean'. Current top favorite: '$currentFav'")

        scope.launch(Dispatchers.IO) {
            repository.saveMemory(
                rawText = "User played and likes the song $clean",
                category = MemoryEntity.CATEGORY_PREFERENCES,
                key = "song_$clean",
                value = clean
            )
            if (currentFav.equals(clean, ignoreCase = true)) {
                repository.deleteMemoriesByKeyword("favorite_song")
                repository.saveMemory(
                    rawText = "User's favorite song is $clean",
                    category = MemoryEntity.CATEGORY_PREFERENCES,
                    key = "favorite_song",
                    value = clean
                )
            }
        }
    }

    fun resolveSongQuery(rawQuery: String): String {
        val clean = rawQuery.lowercase(Locale.ROOT).trim()
        val isFavoriteQuery = clean == "my favorite song" || clean == "my favourite song" ||
                clean == "favorite song" || clean == "favourite song" ||
                clean == "my song" || clean == "my favorite" || clean == "my favourite" ||
                clean == "something i like" || clean == "a song i like" ||
                clean == "mera favorite gana" || clean == "mera favourite gana" ||
                clean == "mera favorite song" || clean == "mera favourite song" ||
                clean == "mera pasandida gana" || clean == "pasandida gana"

        if (!isFavoriteQuery) {
            return rawQuery
        }

        val prefFav = preferences.favoriteSong
        if (prefFav.isNotBlank()) {
            return prefFav
        }

        val mostPlayed = preferences.getSongPlayCounts().maxByOrNull { it.value }?.key
        if (!mostPlayed.isNullOrBlank()) {
            return mostPlayed
        }

        return "Tadipaar"
    }

    /**
     * Expanded Semantic Learning Engine: Extracts personal facts, preferences, routines,
     * relationships, and knowledge from spoken user conversation in English and Hinglish.
     * Everything learned is immediately committed to Room Database (MemoryRepository).
     */
    fun learnPreferencesFromConversation(userSpeech: String) {
        val clean = userSpeech.trim()
        if (clean.isBlank() || clean.length < 3) return

        scope.launch(Dispatchers.IO) {
            val lower = clean.lowercase(Locale.ROOT)

            // 1. EXPLICIT "REMEMBER THAT" / "YAAD RAKHNA" STATEMENTS
            val rememberMatch = Regex("(?i).*(?:remember\\s+that|remember\\s+to|remember|yaad\\s+rakhna\\s+ki|yaad\\s+rakhna)\\s+(.+)$").find(clean)
            if (rememberMatch != null) {
                val fact = rememberMatch.groupValues[1].trim()
                if (fact.length > 3) {
                    repository.saveMemory(
                        rawText = fact,
                        category = MemoryEntity.CATEGORY_NOTES,
                        key = fact.take(30),
                        value = fact
                    )
                    Log.i(TAG, "Learned explicit fact: $fact")
                }
            }

            // 2. USER NAME & IDENTITY
            val nameMatch = Regex("(?i).*\\b(?:my\\s+name\\s+is|call\\s+me|i\\s+am\\s+called|mera\\s+naam\\s+hai|mera\\s+naam)\\s+([a-zA-Z]+)(?:[.,!]|\\s+\$|\\s+hai)?").find(clean)
            if (nameMatch != null) {
                val name = nameMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (name.length > 1 && !listOf("jarvis", "sir", "boss", "you", "who", "not").contains(name.lowercase(Locale.ROOT))) {
                    preferences.userName = name
                    repository.saveMemory(
                        rawText = "User's preferred name is $name",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_name",
                        value = name
                    )
                    Log.i(TAG, "Learned user name: $name")
                }
            }

            // 3. BIRTHDAY EXTRACTION (English & Hinglish)
            val bdayMatch = Regex("(?i).*\\b(?:my\\s+birthday\\s+is|born\\s+on|my\\s+bday\\s+is)\\s+(?:on\\s+)?([a-zA-Z0-9\\s,]+?)(?:[.,!]|\$|\\s+sir|\\s+jarvis)").find(clean)
                ?: Regex("(?i).*\\b(?:mera\\s+)?(?:birthday|bday|janamdin)\\s+(?:hai\\s+)?([a-zA-Z0-9\\s]+?)(?:\\s+ko\\s+hai|\\s+ko|\\s+hai)?\$").find(clean)
                ?: Regex("(?i).*\\b([a-zA-Z0-9\\s]+?)\\s+(?:ko\\s+mera\\s+birthday\\s+hai|mera\\s+birthday\\s+hai).*").find(clean)

            if (bdayMatch != null) {
                var bday = bdayMatch.groupValues[1].trim()
                bday = bday.replace(Regex("(?i)^(on|ko|hai)\\s+"), "")
                    .replace(Regex("(?i)\\s+(ko|hai|sir|jarvis|please)$"), "")
                    .trim()
                if (bday.isNotBlank() && bday.length >= 3 && !listOf("sir", "jarvis", "today", "tomorrow", "when").contains(bday.lowercase(Locale.ROOT))) {
                    preferences.userBirthday = bday
                    repository.deleteMemoriesByKeyword("birthday")
                    repository.saveMemory(
                        rawText = "User's birthday is $bday",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_birthday",
                        value = bday
                    )
                    Log.i(TAG, "Learned user's birthday: $bday")
                }
            }

            // 4. USER LOCATION / RESIDENCE
            val locMatch = Regex("(?i).*\\b(?:i\\s+live\\s+in|i\\s+stay\\s+in|my\\s+hometown\\s+is|my\\s+city\\s+is|main\\s+rehta\\s+hoon|mera\\s+ghar\\s+hai)\\s+([a-zA-Z\\s]+?)(?:[.,!]|\$|\\s+me|\\s+mein)").find(clean)
            if (locMatch != null) {
                val loc = locMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (loc.isNotBlank() && loc.length > 2 && !listOf("here", "there", "home", "house").contains(loc.lowercase(Locale.ROOT))) {
                    preferences.userLocation = loc
                    repository.saveMemory(
                        rawText = "User lives in $loc",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_location",
                        value = loc
                    )
                    Log.i(TAG, "Learned user location: $loc")
                }
            }

            // 5. USER JOB / PROFESSION
            val jobMatch = Regex("(?i).*\\b(?:i\\s+work\\s+as\\s+(?:a\\s+)?|i\\s+am\\s+a\\s+|my\\s+job\\s+is\\s+(?:a\\s+)?|mera\\s+kaam\\s+hai\\s+)([a-zA-Z\\s]+?)(?:[.,!]|\$|\\s+engineer|\\s+developer|\\s+doctor)").find(clean)
            if (jobMatch != null) {
                val job = jobMatch.groupValues[1].trim()
                if (job.isNotBlank() && job.length > 2 && !listOf("human", "person", "user", "good", "bad").contains(job.lowercase(Locale.ROOT))) {
                    repository.saveMemory(
                        rawText = "User's profession/job is $job",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_profession",
                        value = job
                    )
                    Log.i(TAG, "Learned user profession: $job")
                }
            }

            // 6. FAVORITE SONG LEARNING
            val favSongMatch = Regex("(?i).*\\b(?:my\\s+favou?rite\\s+song\\s+is|i\\s+love\\s+the\\s+song|mera\\s+favou?rite\\s+gana\\s+hai|mera\\s+pasandida\\s+gana)\\s+([a-zA-Z0-9\\s]+?)(?:[.,!]|\$|\\s+by|\\s+hai)").find(clean)
            if (favSongMatch != null) {
                val song = favSongMatch.groupValues[1].trim()
                if (song.isNotBlank() && song.length > 2) {
                    preferences.favoriteSong = song
                    repository.deleteMemoriesByKeyword("favorite_song")
                    repository.saveMemory(
                        rawText = "User's favorite song is $song",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "favorite_song",
                        value = song
                    )
                    Log.i(TAG, "Learned user's favorite song: $song")
                }
            }

            // 7. FAVORITE APP LEARNING
            val appMatch = Regex("(?i).*\\b(?:my\\s+favou?rite\\s+app\\s+is|i\\s+use\\s+)([a-zA-Z0-9\\s]+?)(?:\\s+the\\s+most|\\s+app)?(?:[.,!]|\$)?$").find(clean)
            if (appMatch != null) {
                val app = appMatch.groupValues[1].trim()
                if (app.isNotBlank() && app.length > 2 && !listOf("this", "phone", "internet", "device").contains(app.lowercase(Locale.ROOT))) {
                    preferences.favoriteApp = app
                    repository.saveMemory(
                        rawText = "User's favorite app is $app",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "favorite_app",
                        value = app
                    )
                    Log.i(TAG, "Learned user's favorite app: $app")
                }
            }

            // 8. PERSONAL RELATIONSHIPS / CONTACTS
            val contactMatch = Regex("(?i).*\\b([a-zA-Z0-9]+)\\s+is\\s+my\\s+(brother|sister|friend|best\\s+friend|mom|dad|father|mother|cousin|boss|wife|husband)[.,!]?$").find(clean)
                ?: Regex("(?i).*\\b([a-zA-Z0-9]+)\\s+mera\\s+(bhai|dost|yaar|papa|mummy)[.,!]?$").find(clean)
            if (contactMatch != null) {
                val name = contactMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                val relation = contactMatch.groupValues[2].trim()
                if (name.isNotBlank() && name.length > 1 && !listOf("who", "he", "she", "this", "that", "it").contains(name.lowercase(Locale.ROOT))) {
                    repository.saveMemory(
                        rawText = "$name is user's $relation",
                        category = MemoryEntity.CATEGORY_CONTACTS,
                        key = "relation_$name",
                        value = relation
                    )
                    Log.i(TAG, "Learned personal relationship: $name is $relation")
                }
            }

            // 9. GENERAL PREFERENCES (e.g. food, vehicles, colors, hobbies)
            val prefMatch = Regex("(?i).*\\b(?:i\\s+love\\s+|i\\s+like\\s+|my\\s+favou?rite\\s+)([a-zA-Z0-9\\s]+?)\\s+(?:is|are)\\s+([a-zA-Z0-9\\s]+?)[.,!]?$").find(clean)
            if (prefMatch != null) {
                val topic = prefMatch.groupValues[1].trim()
                val prefValue = prefMatch.groupValues[2].trim()
                if (topic.isNotBlank() && prefValue.isNotBlank() && topic.length < 25 && prefValue.length < 40) {
                    repository.saveMemory(
                        rawText = "User's favorite $topic is $prefValue",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "pref_${topic.take(20)}",
                        value = prefValue
                    )
                    Log.i(TAG, "Learned general preference: $topic -> $prefValue")
                }
            }
        }
    }

    /**
     * Builds the complete prompt context for Gemini Live.
     * Contains:
     * 1. Core Profile
     * 2. All categorized Room database memories (persistent across restarts)
     * 3. Recent 15 conversation turns across sessions (persistent dialogue memory)
     */
    suspend fun getSystemPromptContext(): String {
        val all = repository.getAllMemories()
        val favSong = resolveSongQuery("my favorite song")
        val sb = StringBuilder("\n[USER PERSONAL PROFILE & MEMORY CORE - PERMANENT STORAGE]:\n")
        sb.append("- User's Preferred Name: ${preferences.userName.ifBlank { "Sir" }}\n")
        sb.append("- User's Birthday: ${preferences.userBirthday.ifBlank { "Not specified yet" }}\n")
        sb.append("- User's Known Favorite Song: $favSong\n")
        sb.append("- User's Favorite App: ${preferences.favoriteApp.ifBlank { "YouTube" }}\n")
        if (preferences.userLocation.isNotBlank()) {
            sb.append("- User's Location: ${preferences.userLocation}\n")
        }
        if (preferences.userAge.isNotBlank()) {
            sb.append("- User's Age: ${preferences.userAge} years old\n")
        }
        if (preferences.dailyRoutine.isNotBlank()) {
            sb.append("- User's Daily Routine: ${preferences.dailyRoutine}\n")
        }

        if (all.isNotEmpty()) {
            sb.append("\n[ALL PERMANENT MEMORIES FROM DATABASE]:\n")
            all.take(40).forEach { mem ->
                sb.append("- [${mem.category}] ${mem.rawText}\n")
            }
        }

        // Persistent Dialogue History across App Restarts
        val recentTurns = chatRepository.turns.value.takeLast(15)
        if (recentTurns.isNotEmpty()) {
            sb.append("\n[PERSISTENT DIALOGUE HISTORY ACROSS APP RESTARTS]:\n")
            sb.append("You remember what was discussed in previous conversations with the user:\n")
            recentTurns.forEach { turn ->
                val u = turn.userTranscript.trim()
                val j = turn.jarvisResponse.trim()
                if (u.isNotBlank() && j.isNotBlank() && u != "(Audio query)" && j != "(Audio response)") {
                    sb.append("User: \"$u\"\n")
                    sb.append("JARVIS: \"$j\"\n")
                }
            }
        }

        // Maya parity (WP6): standing rules + last 3 episode summaries + wiki index (<= ~800 chars).
        try {
            val maya = com.jarvis.assistant.maya.memory.MayaMemoryPromptContributor.render(appContext)
            if (maya.isNotBlank()) sb.append(System.lineSeparator()).append(maya).append(System.lineSeparator())
        } catch (e: Throwable) {
            Log.w(TAG, "Maya memory prompt block failed: ${e.message}")
        }

        return sb.toString()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Former AndroMemoryPipeline (method names unchanged)
    // ═════════════════════════════════════════════════════════════════════════

    // ── Hook from the Jarvis turn ────────────────────────────────────────────

    /**
     * Called after every Jarvis turn. No-op unless [AndroLlmFlags.MEMORY_EXTRACTION]
     * is enabled (default ON). Runs in the background, serialized, never blocking the caller.
     */
    fun onJarvisTurn(userText: String, assistantText: String, history: List<ChatTurn>) {
        if (!AndroLlmFlags.isMemoryExtractionEnabled(appContext)) return
        // Maya parity (WP6): incognito / auto-save off -> no automatic extraction.
        if (runCatching { !com.jarvis.assistant.maya.memory.MemoryPrefs.canStore(appContext) || !com.jarvis.assistant.maya.memory.MemoryPrefs.isAutoSave(appContext) }.getOrDefault(false)) return
        val user = userText.trim()
        val assistant = assistantText.trim()
        if (user.isBlank() || user == AUDIO_QUERY_PLACEHOLDER) return
        pipelineScope.launch {
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
                }.onFailure { Log.w(PIPELINE_TAG, "Background memory processing failed: ${it.message}") }
            }
        }
    }

    // ── Write pipeline ───────────────────────────────────────────────────────

    /** extract -> (dedupe/correct) -> update-or-insert -> summarize. */
    suspend fun processExchange(exchange: MemoryExchange): Result<MemoryWriteSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.current()
            if (!settings.extractionEnabled) return@runCatching MemoryWriteSummary()

            // Backoff: after repeated extraction failures (dead cloud key, offline
            // model missing, ...) skip the LLM calls for a while instead of
            // logging "Extraction unavailable" on every single voice turn.
            val pausedUntil = extractionPausedUntil
            if (pausedUntil > 0L && System.currentTimeMillis() < pausedUntil) {
                return@runCatching MemoryWriteSummary()
            }

            val t0 = System.currentTimeMillis()
            val extraction = intelligence.extract(exchange)
            lastExtractionMs = System.currentTimeMillis() - t0
            totalExtractions++
            val items = extraction.getOrElse {
                settingsStore.recordExtraction("Extraction unavailable: ${it.message}")
                logger.warn("Extraction unavailable: ${it.message}")
                noteExtractionFailure(it)
                emptyList()
            }
            if (extraction.isSuccess) {
                consecutiveExtractionFailures = 0
                extractionPausedUntil = 0L
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
            runCatching { repository.linkExchangeMemories(touched) }
                .onFailure { logger.warn("Relationship linking failed: ${it.message}") }
            val summarized = if (extraction.isSuccess) maybeSummarize(exchange, settings) else false
            if (extraction.isSuccess) {
                settingsStore.recordExtraction("+$inserted ~$updated -$skipped (extracted ${items.size})${if (summarized) ", summarized" else ""}")
            }
            logger.info("Exchange processed: +$inserted ~$updated -$skipped (extracted ${items.size}) summarized=$summarized in ${lastExtractionMs}ms")
            MemoryWriteSummary(inserted, updated, skipped, items.size, summarized)
        }
    }

    /** Counts a failed extraction; after [EXTRACTION_FAILURE_LIMIT] in a row, pauses extraction (logged once). */
    private fun noteExtractionFailure(error: Throwable) {
        val failures = consecutiveExtractionFailures + 1
        consecutiveExtractionFailures = failures
        if (failures >= EXTRACTION_FAILURE_LIMIT && extractionPausedUntil <= System.currentTimeMillis()) {
            extractionPausedUntil = System.currentTimeMillis() + EXTRACTION_BACKOFF_MS
            consecutiveExtractionFailures = 0
            val minutes = EXTRACTION_BACKOFF_MS / 60_000L
            Log.w(PIPELINE_TAG, "Memory extraction failed $failures times in a row (${error.message}); pausing extraction for $minutes min")
            logger.warn("Extraction paused for $minutes min after $failures consecutive failures")
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
        // Jarvis already keeps current-conversation context in the short-term
        // history above; short-term facts are not persisted long-term.
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
            repository.upsertMeta(
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
            Log.w(PIPELINE_TAG, "Metadata write failed for memory #$id: ${e.message}")
        }
        Log.i(PIPELINE_TAG, "Inserted memory #$id category=${category.name} type=${type.name}")
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
        val oldMeta = repository.getMeta(id)

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

        val base = oldMeta ?: repository.defaultMetaFor(entity)
        val mergedTags = (base.tagList + normalizeTags(item.tags)).distinct().take(10)
        val newPriority = maxOf(priority, if (oldMeta != null) base.priority else 1).coerceIn(1, 5)
        repository.upsertMeta(
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
        Log.i(PIPELINE_TAG, "Updated memory #$id (score=${score ?: "exact"}) -> '${newContent.take(40)}'")
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
        val last = repository.getSummary(exchange.conversationId)
        if (last != null && exchange.messageCount < last.messageCount + settings.summarizationInterval) return false
        val messages = exchange.recentMessages + listOfNotNull(
            exchange.userMessage.takeIf { it.isNotBlank() }?.let { "user" to it },
            exchange.assistantResponse.takeIf { it.isNotBlank() }?.let { "assistant" to it }
        )
        val summary = intelligence.summarize(exchange.conversationId, last?.summary, messages).getOrNull()
            ?: return false
        val now = System.currentTimeMillis()
        repository.upsertSummary(
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
            runCatching { repository.memoryIdsForTagQuery(clean) }.getOrDefault(emptySet()).forEach { keywordIds += it }
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
            val m = MemoryRepository.toDomain(e, metas[e.id])
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
            val own = conversationId?.let { repository.getSummary(it) }
            (listOfNotNull(own) + repository.recentSummaries(settings.maxContextSummaries))
                .distinctBy { it.conversationId }
                .take(settings.maxContextSummaries)
        }
        val selected = ContextBuilder.selectMemories(memories, settings.maxContextMemories)
        val text = ContextBuilder.buildSystemText(selected, summaries, settings.maxContextMemories, settings.maxContextSummaries, maxChars)
        MemoryContext(selected, summaries, System.currentTimeMillis() - t0, text)
    } catch (e: Exception) {
        Log.w(PIPELINE_TAG, "buildContext failed: ${e.message}")
        MemoryContext()
    }

    // ── CRUD for the settings section ────────────────────────────────────────

    suspend fun getMemories(): List<Memory> = withContext(Dispatchers.IO) {
        val metas = MemoryMetaRegistry.snapshot()
        repository.getAllMemories()
            .map { MemoryRepository.toDomain(it, metas[it.id]) }
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
            .map { MemoryRepository.toDomain(it, metas[it.id]) }
            .sortedWith(compareByDescending<Memory> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    // ── Projects / tags / relationships ──────────────────────────────────────

    suspend fun getProjects(): List<Project> = withContext(Dispatchers.IO) {
        repository.getProjects().also { list ->
            projectNameCache.clear()
            list.forEach { projectNameCache[it.id] = it.name }
        }
    }

    suspend fun createProject(name: String, description: String = ""): Result<Project> = withContext(Dispatchers.IO) {
        runCatching { repository.createProject(name, description).also { projectNameCache[it.id] = it.name } }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { repository.deleteProject(projectId) }
    }

    suspend fun getTags(): List<Tag> = withContext(Dispatchers.IO) { repository.getTags() }

    suspend fun deleteTag(tagId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { repository.deleteTag(tagId) }
    }

    suspend fun getRelationships(): List<Relationship> = withContext(Dispatchers.IO) { repository.getRelationships() }

    suspend fun addRelationship(fromMemoryId: Long, toMemoryId: Long, type: String): Result<Relationship?> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(repository.getMemoryById(fromMemoryId) != null) { "Memory #$fromMemoryId not found" }
                require(repository.getMemoryById(toMemoryId) != null) { "Memory #$toMemoryId not found" }
                repository.addRelationship(fromMemoryId, toMemoryId, type)
            }
        }

    suspend fun deleteRelationship(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { repository.deleteRelationship(id) }
    }

    /** Assigns (or clears, with null/blank) a memory's project. */
    suspend fun setProject(memoryId: Long, projectName: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            val base = repository.getMeta(memoryId) ?: repository.defaultMetaFor(entity)
            repository.upsertMeta(base.copy(projectName = projectName?.trim()?.take(64)?.ifBlank { null }, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Replaces a memory's tags. */
    suspend fun setTags(memoryId: Long, tags: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            val base = repository.getMeta(memoryId) ?: repository.defaultMetaFor(entity)
            repository.upsertMeta(base.copy(tags = normalizeTags(tags).distinct().take(10).joinToString(","), updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun setPinned(memoryId: Long, pinned: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            repository.setPinned(entity, pinned)
        }
    }

    suspend fun setArchived(memoryId: Long, archived: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = repository.getMemoryById(memoryId) ?: error("Memory #$memoryId not found")
            repository.setArchived(entity, archived)
        }
    }

    suspend fun deleteMemory(memoryId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            repository.deleteMemoryById(memoryId)
            repository.deleteMeta(memoryId)
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
            val summaries = repository.recentSummaries(1000).map {
                MemoryExportSummary(it.conversationId, it.summary, it.messageCount, it.updatedAt)
            }
            val relationships = repository.getRelationships().map {
                MemoryExportRelationship(from = it.fromMemoryId, to = it.toMemoryId, type = it.type)
            }
            val projects = repository.getProjects().map { MemoryExportProject(it.name, it.description) }
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
        dto.projects.forEach { p -> runCatching { repository.createProject(p.name, p.description) } }
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
                    repository.getMeta(result.memoryId)?.let { meta ->
                        repository.upsertMeta(
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
            if (runCatching { repository.addRelationship(from, to, rel.type) }.getOrNull() != null) relationships++
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
            projectCount = runCatching { repository.projectCount() }.getOrDefault(0),
            tagCount = runCatching { repository.tagCount() }.getOrDefault(0),
            summaryCount = runCatching { repository.summaryCount() }.getOrDefault(0),
            relationshipCount = runCatching { repository.relationshipCount() }.getOrDefault(0),
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
            summaryCount = runCatching { repository.summaryCount() }.getOrDefault(0),
            lastIndexingAt = settingsStore.lastIndexingAt(),
            lastIndexingResult = settingsStore.lastIndexingResult(),
            lastExtractionAt = settingsStore.lastExtractionAt(),
            lastExtractionResult = settingsStore.lastExtractionResult()
        )
    }

    /** Explicitly synchronizes and re-indexes all Room memories into the Vector Engine. */
    suspend fun syncAllMemories(memories: List<MemoryEntity>): Int = withContext(Dispatchers.IO) {
        val all = if (memories.isNotEmpty()) memories else runCatching { repository.getAllMemories() }.getOrDefault(emptyList())
        runCatching { vectorEngine.syncWithRoomMemories(all) }
        runCatching { vectorEngine.indexedMemoryCount() }.getOrDefault(all.size)
    }

    // ── helpers ──

    private suspend fun loadCandidates(): List<Memory> {
        val metas = MemoryMetaRegistry.snapshot()
        return repository.getAllMemories()
            .filterNot { metas[it.id]?.isArchived == true }
            .map { MemoryRepository.toDomain(it, metas[it.id]) }
    }

    private suspend fun toDomain(entity: MemoryEntity): Memory =
        MemoryRepository.toDomain(entity, repository.getMeta(entity.id))

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase().replace(",", " ").take(32) }.filter { it.isNotEmpty() }

    private fun skipped(reason: String): MemoryWriteResult {
        Log.d(PIPELINE_TAG, "Skipped memory: $reason")
        return MemoryWriteResult(0L, MemoryWriteAction.SKIPPED, reason = reason)
    }
}

// ── Export / import DTOs (moved from AndroMemoryPipeline.kt) ─────────────────

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
