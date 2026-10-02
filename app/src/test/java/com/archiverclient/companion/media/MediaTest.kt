package com.archiverclient.companion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors `ac-import/src/source.rs::is_media`. */
class MediaTest {

    @Test
    fun `pictures raw and footage are all media`() {
        listOf("a.jpg", "b.HEIC", "c.png", "d.avif", "e.svg").forEach {
            assertTrue(it, Media.isMedia(it))
        }
        listOf("f.cr3", "g.NEF", "h.dng", "i.raw").forEach { assertTrue(it, Media.isMedia(it)) }
        listOf("j.mp4", "k.MOV", "l.mkv", "m.insv", "n.3g2").forEach {
            assertTrue(it, Media.isMedia(it))
        }
    }

    @Test
    fun `everything else is not`() {
        listOf("notes.txt", "sheet.xlsx", "installer.exe", "song.mp3", "book.pdf")
            .forEach { assertFalse(it, Media.isMedia(it)) }
    }

    @Test
    fun `a name that says nothing about itself is not worth fetching`() {
        assertFalse(Media.isMedia("IMG_0001"))
        assertFalse(Media.isMedia(""))
        assertFalse(Media.isMedia("trailing."))
    }

    @Test
    fun `only the last extension counts`() {
        assertTrue(Media.isMedia("archive.txt.jpg"))
        assertFalse(Media.isMedia("photo.jpg.txt"))
    }

    @Test
    fun `ids round-trip and keep the volume`() {
        assertEquals("external_primary:42", Media.idOf("external_primary", 42))
        assertEquals("external_primary" to 42L, Media.splitId("external_primary:42"))
        // An SD card volume is named for its serial, which contains a dash, not a colon.
        assertEquals("1a2b-3c4d" to 7L, Media.splitId("1a2b-3c4d:7"))
    }

    @Test
    fun `a malformed id is rejected rather than guessed at`() {
        assertNull(Media.splitId("nocolon"))
        assertNull(Media.splitId(":42"))
        assertNull(Media.splitId("external:"))
        assertNull(Media.splitId("external:notanumber"))
    }
}
