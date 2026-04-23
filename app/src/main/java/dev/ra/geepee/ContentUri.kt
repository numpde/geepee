package dev.ra.geepee

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (columnIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(columnIndex)
        } else {
            null
        }
    }
}
