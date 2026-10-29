package io.github.glacier_jellyfin.androidtv.core.data.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A saved index and when it was fetched. */
class SavedIndex(val at: Long, val entries: List<IndexEntry>)

/**
 * Keeps sorted name indexes (see [LibraryRepository]) on the device. A big library takes the
 * server half a minute to list in full (70,000 songs), so the list is fetched once and shown
 * from here at once the next time, while a fresh copy is fetched in the background.
 * One file per server, user and query, in the cache folder: the system may clear it.
 */
@Singleton
class NameIndexStore @Inject constructor(@ApplicationContext context: Context) {

    private val folder = File(context.cacheDir, "name-index")

    /** When the saved index of [key] was fetched, without reading all of it. */
    fun savedAt(key: String): Long? = runCatching {
        val file = File(folder, fileName(key))
        if (!file.exists()) return null
        file.bufferedReader().use { reader -> if (reader.readLine() == key) reader.readLine()?.toLong() else null }
    }.getOrNull()

    fun read(key: String): SavedIndex? = runCatching {
        val file = File(folder, fileName(key))
        if (!file.exists()) return null
        file.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            if (!iterator.hasNext() || iterator.next() != key) return null
            val at = iterator.next().toLong()
            val entries = ArrayList<IndexEntry>()
            while (iterator.hasNext()) {
                val fields = iterator.next().split('\t')
                if (fields.size < FIELDS) continue
                fun text(i: Int) = fields[i].ifEmpty { null }
                entries += IndexEntry(
                    id = UUID.fromString(fields[0]),
                    name = fields[1],
                    addedMonth = text(2)?.toInt(),
                    year = text(3)?.toInt(),
                    rating = text(4)?.toFloat(),
                    runtimeMs = text(5)?.toLong(),
                    artist = text(6),
                    albumId = text(7),
                    album = text(8),
                    disc = text(9)?.toInt(),
                    track = text(10)?.toInt(),
                )
            }
            SavedIndex(at, entries)
        }
    }.onFailure { Log.w(TAG, "Reading a name index failed", it) }.getOrNull()

    fun write(key: String, index: SavedIndex) {
        runCatching {
            folder.mkdirs()
            // Files named by SHA-1 (up to 0.6.3) are never read again.
            folder.listFiles { f -> f.name.length == 40 + ".txt".length }?.forEach { it.delete() }
            val file = File(folder, fileName(key))
            val partial = File(folder, file.name + ".part")
            partial.bufferedWriter().use { out ->
                out.write(key)
                out.write("\n")
                out.write(index.at.toString())
                out.write("\n")
                for (entry in index.entries) {
                    val fields = listOf(
                        entry.id, entry.name, entry.addedMonth, entry.year, entry.rating, entry.runtimeMs,
                        entry.artist, entry.albumId, entry.album, entry.disc, entry.track,
                    )
                    out.write(fields.joinToString("\t") { it?.toString().orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') })
                    out.write("\n")
                }
            }
            if (!partial.renameTo(file)) {
                file.delete()
                partial.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Saving a name index failed", it) }
    }

    /** The key itself is the first line of the file, so a clash of names is noticed. */
    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".txt"

    private companion object {
        const val TAG = "NameIndex"
        /** Values per line: id, name and the values of the other sorts (see [IndexEntry]). */
        const val FIELDS = 11
    }
}
