package com.troy.orcaplayer.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSecretsTest {
    @Test
    fun keysAndPasswordsStayOutOfPlainBackups() {
        assertTrue(SettingsRepository.isSecretKey("global/media_library.tmdb_key"))
        assertTrue(SettingsRepository.isSecretKey("p/default/sources.trakt_client_secret"))
        assertTrue(SettingsRepository.isSecretKey("p/kid1/subtitles.opensubtitles_key"))
        assertFalse(SettingsRepository.isSecretKey("p/default/sources.user_agent"))
        assertFalse(SettingsRepository.isSecretKey("p/default/layout.player"))
    }
}
