package com.jarvis.assistant.androllm.attachments

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.assistant.androllm.attachments.model.AttachmentSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.androLlmAttachmentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "androllm_attachment_prefs"
)

/**
 * Persists [AttachmentSettings] in a dedicated DataStore (ported from AndroLLM;
 * Hilt singleton → process singleton via [get]).
 */
class AttachmentSettingsStore private constructor(context: Context) {

    private object Keys {
        val IMAGE_QUALITY = intPreferencesKey("image_quality")
        val OCR_LANGUAGE = stringPreferencesKey("ocr_language")
        val MAX_ATTACHMENT_BYTES = longPreferencesKey("max_attachment_bytes")
        val MAX_PER_MESSAGE = intPreferencesKey("max_per_message")
        val AUTO_COMPRESS_IMAGES = booleanPreferencesKey("auto_compress_images")
        val PRESERVE_FILENAMES = booleanPreferencesKey("preserve_filenames")
        val CACHE_PROCESSED = booleanPreferencesKey("cache_processed")
    }

    private val dataStore: DataStore<Preferences> = context.applicationContext.androLlmAttachmentDataStore

    val settings: Flow<AttachmentSettings> = dataStore.data.map { prefs ->
        AttachmentSettings(
            imageQuality = (prefs[Keys.IMAGE_QUALITY] ?: 85)
                .coerceIn(AttachmentSettings.IMAGE_QUALITY_MIN, AttachmentSettings.IMAGE_QUALITY_MAX),
            ocrLanguage = prefs[Keys.OCR_LANGUAGE] ?: "en",
            maxAttachmentBytes = prefs[Keys.MAX_ATTACHMENT_BYTES] ?: (20L * 1024 * 1024),
            maxAttachmentsPerMessage = (prefs[Keys.MAX_PER_MESSAGE] ?: 10)
                .coerceIn(AttachmentSettings.MAX_ATTACHMENTS_MIN, AttachmentSettings.MAX_ATTACHMENTS_MAX),
            autoCompressImages = prefs[Keys.AUTO_COMPRESS_IMAGES] ?: true,
            preserveFilenames = prefs[Keys.PRESERVE_FILENAMES] ?: true,
            cacheProcessedAttachments = prefs[Keys.CACHE_PROCESSED] ?: true
        )
    }

    suspend fun current(): AttachmentSettings = settings.first()

    suspend fun update(transform: (AttachmentSettings) -> AttachmentSettings) {
        dataStore.edit { prefs ->
            val current = AttachmentSettings(
                imageQuality = prefs[Keys.IMAGE_QUALITY] ?: 85,
                ocrLanguage = prefs[Keys.OCR_LANGUAGE] ?: "en",
                maxAttachmentBytes = prefs[Keys.MAX_ATTACHMENT_BYTES] ?: (20L * 1024 * 1024),
                maxAttachmentsPerMessage = prefs[Keys.MAX_PER_MESSAGE] ?: 10,
                autoCompressImages = prefs[Keys.AUTO_COMPRESS_IMAGES] ?: true,
                preserveFilenames = prefs[Keys.PRESERVE_FILENAMES] ?: true,
                cacheProcessedAttachments = prefs[Keys.CACHE_PROCESSED] ?: true
            )
            val updated = transform(current)
            prefs[Keys.IMAGE_QUALITY] = updated.imageQuality
                .coerceIn(AttachmentSettings.IMAGE_QUALITY_MIN, AttachmentSettings.IMAGE_QUALITY_MAX)
            prefs[Keys.OCR_LANGUAGE] = updated.ocrLanguage
            prefs[Keys.MAX_ATTACHMENT_BYTES] = updated.maxAttachmentBytes
            prefs[Keys.MAX_PER_MESSAGE] = updated.maxAttachmentsPerMessage
                .coerceIn(AttachmentSettings.MAX_ATTACHMENTS_MIN, AttachmentSettings.MAX_ATTACHMENTS_MAX)
            prefs[Keys.AUTO_COMPRESS_IMAGES] = updated.autoCompressImages
            prefs[Keys.PRESERVE_FILENAMES] = updated.preserveFilenames
            prefs[Keys.CACHE_PROCESSED] = updated.cacheProcessedAttachments
        }
    }

    companion object {
        @Volatile
        private var instance: AttachmentSettingsStore? = null

        fun get(context: Context): AttachmentSettingsStore =
            instance ?: synchronized(this) {
                instance ?: AttachmentSettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
