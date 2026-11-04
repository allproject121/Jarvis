package com.jarvis.assistant.maya.guardian

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FaceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun peopleJsonRoundTrip() {
        val people = listOf(
            FacePerson("a1", "Boss", FaceStore.ROLE_BOSS, listOf(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(1f, 2f, 3f)),
                listOf("/x/a.jpg"), 10L, 20L),
            FacePerson("b2", "Riya", FaceStore.ROLE_FAMILY, emptyList(), emptyList(), 30L, 40L)
        )
        val text = FaceStore.peopleToJson(people).toString()
        val back = FaceStore.peopleFromJson(JSONObject(text))
        assertEquals(2, back.size)
        assertEquals("Boss", back[0].name)
        assertEquals(FaceStore.ROLE_BOSS, back[0].role)
        assertEquals(2, back[0].vectors.size)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), back[0].vectors[0], 1e-6f)
        assertEquals(listOf("/x/a.jpg"), back[0].samplePaths)
        assertEquals(20L, back[0].updatedAt)
        assertEquals(FaceStore.ROLE_FAMILY, back[1].role)
        assertTrue(back[1].vectors.isEmpty())
    }

    @Test
    fun storePersistsAcrossInstances() {
        val dir = tmp.newFolder()
        val s1 = FaceStore(dir)
        s1.addSample("Boss", "owner", floatArrayOf(1f, 0f), null)
        s1.addSample("boss", null, floatArrayOf(0f, 1f), "/p.jpg")
        s1.addSample("Riya", "family", floatArrayOf(0.5f, 0.5f), null)
        assertTrue(s1.peopleFile.exists())

        val s2 = FaceStore(dir)
        val boss = s2.findByName("BOSS")!!
        assertEquals(FaceStore.ROLE_BOSS, boss.role)
        assertEquals(2, boss.vectors.size)
        assertEquals(listOf("/p.jpg"), boss.samplePaths)
        assertEquals(1, s2.owners().size)
        assertEquals(2, s2.trustedPeople().size)

        assertTrue(s2.setRole("riya", "blocked"))
        assertEquals(FaceStore.ROLE_BLOCKED, FaceStore(dir).findByName("Riya")!!.role)
        assertTrue(s2.remove("Riya"))
        assertFalse(s2.remove("Riya"))
        assertNull(FaceStore(dir).findByName("Riya"))
    }

    @Test
    fun galleryIndexRoundTripAndLookup() {
        val s = FaceStore(tmp.newFolder())
        s.writeGalleryIndex(
            GalleryIndex(
                99L, 3, listOf(
                    GalleryEntry("content://1", null, 100L, listOf("Boss"), 0),
                    GalleryEntry("content://2", "/sd/2.jpg", 200L, listOf("Boss", "Riya"), 1),
                    GalleryEntry("content://3", null, 300L, emptyList(), 2)
                )
            )
        )
        val idx = s.readGalleryIndex()
        assertEquals(99L, idx.scannedAt)
        assertEquals(3, idx.entries.size)
        assertEquals(listOf("content://2", "content://1"), s.photosOf("boss").map { it.uri })
        assertEquals(listOf("content://3", "content://2"), s.photosWithUnknownFaces().map { it.uri })
    }

    @Test
    fun roleNormalisation() {
        assertEquals(FaceStore.ROLE_BOSS, FaceStore.normaliseRole("Owner"))
        assertEquals(FaceStore.ROLE_FAMILY, FaceStore.normaliseRole(" FAMILY "))
        assertEquals(FaceStore.ROLE_GUEST, FaceStore.normaliseRole("stranger"))
        assertEquals(FaceStore.ROLE_GUEST, FaceStore.normaliseRole(null))
    }
}
