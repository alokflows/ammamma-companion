package com.ammamma.companion

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Serves exactly one file — the camera-send photo — as a content:// URI.
 *
 * WHY a hand-rolled provider instead of androidx.core.content.FileProvider: this
 * project has ZERO third-party/androidx dependencies on purpose (app/build.gradle.kts
 * has an empty dependencies block — fewer moving parts on a 2 GB, API-27 phone). But
 * SOME content:// provider is still required: on targetSdk 24+, handing a file://
 * Uri to another app's Intent (the camera app, then WhatsApp) throws
 * FileUriExposedException. This is the smallest provider that satisfies that for a
 * single known file.
 *
 * It is read/write, not read-only: the system camera app WRITES the captured JPEG
 * here via EXTRA_OUTPUT, then WhatsApp (or the share chooser) READS it back to
 * attach — same file, two directions — so [openFile] honors whatever mode
 * (ParcelFileDescriptor "r"/"w"/"rw") the caller actually asked for.
 */
class ShareFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        // WhatsApp (and the plain share chooser) query display name + size before
        // attaching; OPENABLE columns are all they need from a single-file provider.
        val file = fileFor(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(file.name, file.length()))
        }
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    // Nothing ever creates/edits/removes rows through this provider: the camera app
    // writes bytes straight through openFile(), not via insert().
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    // Only ever serves the one known filename — no path is trusted from the caller,
    // so there is no traversal surface even though this is otherwise very permissive.
    private fun fileFor(uri: Uri): File? {
        if (uri.lastPathSegment != FILE_NAME) return null
        return context?.let { fileFor(it) }
    }

    companion object {
        const val AUTHORITY = "com.ammamma.companion.shareprovider"
        const val FILE_NAME = "share.jpg"

        /** The deterministic on-disk path — same file every time, so a photo taken
         *  right before process death is still found when onActivityResult arrives. */
        fun fileFor(context: Context): File? =
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, FILE_NAME) }

        fun uriFor(): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(FILE_NAME)
            .build()
    }
}
