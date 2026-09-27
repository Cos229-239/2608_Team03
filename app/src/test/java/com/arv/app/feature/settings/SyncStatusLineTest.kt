package com.arv.app.feature.settings

import com.arv.app.core.sync.SyncSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one line Settings says about sharing, and what it may not leave out. */
class SyncStatusLineTest {

    private val shared = SyncSettings.Last(at = 0L, outcome = SyncSettings.Outcome.UP_TO_DATE, refused = 0)

    @Test
    fun `files that have not gone up are said, not hidden behind Shared`() {
        val (text, needsAction) = statusLine(SyncViewModel.Ui(on = true, last = shared, filesWaiting = 2))
        assertTrue(text, text.startsWith("Shared at "))
        assertTrue(text, text.endsWith(" 2 files have not reached the family yet."))
        assertFalse("nothing here for the person to fix", needsAction)
    }

    @Test
    fun `one file is one file`() {
        val (text, _) = statusLine(SyncViewModel.Ui(on = true, last = shared, filesWaiting = 1))
        assertTrue(text, text.endsWith(" 1 file has not reached the family yet."))
    }

    @Test
    fun `nothing waiting adds nothing`() {
        val (text, _) = statusLine(SyncViewModel.Ui(on = true, last = shared))
        assertFalse(text, text.contains("file"))
        assertFalse(text, text.contains("waiting"))
    }
}
