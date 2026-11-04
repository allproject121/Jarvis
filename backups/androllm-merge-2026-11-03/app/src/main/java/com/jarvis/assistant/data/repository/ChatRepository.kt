package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
                return
            }
            // In-place update: if the last turn was a pending text send awaiting reply,
            // update its placeholder response with the actual assistant reply instead of creating a duplicate turn.
            val isLastPlaceholder = last.jarvisResponse == "(Audio response)" || last.jarvisResponse.isBlank()
            if (cleanJarvis.isNotBlank() && cleanJarvis != "(Audio response)" && isLastPlaceholder) {
                if (last.userTranscript == cleanUser || cleanUser.isBlank() || cleanUser == "Spoken user query") {
                    currentList[currentList.size - 1] = last.copy(
                        jarvisResponse = cleanJarvis,
                        timestamp = System.currentTimeMillis()
                    )
                    _turns.value = currentList
                    preferences.saveChatHistory(currentList)
                    return
                }
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" }
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
    }
}
