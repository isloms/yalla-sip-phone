package uz.yalla.sipphone.ui.strings

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StringResourcesTest {
    @Test
    fun `UzStrings implements all properties`() {
        val s: StringResources = UzStrings
        assertTrue(s.loginTitle.isNotBlank())
        assertTrue(s.loginButton.isNotBlank())
        assertTrue(s.settingsTitle.isNotBlank())
        assertTrue(s.agentStatusOnline.isNotBlank())
        assertTrue(s.sipConnected.isNotBlank())
    }

    @Test
    fun `RuStrings implements all properties`() {
        val s: StringResources = RuStrings
        assertTrue(s.loginTitle.isNotBlank())
        assertTrue(s.loginButton.isNotBlank())
        assertTrue(s.settingsTitle.isNotBlank())
    }

    @Test
    fun `UZ and RU strings differ`() {
        assertNotEquals(UzStrings.loginSubtitle, RuStrings.loginSubtitle)
        assertNotEquals(UzStrings.loginButton, RuStrings.loginButton)
        assertNotEquals(UzStrings.agentStatusOnline, RuStrings.agentStatusOnline)
    }
}
