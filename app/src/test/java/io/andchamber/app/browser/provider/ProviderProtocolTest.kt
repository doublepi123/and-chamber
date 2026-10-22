package io.andchamber.app.browser.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderProtocolTest {
    @Test
    fun `selector is exact`() {
        assertEquals("ref-1", ProviderProtocol.selectorRef("""[data-andchamber-ref="ref-1"]"""))
        assertNull(ProviderProtocol.selectorRef("button.apply"))
        assertNull(ProviderProtocol.selectorRef("""[data-andchamber-ref="ref-1"] div"""))
    }
}
