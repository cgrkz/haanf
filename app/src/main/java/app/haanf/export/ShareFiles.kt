package app.haanf.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** The only route off the phone: every call here starts from the user tapping export. */
object ShareFiles {
    private fun exportDir(context: Context) = File(context.cacheDir, "exports")

    /** Removes any earlier export so that private data does not linger in the cache. */
    fun clear(context: Context) {
        exportDir(context).deleteRecursively()
    }

    fun write(context: Context, name: String, content: String): File {
        val dir = exportDir(context).apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    fun send(context: Context, files: List<File>, mimeType: String, title: String) {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
            clipData = ClipData.newRawUri(title, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
