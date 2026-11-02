package io.github.betterzslf.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationHelpersTest {
    @Test
    fun olderAdvertisedSchoolVersionIsNotAnUpgrade() {
        assertFalse(UpdateRepository.isNewerVersion("1.0.79", "1.0.83"))
        assertFalse(UpdateRepository.isNewerVersion("1.0.83", "1.0.83"))
        assertTrue(UpdateRepository.isNewerVersion("1.0.100", "1.0.83"))
        assertTrue(UpdateRepository.isNewerVersion("2.0", "1.99.99"))
        assertFalse(UpdateRepository.isNewerVersion("1.0.83.0", "1.0.83"))
        assertFalse(UpdateRepository.isNewerVersion("invalid", "1.0.83"))
        assertFalse(UpdateRepository.isNewerVersion("", "1.0.83"))
    }
}
