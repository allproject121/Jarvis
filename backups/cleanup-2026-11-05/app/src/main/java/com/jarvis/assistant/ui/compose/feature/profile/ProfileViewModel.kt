package com.jarvis.assistant.ui.compose.feature.profile

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local identity stored in Jarvis' AppPreferences (no Firebase account in Jarvis). */
internal data class ProfileIdentity(
    val userName: String = "",
    val assistantName: String = "",
    val personality: String = "",
    val location: String = "",
    val birthday: String = "",
    val age: String = ""
)

/** Real on-device statistics. -1 means "not available". */
internal data class ProfileStats(
    val conversationTurns: Int = -1,
    val memoryCount: Int = -1,
    val modelsReady: Int = 0,
    val modelCatalogSize: Int = 0,
    val storageFreeBytes: Long = 0L,
    val storageTotalBytes: Long = 0L,
    val readyModelNames: List<String> = emptyList()
)

private val profileBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Profile view model mapped onto Jarvis' local persona preferences
 * ([AppPreferences.userName], [AppPreferences.assistantName], …) plus real
 * device stats (chat history, memory DB, downloaded models, storage).
 */
internal class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val jarvisApp: JarvisApp? = application as? JarvisApp

    private val prefs: AppPreferences =
        jarvisApp?.let { runCatching { it.preferences }.getOrNull() } ?: AppPreferences(application)

    private val modelManager: LocalModelManager? =
        runCatching { LocalModelManager.getInstance(application, profileBackgroundScope) }.getOrNull()

    private val _identity = MutableStateFlow(readIdentity())
    val identity: StateFlow<ProfileIdentity> = _identity.asStateFlow()

    private val _stats = MutableStateFlow(ProfileStats())
    val stats: StateFlow<ProfileStats> = _stats.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val personalities: List<String> = GeminiConstants.SUPPORTED_PERSONALITIES

    init {
        refresh()
        // Chat turns are a live StateFlow in Jarvis' ChatRepository.
        val chatRepo = jarvisApp?.let { runCatching { it.chatRepository }.getOrNull() }
        if (chatRepo != null) {
            viewModelScope.launch {
                chatRepo.turns.collect { turns ->
                    _stats.update { it.copy(conversationTurns = turns.size) }
                }
            }
        }
        val memoryRepo = jarvisApp?.let { runCatching { it.memoryRepository }.getOrNull() }
        if (memoryRepo != null) {
            viewModelScope.launch {
                runCatching {
                    memoryRepo.getAllMemoriesFlow().collect { list ->
                        _stats.update { it.copy(memoryCount = list.size) }
                    }
                }
            }
        }
    }

    private fun readIdentity(): ProfileIdentity = ProfileIdentity(
        userName = prefs.userName,
        assistantName = prefs.assistantName,
        personality = prefs.personality,
        location = prefs.userLocation,
        birthday = prefs.userBirthday,
        age = prefs.userAge
    )

    fun refresh() {
        _identity.value = readIdentity()
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val manager = modelManager
                val ready = if (manager == null) emptyList() else LocalAiConfig.AVAILABLE_MODELS.filter {
                    runCatching { manager.isModelReady(it.id) }.getOrDefault(false)
                }
                val (free, total) = runCatching {
                    val stat = StatFs(getApplication<Application>().filesDir.absolutePath)
                    stat.availableBytes to stat.totalBytes
                }.getOrDefault(0L to 0L)
                Triple(ready, free, total)
            }
            _stats.update {
                it.copy(
                    modelsReady = snapshot.first.size,
                    modelCatalogSize = LocalAiConfig.AVAILABLE_MODELS.size,
                    readyModelNames = snapshot.first.map { d -> d.name },
                    storageFreeBytes = snapshot.second,
                    storageTotalBytes = snapshot.third
                )
            }
        }
    }

    fun saveIdentity(
        userName: String,
        assistantName: String,
        location: String,
        birthday: String,
        age: String
    ) {
        runCatching {
            if (userName.isNotBlank()) prefs.userName = userName
            if (assistantName.isNotBlank()) prefs.assistantName = assistantName
            prefs.userLocation = location
            prefs.userBirthday = birthday
            prefs.userAge = age
        }.onSuccess {
            _message.value = "Profile saved"
        }.onFailure { e ->
            _message.value = "Could not save profile: ${e.message}"
        }
        _identity.value = readIdentity()
    }

    fun setPersonality(personality: String) {
        prefs.personality = personality
        _identity.value = readIdentity()
        _message.value = "Personality set to $personality"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
