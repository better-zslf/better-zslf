package io.github.betterzslf.feature.campusnet

import kotlin.random.Random
import kotlin.test.*

/**
 * The pseudo-identity the dialer presents.
 *
 * Worth pinning because a wrong shape fails on the network, not in a test: the gateway validates
 * these as part of the encrypted document and answers with the same generic code it uses for a bad
 * password.
 */
class DormClientIdentityTest {

    @Test fun theHostNameIsTenLowercaseHexCharacters() {
        repeat(50) {
            val id = newDormClientIdentity()
            assertEquals(10, id.hostName.length, id.hostName)
            assertTrue(id.hostName.all { c -> c in '0'..'9' || c in 'a'..'f' }, id.hostName)
        }
    }

    @Test fun theMacIsSixColonSeparatedPairs() {
        repeat(50) {
            val id = newDormClientIdentity()
            val parts = id.mac.split(':')
            assertEquals(6, parts.size, id.mac)
            for (p in parts) {
                assertEquals(2, p.length, id.mac)
                assertTrue(p.all { c -> c in '0'..'9' || c in 'a'..'f' }, id.mac)
            }
        }
    }

    @Test fun bothFirstOctetsHaveTheLowBitCleared() {
        // The reference clears it; an odd first octet is treated as multicast by some controllers.
        repeat(200) {
            val id = newDormClientIdentity()
            val hostFirst = id.hostName.substring(0, 2).toInt(16)
            val macFirst = id.mac.substring(0, 2).toInt(16)
            assertEquals(0, hostFirst and 1, "host ${id.hostName}")
            assertEquals(0, macFirst and 1, "mac ${id.mac}")
        }
    }

    @Test fun theOstagIdentifiesTheAndroidChannel() {
        // The gateway serves the cipher family for this channel; a different tag would ask for
        // another platform's ids, which this build may not implement.
        assertEquals("3", DORM_OSTAG_ANDROID)
        assertEquals("3", newDormClientIdentity().ostag)
    }

    @Test fun identitiesDifferBetweenCalls() {
        // The reference regenerates per session so a re-dial is not seen as a duplicate client.
        val ids = List(50) { newDormClientIdentity().mac }
        assertEquals(50, ids.toSet().size)
    }

    @Test fun theGeneratorIsReproducibleGivenASeed() {
        // Injectable randomness keeps the shape testable without depending on luck.
        val a = newDormClientIdentity(Random(42))
        val b = newDormClientIdentity(Random(42))
        assertEquals(a.hostName, b.hostName)
        assertEquals(a.mac, b.mac)
        assertEquals(a.ostag, b.ostag)
    }

    @Test fun theIdentityFieldsReachTheDocument() {
        // The values only matter if they are actually stamped into the envelope.
        val id = newDormClientIdentity(Random(7))
        val fields = DormXmlFields(
            userAgent = DORM_ANDROID_USER_AGENT, clientId = "cid", localTime = "t",
            hostName = id.hostName, ipv4 = "10.0.0.2", mac = id.mac,
            ostag = id.ostag, gwip = "10.0.0.1")
        val xml = buildDormXml(DormXmlKind.GetTicket, fields)
        assertTrue(xml.contains("<host-name>${id.hostName}</host-name>"), xml)
        assertTrue(xml.contains("<mac>${id.mac}</mac>"), xml)
        assertTrue(xml.contains("<ostag>${id.ostag}</ostag>"), xml)
    }

    @Test fun anEmptyHostNameOrMacWouldBeVisiblyWrong() {
        // Guards against a regression where these fields silently go back to empty strings: the
        // document would still be well-formed, which is exactly why it needs asserting.
        val empty = buildDormXml(DormXmlKind.GetTicket, DormXmlFields("ua", "cid", "t"))
        assertTrue(empty.contains("<host-name></host-name>"), empty)
        assertTrue(empty.contains("<mac></mac>"), empty)
        // The reference never sends these empty, so a populated envelope must differ.
        val populated = buildDormXml(DormXmlKind.GetTicket, DormXmlFields("ua", "cid", "t",
            hostName = "abc", mac = "aa:bb", ostag = "3"))
        assertNotEquals(empty, populated)
    }
}
