package ru.feryafox.shiori.diagnostics

import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Reads local NeoReader history without opening books or requesting synchronization. */
class RecentReaderBooks(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun read(): JSONObject {
        val columns =
            resolver.query(METADATA, null, "1=0", null, null).use { cursor ->
                cursor?.columnNames?.toSet()
                    ?: throw IOException("NeoReader metadata provider is unavailable")
            }
        if ("lastAccess" !in columns) {
            throw IOException(
                "This NeoReader version does not expose lastAccess; recent books cannot be ordered reliably"
            )
        }
        val projection = BOOK_FIELDS.filter { it in columns }.toTypedArray()
        val books = JSONArray()
        resolver
            .query(METADATA, projection, "lastAccess > ?", arrayOf("0"), "lastAccess DESC")
            .use { cursor ->
                if (cursor == null) throw IOException("NeoReader returned no metadata cursor")
                while (books.length() < MAX_BOOKS && cursor.moveToNext()) {
                    val book = JSONObject()
                    for (index in 0 until cursor.columnCount) {
                        val value =
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else ->
                                    throw IOException(
                                        "Unexpected NeoReader metadata type: ${cursor.getColumnName(index)}"
                                    )
                            }
                        book.put(cursor.getColumnName(index), value)
                    }
                    books.put(book)
                }
            }
        return JSONObject()
            .put("readOnly", true)
            .put("source", METADATA.toString())
            .put("order", "lastAccess DESC")
            .put("limit", MAX_BOOKS)
            .put("count", books.length())
            .put("books", books)
    }

    companion object {
        private const val MAX_BOOKS = 20
        private val METADATA =
            Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
        // Keep native progress and timestamp values intact; their representation is
        // firmware-specific.
        private val BOOK_FIELDS =
            listOf(
                "uuid",
                "idString",
                "title",
                "name",
                "authors",
                "author",
                "path",
                "nocasePath",
                "lastAccess",
                "progress",
                "readingStatus",
            )
    }
}
