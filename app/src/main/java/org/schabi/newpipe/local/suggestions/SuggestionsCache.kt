package org.schabi.newpipe.local.suggestions

import android.content.Context
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Last computed suggestions, kept in the app's private storage so the tab opens instantly. */
object SuggestionsCache {
    private const val FILE_NAME = "tubetube_suggestions.ser"

    class Entry(val items: List<StreamInfoItem>, val savedAtMillis: Long)

    fun read(context: Context): Entry? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) {
            return null
        }
        return runCatching {
            ObjectInputStream(file.inputStream().buffered()).use { input ->
                @Suppress("UNCHECKED_CAST")
                Entry(input.readObject() as ArrayList<StreamInfoItem>, file.lastModified())
            }
        }.getOrNull() // unreadable after an app update: recompute
    }

    fun write(context: Context, items: List<StreamInfoItem>) {
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        ObjectOutputStream(tmp.outputStream().buffered()).use { it.writeObject(ArrayList(items)) }
        tmp.renameTo(file)
    }
}
