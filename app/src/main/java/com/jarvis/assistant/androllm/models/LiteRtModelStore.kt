package com.jarvis.assistant.androllm.models

import android.content.Context
import com.jarvis.assistant.androllm.AndroLlmPaths
import com.jarvis.assistant.androllm.models.catalog.CatalogModel
import com.jarvis.assistant.androllm.models.download.LiteRtDownloadManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One LiteRT artifact present in [AndroLlmPaths.liteRtModelsDir]. */
data class InstalledLiteRtModel(
    val file: File,
    val format: ModelArtifactFormat,
    val sizeBytes: Long,
    /** Matching catalog entry (by file name) when the artifact came from the catalog. */
    val catalogModel: CatalogModel?
) {
    val displayName: String get() = catalogModel?.name ?: file.nameWithoutExtension
    val path: String get() = file.absolutePath
}

/**
 * Lists the LiteRT model files installed for the LiteRT-LM engine. Only finished artifacts
 * are reported: in-progress downloads use a `.part` suffix and the companion
 * `tokenizer.model` is not a model.
 */
object LiteRtModelStore {

    fun scan(context: Context, catalog: List<CatalogModel>): List<InstalledLiteRtModel> {
        val dir = AndroLlmPaths.liteRtModelsDir(context)
        val byName = catalog.associateBy { it.fileName.lowercase() }
        return dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(LiteRtDownloadManager.PART_SUFFIX) }
            .mapNotNull { file ->
                val format = ModelArtifactFormat.fromFileName(file.name) ?: return@mapNotNull null
                if (!format.isLiteRt) return@mapNotNull null
                InstalledLiteRtModel(
                    file = file,
                    format = format,
                    sizeBytes = file.length(),
                    catalogModel = byName[file.name.lowercase()]
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    /** File names of LiteRT artifacts that have a resumable `.part` file. */
    fun partialFileNames(context: Context): Set<String> =
        AndroLlmPaths.liteRtModelsDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(LiteRtDownloadManager.PART_SUFFIX) && it.length() > 0 }
            .map { it.name.removeSuffix(LiteRtDownloadManager.PART_SUFFIX) }
            .toSet()
}

/**
 * Small persisted preferences of the Models screen: favourites, first-launch recommendation
 * flag and the user's preferred LiteRT model (absolute path inside the LiteRT models dir).
 */
class ModelLibraryPrefs private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet())
    /** Favourite model ids (GGUF model ids and LiteRT catalog ids / file names share one set). */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _preferredLiteRtModelPath = MutableStateFlow(prefs.getString(KEY_PREFERRED_LITERT, null))
    /**
     * Absolute path of the LiteRT model the user picked as preferred in the Models screen
     * (null = none). The LiteRT engine may read it via [preferredLiteRtModelFile].
     */
    val preferredLiteRtModelPath: StateFlow<String?> = _preferredLiteRtModelPath.asStateFlow()

    fun toggleFavorite(id: String) {
        val next = _favorites.value.let { if (id in it) it - id else it + id }
        _favorites.value = next
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
    }

    fun isFirstLaunchRecommendationShown(): Boolean = prefs.getBoolean(KEY_RECOMMENDATION_SHOWN, false)

    fun markFirstLaunchRecommendationShown() {
        prefs.edit().putBoolean(KEY_RECOMMENDATION_SHOWN, true).apply()
    }

    fun setPreferredLiteRtModel(path: String?) {
        _preferredLiteRtModelPath.value = path
        prefs.edit().putString(KEY_PREFERRED_LITERT, path).apply()
    }

    /** The preferred LiteRT model file when it still exists. */
    fun preferredLiteRtModelFile(): File? =
        _preferredLiteRtModelPath.value?.let { File(it) }?.takeIf { it.isFile }

    companion object {
        const val PREFS = "androllm_model_library"
        const val KEY_FAVORITES = "favorite_model_ids"
        const val KEY_RECOMMENDATION_SHOWN = "first_launch_recommendation_shown"
        const val KEY_PREFERRED_LITERT = "preferred_litert_model_path"

        @Volatile
        private var instance: ModelLibraryPrefs? = null

        fun getInstance(context: Context): ModelLibraryPrefs =
            instance ?: synchronized(this) {
                instance ?: ModelLibraryPrefs(context.applicationContext).also { instance = it }
            }
    }
}
