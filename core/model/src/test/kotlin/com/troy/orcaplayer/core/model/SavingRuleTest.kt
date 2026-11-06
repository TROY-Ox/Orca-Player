package com.troy.orcaplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SavingRuleTest {
    private val local = SourceRef(SourceKind.LOCAL, "local")
    private val addon = SourceRef(SourceKind.STREMIO_ADDON, "addon.a")

    @Test
    fun drmIsNeverSaved() {
        assertEquals(SaveDecision.DENIED_DRM, SavingRule.canSave(local, true, true, SavingPolicy()))
    }

    @Test
    fun localIsAlwaysAllowed() {
        assertEquals(SaveDecision.ALLOWED, SavingRule.canSave(local, false, true, SavingPolicy(savingEnabled = false)))
    }

    @Test
    fun addonNeedsReminderFirst() {
        assertEquals(SaveDecision.NEEDS_REMINDER, SavingRule.canSave(addon, false, true, SavingPolicy()))
        assertEquals(SaveDecision.ALLOWED, SavingRule.canSave(addon, false, true, SavingPolicy(reminderAccepted = true)))
    }

    @Test
    fun sourceAndSettingsCanBlock() {
        val ok = SavingPolicy(reminderAccepted = true)
        assertEquals(SaveDecision.DENIED_BY_SOURCE, SavingRule.canSave(addon, false, false, ok))
        assertEquals(SaveDecision.DENIED_BY_SETTINGS, SavingRule.canSave(addon, false, true, ok.copy(blockedSources = setOf("addon.a"))))
        assertEquals(SaveDecision.DENIED_BY_SETTINGS, SavingRule.canSave(addon, false, true, ok.copy(profileAllowsSaving = false)))
    }
}
