package org.schabi.newpipe.local.playlist.takeout

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class TakeoutPlaylistParserTest {
    private val currentFormat = """
        Video ID,Playlist Video Creation Timestamp
        dQw4w9WgXcQ,2024-01-01T10:00:00+00:00
        9bZkp7q19f0,2024-01-02T10:00:00+00:00
    """.trimIndent()

    private val oldFormat = "Playlist ID,Channel ID,Time Created,Time Updated,Title,Description,Visibility\n" +
        "PLabcdefghijklmnopqrstuvwxyz012345,UCabcdefghijklmnopqrstuv,2019-01-01,2019-01-02,Rock,,Private\n" +
        "\n" +
        "Video ID,Time Added\n" +
        "kJQP7kiw5Fk ,2019-01-01 10:00:00 UTC\n" +
        "kJQP7kiw5Fk ,2019-01-03 10:00:00 UTC\n"

    @Test
    fun readsCurrentTakeoutFormat() {
        assertEquals(listOf("dQw4w9WgXcQ", "9bZkp7q19f0"), TakeoutPlaylistParser.videoIds(currentFormat))
    }

    @Test
    fun readsOldTakeoutFormatWithoutDuplicates() {
        assertEquals(listOf("kJQP7kiw5Fk"), TakeoutPlaylistParser.videoIds(oldFormat))
    }

    @Test
    fun otherTakeoutCsvsYieldNothing() {
        val subscriptions = "Channel Id,Channel Url,Channel Title\n" +
            "UCabcdefghijklmnopqrstuv,http://www.youtube.com/channel/UCabcdefghijklmnopqrstuv,Foo\n"
        val metadata = "Playlist ID,Add new videos to top,Playlist Title (Original)\n" +
            "PLabcdefghijklmnopqrstuvwxyz012345,False,Rock\n"
        assertEquals(emptyList<String>(), TakeoutPlaylistParser.videoIds(subscriptions))
        assertEquals(emptyList<String>(), TakeoutPlaylistParser.videoIds(metadata))
    }

    @Test
    fun playlistNameComesFromFileName() {
        assertEquals("Rock nacional", TakeoutPlaylistParser.nameFromPath("Takeout/YouTube/playlists/Rock nacional-videos.csv"))
        assertEquals("Jazz", TakeoutPlaylistParser.nameFromPath("Jazz.CSV"))
    }

    @Test
    fun parsesSingleCsv() {
        val playlists = TakeoutPlaylistParser.parse(
            ByteArrayInputStream(currentFormat.toByteArray()),
            "Favoritas-videos.csv"
        )
        assertEquals(listOf(TakeoutPlaylist("Favoritas", listOf("dQw4w9WgXcQ", "9bZkp7q19f0"))), playlists)
    }

    @Test
    fun parsesWholeTakeoutZip() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            fun add(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            add("Takeout/YouTube e YouTube Music/playlists/Rock-videos.csv", oldFormat)
            add("Takeout/YouTube e YouTube Music/playlists/Favoritas-videos.csv", currentFormat)
            add("Takeout/YouTube e YouTube Music/playlists/playlists.csv", "Playlist ID,Title\nPLx,Rock\n")
            add("Takeout/YouTube e YouTube Music/inscrições/inscrições.csv", "Channel Id\nUCabcdefghijklmnopqrstuv\n")
        }
        val playlists = TakeoutPlaylistParser.parse(ByteArrayInputStream(zip.toByteArray()), "takeout.zip")
        assertEquals(listOf("Favoritas", "Rock"), playlists.map { it.name })
        assertEquals(listOf("kJQP7kiw5Fk"), playlists[1].videoIds)
    }
}
