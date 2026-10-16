package org.schabi.newpipe.local.playlist.takeout

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class TakeoutPlaylist(val name: String, val videoIds: List<String>)

/**
 * Reads YouTube playlists from a Google Takeout export: the whole .zip or a single playlist .csv.
 *
 * Both Takeout layouts are handled, the current one ("Name-videos.csv" with a
 * "Video ID,Playlist Video Creation Timestamp" header) and the older one (a playlist header block,
 * then "Video ID,Time Added" rows): any row whose first column is a video id is a video.
 * Other CSVs of the export (subscriptions, comments, playlist metadata) have no video id in
 * their first column, so they yield nothing and are skipped.
 */
object TakeoutPlaylistParser {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private const val MAX_CSV_BYTES = 8 * 1024 * 1024

    fun videoIds(csv: String): List<String> = csv.lineSequence()
        .map { it.removePrefix("").substringBefore(',').trim().trim('"') }
        .filter { VIDEO_ID.matches(it) }
        .distinct()
        .toList()

    fun nameFromPath(path: String): String {
        val name = path.substringAfterLast('/')
            .replace(Regex("\\.csv$", RegexOption.IGNORE_CASE), "")
            .removeSuffix("-videos")
            .trim()
        return name.ifEmpty { "YouTube" }
    }

    /** @param fileName display name of the picked file, used as playlist name for a single CSV */
    fun parse(input: InputStream, fileName: String?): List<TakeoutPlaylist> {
        val stream = BufferedInputStream(input)
        return if (isZip(stream)) {
            parseZip(stream)
        } else {
            listOfNotNull(
                playlistOf(fileName ?: "YouTube", readText(stream))
            )
        }
    }

    private fun parseZip(input: InputStream): List<TakeoutPlaylist> {
        val result = ArrayList<TakeoutPlaylist>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                    playlistOf(entry.name, readText(zip))?.let(result::add)
                }
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun playlistOf(path: String, csv: String): TakeoutPlaylist? {
        val ids = videoIds(csv)
        return if (ids.isEmpty()) null else TakeoutPlaylist(nameFromPath(path), ids)
    }

    private fun isZip(input: BufferedInputStream): Boolean {
        input.mark(4)
        val magic = ByteArray(4)
        val read = input.read(magic)
        input.reset()
        return read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2] == 3.toByte() && magic[3] == 4.toByte()
    }

    private fun readText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < MAX_CSV_BYTES) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
