package io.github.betterzslf.feature.campusnet

/**
 * Portal facts observed on this network, shared between the diagnosis run and the dialer.
 *
 * The two `wlan*` addresses and the school identity cannot be configured or guessed: they exist
 * only while the device is on the campus network, and the reference implementation refuses to dial
 * without them. The diagnosis run already reads all three, so it records them here and the dialer
 * consumes them — no copy/paste, and no second probe that could observe a different gateway.
 *
 * Process-local and never persisted: they describe the network the app is on right now.
 */
class CampusDiagnosisControllerHolder {
    @Volatile
    var observed: PortalAddress? = null
        private set

    @Volatile
    var identity: Triple<String, String, String>? = null
        private set

    /** Whether a probe has run at all, even if it yielded nothing the dialer can use. */
    @Volatile
    var probed = false
        private set

    /**
     * Records what a probe saw.
     *
     * [PortalAddress] can legitimately fail to parse — a portal that answers without the two `wlan*`
     * parameters is a real outcome, not a bug. Recording it as "observed" would then let the sign-in
     * button report a missing address right after the panel said the probe succeeded, which reads as
     * a contradiction.
     */
    fun record(probedUrl: String, schoolId: String, domain: String, area: String) {
        observed = parsePortalAddress(probedUrl)
        identity = Triple(schoolId, domain, area)
        probed = true
    }

    fun clear() {
        observed = null
        identity = null
        probed = false
    }

    override fun toString(): String =
        "CampusDiagnosisControllerHolder(probed=$probed, usable=${observed != null})"
}
