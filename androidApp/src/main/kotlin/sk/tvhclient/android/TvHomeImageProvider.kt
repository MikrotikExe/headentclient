package sk.tvhclient.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * M580-fix: tile images for the row on the Android TV home screen.
 *
 * The launcher (another process) downloads the programme images itself; a content:// URI via
 * FileProvider needed grantUriPermission for the launcher's specific package, which on
 * some boxes did not work (grey tile) and does not survive a restart. This provider
 * is exported without a permission and serves EXCLUSIVELY PNGs from cache/tvhome — they are only
 * channel logos on a dark background, nothing sensitive. It can do nothing else (query/insert/delete
 * are not supported).
 */
class TvHomeImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        if (!name.matches(Regex("[A-Za-z0-9._-]+\\.png"))) throw FileNotFoundException(uri.toString())
        val ctx = context ?: throw FileNotFoundException("no context")
        val file = File(File(ctx.cacheDir, "tvhome"), name)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** ?v= from the file's timestamp — the launcher caches the images by URI, so after a picon change
         *  (or after a failed attempt) it would otherwise hold on to the old/empty version. */
        fun uriFor(ctx: android.content.Context, file: File): Uri =
            Uri.parse("content://" + ctx.packageName + ".tvhome/" + file.name + "?v=" + file.lastModified())
    }
}
