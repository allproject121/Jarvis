package com.jarvis.assistant.maya.guardian

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * One enrolled person. A face print is a list of numbers ([vectors]), not a photo — the sample
 * JPEGs under faces/<id>/ exist only so the Faces screen can show who was saved.
 * Roles mirror Maya / [com.jarvis.assistant.security.guardian.GuardianRole]:
 * boss | family | friend | guest | blocked.
 */
data class FacePerson(
    val id: String,
    val name: String,
    val role: String,
    val vectors: List<FloatArray>,
    val samplePaths: List<String>,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isTrusted: Boolean get() = role == FaceStore.ROLE_BOSS || role == FaceStore.ROLE_FAMILY
    val isOwner: Boolean get() = role == FaceStore.ROLE_BOSS
}

/** One indexed phone-gallery photo. [people] = names of enrolled people recognised in it. */
data class GalleryEntry(
    val uri: String,
    val path: String?,
    val takenAt: Long,
    val people: List<String>,
    val unknownFaces: Int
)

data class GalleryIndex(val scannedAt: Long, val scannedCount: Int, val entries: List<GalleryEntry>)

/**
 * filesDir/faces/people.json  — enrolled people + vectors
 * filesDir/faces/gallery_index.json — [GalleryFaceIndexer] output
 * filesDir/faces/<personId>/<n>.jpg — sample crops
 *
 * Plain org.json so the round trip is unit-testable; every write is atomic (tmp + rename).
 */
class FaceStore(baseDir: File) {

