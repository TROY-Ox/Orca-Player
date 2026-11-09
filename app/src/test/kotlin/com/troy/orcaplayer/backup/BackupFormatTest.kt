package com.troy.orcaplayer.backup

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val payload = BackupPayload(
        app = "1.0.0", createdAt = 1L, dbVersion = 3,
        sections = mapOf(
            BackupSection.SETTINGS.name to buildJsonObject { put("p/default/player.speed", "1.5") },
            BackupSection.MY_LIST.name to JsonPrimitive("rows"),
        ),
    )

    @Test
    fun plainRoundTrip() {
        val bytes = BackupFormat.write(payload, null)
        assertFalse(BackupFormat.envelope(bytes).encrypted)
        val back = BackupFormat.read(bytes, null)
        assertEquals(payload, back)
        assertEquals(listOf(BackupSection.SETTINGS, BackupSection.MY_LIST), back.present)
    }

    @Test
    fun encryptedRoundTrip() {
        val bytes = BackupFormat.write(payload, "correct horse")
        assertTrue(BackupFormat.envelope(bytes).encrypted)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("player.speed"))
        assertEquals(payload, BackupFormat.read(bytes, "correct horse"))
    }

    @Test(expected = BackupPasswordException::class)
    fun wrongPasswordIsRefused() {
        BackupFormat.read(BackupFormat.write(payload, "one"), "two")
    }

    @Test(expected = BackupPasswordException::class)
    fun missingPasswordIsRefused() {
        BackupFormat.read(BackupFormat.write(payload, "one"), null)
    }

    @Test(expected = BackupFormatException::class)
    fun otherFilesAreRefused() {
        BackupFormat.read("{\"format\":\"something-else\"}".toByteArray(), null)
    }
}
