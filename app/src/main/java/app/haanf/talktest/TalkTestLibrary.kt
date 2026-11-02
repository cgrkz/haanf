package app.haanf.talktest

import android.content.Context
import app.haanf.data.TalkTestDao
import app.haanf.data.TalkTestEntity
import app.haanf.export.Csv
import app.haanf.export.ShareFiles
import java.io.File

/** Saved recordings live in app-private storage and leave only through [export]. */
class TalkTestLibrary(
    private val context: Context,
    private val dao: TalkTestDao,
) {
    private val directory get() = File(context.filesDir, "talk_tests").apply { mkdirs() }

    fun audioFile(fileName: String): File = File(directory, fileName)

    /** A process death mid-recording leaves a partial file with no database row. */
    fun removeUnfinished() {
        directory.listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
    }

    suspend fun delete(test: TalkTestEntity) {
        audioFile(test.fileName).delete()
        dao.delete(test.id)
        ShareFiles.clear(context)
    }

    /** Shares the audio with a CSV of labels, because a WAV alone has no heart rate data. */
    fun export(tests: List<TalkTestEntity>) {
        ShareFiles.clear(context)
        val labels = ShareFiles.write(context, "talk-tests.csv", Csv.talkTests(tests))
        val audio = tests.map { audioFile(it.fileName) }.filter { it.exists() }
        ShareFiles.send(context, audio + labels, "*/*", "Export talk tests")
    }

    companion object {
        const val TEMP_SUFFIX = ".part"
    }
}
