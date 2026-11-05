package io.github.betterzslf.feature.campusnet

import kotlin.random.Random

/**
 * The pseudo-identity the dialer presents: host name, MAC and OS tag.
 *
 * These are not secrets and not the device's real identity — the reference generates them randomly
 * at the start of each session so that a re-dial after a dropped connection is not refused as a
 * duplicate client. Sending the device's actual MAC would be worse: it is a stable identifier, and
 * the gateway keys its duplicate-session check on whatever we send.
 *
 * The concrete formats match the reference exactly:
 *  - host name: 10 characters, lowercase hex, first byte's low bit cleared;
 *  - MAC: six colon-separated lowercase hex pairs, first byte's low bit cleared (unicast);
 *  - ostag: fixed per channel, so the gateway serves that platform's cipher family.
 */
data class DormClientIdentity(
    val hostName: String,
    val mac: String,
    val ostag: String,
) {
    override fun toString(): String = "DormClientIdentity(ostag=$ostag)"
}

/** `ostag` for the Android channel, which is the family of ids this build implements. */
const val DORM_OSTAG_ANDROID: String = "3"

/**
 * Builds a fresh identity.
 *
 * [random] is injectable so tests can pin the output; production uses the default source.
 */
fun newDormClientIdentity(random: Random = Random.Default): DormClientIdentity {
    // 10 bytes, matching the reference's buffer even though only five are formatted.
    val hostBytes = ByteArray(10) { random.nextInt(256).toByte() }
    // Clearing the low bit mirrors the reference and keeps the first octet away from odd values,
    // which some access controllers treat as multicast.
    hostBytes[0] = (hostBytes[0].toInt() and 0xFE).toByte()
    val hostName = buildString {
        for (i in 0 until 5) append(hex2(hostBytes[i].toInt() and 0xFF))
    }

    val macBytes = ByteArray(6) { random.nextInt(256).toByte() }
    // Unicast bit cleared: a locally administered unicast address, as the reference produces.
    macBytes[0] = (macBytes[0].toInt() and 0xFE).toByte()
    val mac = buildString {
        for (i in macBytes.indices) {
            if (i > 0) append(':')
            append(hex2(macBytes[i].toInt() and 0xFF))
        }
    }
    return DormClientIdentity(hostName, mac, DORM_OSTAG_ANDROID)
}

private fun hex2(v: Int): String = ((v and 0xFF) + 0x100).toString(16).substring(1)
