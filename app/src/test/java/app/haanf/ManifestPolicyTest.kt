package app.haanf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the promises the product makes: read-only health data and nothing backed up. */
class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `never requests a Health Connect write permission`() {
        assertFalse(manifest.contains("android.permission.health.WRITE"))
    }

    @Test
    fun `requests read access to the five data types in the brief`() {
        val reads = Regex("android\\.permission\\.health\\.READ_\\w+").findAll(manifest).map { it.value }.toSet()
        assertEquals(5, reads.size)
    }

    @Test
    fun `opts out of backup`() {
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `has no internet permission, so data cannot leave except by export`() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }
}
