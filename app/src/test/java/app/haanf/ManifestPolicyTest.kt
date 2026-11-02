package app.haanf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the promises the product makes: read-only health data, no backups, and known hosts only. */
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
    fun `allows encrypted connections only`() {
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
    }

    @Test
    fun `the only network hosts in the app are the ones the user chose a key for`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        val hosts = sources.flatMap { Regex("https://[a-zA-Z0-9.-]+").findAll(it.readText()).map { match -> match.value } }
            .toSet()
        assertEquals(setOf("https://openrouter.ai"), hosts)
    }

    @Test
    fun `no secret from the dev env file is built into the app`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        assertFalse(sources.any { it.readText().contains("hinoki", ignoreCase = true) })
        assertFalse(File("build.gradle.kts").readText().contains(".env"))
    }
}
