package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** The probe and the handshake as capabilities, so the controller needs no network in tests. */
fun interface CampusDiagnosisProbe {
    suspend fun probe(portalUrl: String): PortalProbeResult
}

fun interface CampusDiagnosisHandshake {
    suspend fun negotiate(
        server: AuthServer,
        address: PortalAddress,
        schoolId: String,
        domain: String,
        area: String,
    ): DormHandshakeResult
}

data class CampusDiagnosisState(
    val diagnosis: CampusDiagnosis = CampusDiagnosis(),
    val running: Boolean = false,
    val message: String = "",
) {
    val hasResult: Boolean get() = diagnosis.attempted
}

/**
 * Reads what the campus network actually is, in one user-initiated pass.
 *
 * Two reads, in the only order that can work:
 *  1. ask the portal for its address; its response headers carry the school identity;
 *  2. ask one auth server for a cipher family, echoing that identity back.
 *
 * Step 2 is why step 1 must come first: the gateway rejects `ticket.cgi` without the identity, and
 * the error code it returns in that case tells us which header it wanted.
 *
 * Nothing is sent until the user asks, nothing is retried automatically, and no credential is used:
 * this is a read-only report, not a login.
 */
class CampusDiagnosisController(
    private val portalAddress: suspend () -> String?,
    private val probe: CampusDiagnosisProbe?,
    private val handshake: CampusDiagnosisHandshake?,
    private val authServers: () -> List<AuthServer> = { DORM_AUTH_SERVERS },
) {
    private val mutable = MutableStateFlow(CampusDiagnosisState())
    val state: StateFlow<CampusDiagnosisState> = mutable.asStateFlow()
    private val flight = Mutex()

    suspend fun run(): Boolean {
        if (!flight.tryLock()) return false
        try {
            val probeSource = probe
            val handshakeSource = handshake
            if (probeSource == null || handshakeSource == null) {
                mutable.value = mutable.value.copy(running = false,
                    message = AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS)
                return false
            }
            val target = portalAddress()
            if (target.isNullOrBlank()) {
                // Without the school-provided portal address there is nothing to probe: that is the
                // normal off-campus outcome, not a bug, so it is stated plainly.
                mutable.value = CampusDiagnosisState(diagnosis = CampusDiagnosis(attempted = true),
                    message = AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS)
                return false
            }
            mutable.value = mutable.value.copy(running = true, message = "")
            val probed = callProbe(probeSource, target)
            if (probed !is PortalProbeResult.Answered) {
                mutable.value = CampusDiagnosisState(
                    diagnosis = CampusDiagnosis(portalAddress = target, attempted = true),
                    message = AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS)
                return false
            }
            val address = parsePortalAddress(probed.url)
            if (address == null) {
                // The portal answered but did not carry wlanuserip/wlanacip, so no dial is possible.
                mutable.value = CampusDiagnosisState(
                    diagnosis = CampusDiagnosis(portalAddress = target, probedUrl = probed.url,
                        schoolId = probed.schoolId, domain = probed.domain, area = probed.area,
                        attempted = true),
                    message = AppText.CampusNetwork.DIAGNOSIS_NO_ANSWER)
                return false
            }
            val server = authServers().firstOrNull()
            if (server == null) {
                mutable.value = mutable.value.copy(running = false,
                    message = AppText.CampusNetwork.DIAGNOSIS_NO_ANSWER)
                return false
            }
            return when (val result = callHandshake(handshakeSource, server, address,
                probed.schoolId, probed.domain, probed.area)) {
                is DormHandshakeResult.Negotiated -> {
                    mutable.value = CampusDiagnosisState(diagnosis = CampusDiagnosis(
                        portalAddress = target, probedUrl = probed.url,
                        schoolId = probed.schoolId, domain = probed.domain, area = probed.area,
                        algoId = result.algoId, attempted = true,
                        algorithmHint = campusAlgoFamilyName(result.algoId)))
                    true
                }
                is DormHandshakeResult.Rejected -> {
                    mutable.value = CampusDiagnosisState(diagnosis = CampusDiagnosis(
                        portalAddress = target, probedUrl = probed.url,
                        schoolId = probed.schoolId, domain = probed.domain, area = probed.area,
                        errorCode = result.errorCode, attempted = true))
                    false
                }
                is DormHandshakeResult.UnreadableBlob -> {
                    mutable.value = CampusDiagnosisState(diagnosis = CampusDiagnosis(
                        portalAddress = target, probedUrl = probed.url,
                        schoolId = probed.schoolId, domain = probed.domain, area = probed.area,
                        errorCode = result.errorCode, attempted = true),
                        message = AppText.CampusNetwork.DIAGNOSIS_NO_ANSWER)
                    false
                }
                DormHandshakeResult.Unreachable -> {
                    mutable.value = CampusDiagnosisState(diagnosis = CampusDiagnosis(
                        portalAddress = target, probedUrl = probed.url, attempted = true),
                        message = AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS)
                    false
                }
            }
        } finally {
            if (mutable.value.running) mutable.value = mutable.value.copy(running = false)
            flight.unlock()
        }
    }

    private suspend fun callProbe(source: CampusDiagnosisProbe, target: String): PortalProbeResult = try {
        source.probe(target)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { PortalProbeResult.Unreachable }

    private suspend fun callHandshake(
        source: CampusDiagnosisHandshake,
        server: AuthServer,
        address: PortalAddress,
        schoolId: String,
        domain: String,
        area: String,
    ): DormHandshakeResult = try {
        source.negotiate(server, address, schoolId, domain, area)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { DormHandshakeResult.Unreachable }

    override fun toString(): String =
        "CampusDiagnosisController(hasAlgoId=${mutable.value.diagnosis.hasAlgoId})"
}