    companion object {
        const val ROLE_BOSS = "boss"
        const val ROLE_FAMILY = "family"
        const val ROLE_FRIEND = "friend"
        const val ROLE_GUEST = "guest"
        const val ROLE_BLOCKED = "blocked"
        val ROLES = listOf(ROLE_BOSS, ROLE_FAMILY, ROLE_FRIEND, ROLE_GUEST, ROLE_BLOCKED)

        /** Accepts owner/boss/family/... in any case; unknown → guest. */
        fun normaliseRole(raw: String?): String {
            val r = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when (r) {
                "owner", "boss", "me", "self" -> ROLE_BOSS
                "family" -> ROLE_FAMILY
                "friend" -> ROLE_FRIEND
                "blocked", "block" -> ROLE_BLOCKED
                "", "guest" -> ROLE_GUEST
                else -> if (r in ROLES) r else ROLE_GUEST
            }
        }

        fun peopleToJson(people: List<FacePerson>): JSONObject = JSONObject().apply {
            put("version", 1)
            put("people", JSONArray().apply {
                people.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("role", p.role)
                        put("created_at", p.createdAt)
                        put("updated_at", p.updatedAt)
                        put("samples", JSONArray(p.samplePaths))
                        put("vectors", JSONArray().apply {
                            p.vectors.forEach { v ->
                                put(JSONArray().apply { v.forEach { put(it.toDouble()) } })
                            }
                        })
                    })
                }
            })
        }

        fun peopleFromJson(json: JSONObject): List<FacePerson> {
            val arr = json.optJSONArray("people") ?: return emptyList()
            val out = ArrayList<FacePerson>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val vecs = ArrayList<FloatArray>()
                val va = o.optJSONArray("vectors")
                if (va != null) {
                    for (j in 0 until va.length()) {
                        val one = va.optJSONArray(j) ?: continue
                        vecs.add(FloatArray(one.length()) { k -> one.optDouble(k, 0.0).toFloat() })
                    }
                }
                val samples = ArrayList<String>()
                val sa = o.optJSONArray("samples")
                if (sa != null) for (j in 0 until sa.length()) sa.optString(j, null)?.let { samples.add(it) }
                out.add(
                    FacePerson(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "?"),
                        role = normaliseRole(o.optString("role", ROLE_GUEST)),
                        vectors = vecs,
                        samplePaths = samples,
                        createdAt = o.optLong("created_at", 0L),
                        updatedAt = o.optLong("updated_at", 0L)
                    )
                )
            }
            return out
        }

        fun galleryToJson(index: GalleryIndex): JSONObject = JSONObject().apply {
            put("version", 1)
            put("scanned_at", index.scannedAt)
            put("scanned_count", index.scannedCount)
            put("entries", JSONArray().apply {
                index.entries.forEach { e ->
                    put(JSONObject().apply {
                        put("uri", e.uri)
                        if (e.path != null) put("path", e.path)
                        put("taken_at", e.takenAt)
                        put("people", JSONArray(e.people))
                        put("unknown_faces", e.unknownFaces)
                    })
                }
            })
        }

        fun galleryFromJson(json: JSONObject): GalleryIndex {
            val arr = json.optJSONArray("entries") ?: JSONArray()
            val entries = ArrayList<GalleryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val names = ArrayList<String>()
                val pa = o.optJSONArray("people")
                if (pa != null) for (j in 0 until pa.length()) names.add(pa.optString(j))
                entries.add(
                    GalleryEntry(
                        uri = o.optString("uri"),
                        path = o.optString("path", null),
                        takenAt = o.optLong("taken_at", 0L),
                        people = names,
                        unknownFaces = o.optInt("unknown_faces", 0)
                    )
                )
            }
            return GalleryIndex(json.optLong("scanned_at", 0L), json.optInt("scanned_count", 0), entries)
        }
    }

    val facesDir: File = File(baseDir, "faces")
    val peopleFile: File = File(facesDir, "people.json")
    val galleryFile: File = File(facesDir, "gallery_index.json")

    private val lock = Any()
    private var cache: MutableList<FacePerson>? = null

    private fun ensureDir() {
        if (!facesDir.exists()) facesDir.mkdirs()
    }

    private fun writeAtomic(file: File, text: String) {
        ensureDir()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun load(): MutableList<FacePerson> {
        cache?.let { return it }
        val list: MutableList<FacePerson> = try {
            if (peopleFile.exists()) peopleFromJson(JSONObject(peopleFile.readText())).toMutableList()
            else mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
        cache = list
        return list
    }

    private fun persist(list: List<FacePerson>) {
        writeAtomic(peopleFile, peopleToJson(list).toString())
    }

    fun listPeople(): List<FacePerson> = synchronized(lock) { load().toList() }

    fun findByName(name: String): FacePerson? = synchronized(lock) {
        val n = name.trim()
        load().firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    fun findById(id: String): FacePerson? = synchronized(lock) { load().firstOrNull { it.id == id } }

    fun owners(): List<FacePerson> = listPeople().filter { it.isOwner }

    fun trustedPeople(): List<FacePerson> = listPeople().filter { it.isTrusted }

    /** name → vectors, as [FaceMatcher.bestMatch] wants it. */
    fun vectorsByName(): Map<String, List<FloatArray>> =
        listPeople().filter { it.vectors.isNotEmpty() }.associate { it.name to it.vectors }

    fun personDir(person: FacePerson): File = File(facesDir, person.id).apply { if (!exists()) mkdirs() }

    /** Creates the person when missing, otherwise updates the role (if given). Returns the person. */
    fun upsert(name: String, role: String?): FacePerson = synchronized(lock) {
        val list = load()
        val now = System.currentTimeMillis()
        val existing = list.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
        val person = if (existing >= 0) {
            val p = list[existing]
            val updated = p.copy(role = role?.let { normaliseRole(it) } ?: p.role, updatedAt = now)
            list[existing] = updated
            updated
        } else {
            val p = FacePerson(
                id = UUID.randomUUID().toString().replace("-", "").take(12),
                name = name.trim(),
                role = normaliseRole(role),
                vectors = emptyList(),
                samplePaths = emptyList(),
                createdAt = now,
                updatedAt = now
            )
            list.add(p)
            p
        }
        persist(list)
        person
    }

    /** Appends one face print (+ optional sample image path) to [name]. */
    fun addSample(name: String, role: String?, vector: FloatArray, samplePath: String?): FacePerson = synchronized(lock) {
        upsert(name, role)
        val list = load()
        val idx = list.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
        val p = list[idx]
        val updated = p.copy(
            vectors = p.vectors + listOf(vector),
            samplePaths = if (samplePath != null) p.samplePaths + samplePath else p.samplePaths,
            updatedAt = System.currentTimeMillis()
        )
        list[idx] = updated
        persist(list)
        updated
    }

    fun setRole(name: String, role: String): Boolean = synchronized(lock) {
        val list = load()
        val idx = list.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
        if (idx < 0) return false
        list[idx] = list[idx].copy(role = normaliseRole(role), updatedAt = System.currentTimeMillis())
        persist(list)
        true
    }

    /** Removes the person, their vectors and their sample images. */
    fun remove(name: String): Boolean = synchronized(lock) {
        val list = load()
        val idx = list.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
        if (idx < 0) return false
        val p = list.removeAt(idx)
        persist(list)
        try { File(facesDir, p.id).deleteRecursively() } catch (_: Exception) {}
        true
    }

    fun clearAll() = synchronized(lock) {
        val list = load()
        list.forEach { p -> try { File(facesDir, p.id).deleteRecursively() } catch (_: Exception) {} }
        list.clear()
        persist(list)
    }

    // ---- gallery index ----

    fun readGalleryIndex(): GalleryIndex = try {
        if (galleryFile.exists()) galleryFromJson(JSONObject(galleryFile.readText()))
        else GalleryIndex(0L, 0, emptyList())
    } catch (_: Exception) {
        GalleryIndex(0L, 0, emptyList())
    }

    fun writeGalleryIndex(index: GalleryIndex) {
        writeAtomic(galleryFile, galleryToJson(index).toString())
    }

    fun photosOf(name: String): List<GalleryEntry> {
        val n = name.trim()
        return readGalleryIndex().entries
            .filter { e -> e.people.any { it.equals(n, ignoreCase = true) } }
            .sortedByDescending { it.takenAt }
    }

    fun photosWithUnknownFaces(): List<GalleryEntry> =
        readGalleryIndex().entries.filter { it.unknownFaces > 0 }.sortedByDescending { it.takenAt }
}
