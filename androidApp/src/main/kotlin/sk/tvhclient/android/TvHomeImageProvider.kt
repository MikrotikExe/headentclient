package sk.tvhclient.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * M580-fix: obrazky dlazdic pre riadok na domovskej obrazovke Android TV.
 *
 * Launcher (iny proces) si obrazky programov stahuje sam; content:// URI cez
 * FileProvider potreboval grantUriPermission pre konkretny balik launchera, co na
 * niektorych boxoch nefungovalo (siva dlazdica) a nepreziva restart. Tento provider
 * je exportovany bez opravnenia a servuje VYLUCNE PNG z cache/tvhome — su to len
 * loga kanalov na tmavom podklade, nic citlive. Nic ine nevie (query/insert/delete
 * nie su podporovane).
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
        /** ?v= podla casu suboru — launcher si obrazky cachuje podla URI, po zmene piconu
         *  (alebo po neuspesnom pokuse) by inak drzal staru/prazdnu verziu. */
        fun uriFor(ctx: android.content.Context, file: File): Uri =
            Uri.parse("content://" + ctx.packageName + ".tvhome/" + file.name + "?v=" + file.lastModified())
    }
}
