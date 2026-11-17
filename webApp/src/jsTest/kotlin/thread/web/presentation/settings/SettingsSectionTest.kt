package thread.web.presentation.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D84: `#/settings/<block>` links, and the Preferences link from before the per-screen blocks. */
class SettingsSectionTest {
    @Test
    fun aBlockLinkOpensThatBlock() {
        assertEquals("statuses", settingsSection("statuses"))
        assertEquals("today", settingsSection("today"))
        assertEquals("jira", settingsSection("jira"))
    }

    @Test
    fun theOldPreferencesLinkOpensToday() {
        assertEquals("today", settingsSection("prefs"))
    }

    @Test
    fun settingsAloneOpensTheTop() {
        assertNull(settingsSection(null))
    }
}
