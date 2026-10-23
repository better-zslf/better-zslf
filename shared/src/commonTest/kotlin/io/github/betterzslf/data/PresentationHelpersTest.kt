package io.github.betterzslf.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationHelpersTest {
    @Test
    fun unreadBadgeIsSumOfBothServerCounters() {
        assertEquals(7, NoticeRepository.unreadBadgeCount(buildJsonObject {
            put("unreadNumber", 3)
            put("noticeMutualCount", 4)
        }))
        assertEquals(0, NoticeRepository.unreadBadgeCount(buildJsonObject {
            put("unreadNumber", -2)
        }))
        assertEquals(Int.MAX_VALUE, NoticeRepository.unreadBadgeCount(buildJsonObject {
            put("unreadNumber", Int.MAX_VALUE)
            put("noticeMutualCount", 1)
        }))
    }

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
