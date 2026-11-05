package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText

/**
 * One reading of what the campus network actually is, taken from the live gateway.
 *
 * The carrier dialer cannot be finished without three values that only exist on campus:
 * `algo_id`, and the school identity the gateway demands. Rather than asking the user to run
 * `curl`, the app collects them here and shows them, so a single visit to campus is enough.
 *
 * Every field is a protocol value copied verbatim. Nothing here is inferred, and nothing is sent
 * anywhere: this is a read-only report.
 */
data class CampusDiagnosis(
    val portalAddress: String = "",
    val probedUrl: String = "",
    val schoolId: String = "",
    val domain: String = "",
    val area: String = "",
    /** Empty until a real `ticket.cgi` handshake produced an id we could read. */
    val algoId: String = "",
    /** The gateway's own `Error-Code`, verbatim, when the handshake did not yield an id. */
    val errorCode: String = "",
    val attempted: Boolean = false,
    /** Which cipher family the id selects, when the id is one this build knows about. */
    val algorithmHint: String = "",
) {
    /** True once we have the one value that unblocks the dialer. */
    val hasAlgoId: Boolean get() = algoId.isNotBlank()

    /** Whether anything at all was actually read; an empty report is not worth offering to copy. */
    fun hasAnyReading(): Boolean =
        portalAddress.isNotBlank() || probedUrl.isNotBlank() || schoolId.isNotBlank() ||
            algoId.isNotBlank() || errorCode.isNotBlank()

    /**
     * A single copyable line. Kept compact and paste-safe: no spaces, no line breaks, so a report
     * survives whatever the user pastes it into.
     */
    fun reportLine(): String = listOf(
        "portal=$portalAddress",
        "probed=$probedUrl",
        "schoolId=$schoolId",
        "domain=$domain",
        "area=$area",
        "algoId=$algoId",
        "errorCode=$errorCode",
    ).joinToString("&")

    override fun toString(): String =
        "CampusDiagnosis(hasAlgoId=$hasAlgoId, errorCode=${errorCode.ifBlank { "-" }})"
}

/**
 * Maps a known `algo_id` to its cipher family name, for display.
 *
 * The 27 ids are exactly the Android-channel branches of the reference implementation's cipher
 * factory. Returning a name here does **not** mean the cipher is implemented — it says only which
 * one the gateway picked, which is the information needed to port the right one.
 */
fun campusAlgoFamilyName(algoId: String): String = CAMPUS_ALGO_FAMILIES[algoId.uppercase()].orEmpty()

/** A short, honest sentence about how far the dialer can get with this id. */
fun campusAlgoStatusText(diagnosis: CampusDiagnosis): String = when {
    diagnosis.hasAlgoId && diagnosis.algorithmHint.isNotBlank() ->
        AppText.CampusNetwork.diagnosisAlgoKnown(diagnosis.algorithmHint)
    diagnosis.hasAlgoId -> AppText.CampusNetwork.DIAGNOSIS_ALGO_UNKNOWN_FAMILY
    // Only claim a gateway code when there actually was one: an empty code means the handshake
    // never ran (usually no portal address, i.e. not on campus), which is a different situation.
    diagnosis.errorCode.isNotBlank() -> AppText.CampusNetwork.diagnosisErrorCode(diagnosis.errorCode)
    else -> AppText.CampusNetwork.DIAGNOSIS_NOT_RUN
}

/**
 * The Android-channel `algo_id` → cipher family table, from
 * `CipherFactory.c` lines under the `Android` section of the reference implementation.
 * Kept here so the diagnosis can name the algorithm instead of showing a bare UUID.
 */
val CAMPUS_ALGO_FAMILIES: Map<String, String> = mapOf(
    "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E" to "AES-CBC",
    "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4" to "AES-ECB",
    "5BFBA864-BBA9-42DB-8EAD-49B5F412BD81" to "3DES-CBC",
    "6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5" to "3DES-ECB",
    "B809531F-0007-4B5B-923B-4BD560398113" to "ZUC-128",
    "F3974434-C0DD-4C20-9E87-DDB6814A1C48" to "SM4-CBC",
    "ED382482-F72C-4C41-A76D-28EEA0F1F2AF" to "SM4-ECB",
    "D6544CFE-F2DE-459B-9B77-0F2B367EF169" to "SM4-variant-CBC",
    "D755A536-B551-468C-BD87-322182B223D4" to "SM4-variant-ECB",
    "73B3F4AE-643F-471F-B08C-A5893D7B3601" to "SM4-variant-CBC",
    "D522E657-2CC3-46BE-BBE9-9CF1DF896C41" to "SM4-variant-ECB",
    "B3047D4E-67DF-4864-A6A5-DF9B9E525C79" to "mod-XTEA",
    "C32C68F9-CA81-4260-A329-BBAFD1A9CCD1" to "mod-XTEA-IV",
    "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA" to "SNOW3G-variant",
    "5038A11D-75EA-4F75-AF8D-2DAD8E0E6A19" to "SNOW3G-variant",
    "319FC5AB-EC0E-46B9-A252-2285F9DAE813" to "triple-modified-TEA-ECB",
    "3084D519-54D7-4434-9564-26A8615BCEF2" to "triple-modified-TEA-ECB",
    "35101415-A20F-4DFE-B00B-0B4F3B2F8C66" to "triple-modified-TEA-CBC",
    "406BD7C5-C48A-4F48-B072-82D5948B537B" to "triple-modified-TEA-CBC",
    "BB2EA626-590B-4C42-82BE-E052FCBBB88E" to "double-AES-128-CBC",
    "3D74E736-AB35-47CD-B934-77EBEF2B42D3" to "double-AES-128-CBC",
    "DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61" to "double-AES-128-ECB",
    "7E6A7DE0-4C56-47E6-A25A-1B4DCD33B950" to "double-AES-128-ECB",
    "9ABF4D29-34DB-4CE9-BB8C-7E371D637758" to "double-3DES-CBC",
    "64BD1BB8-49F4-448F-9D63-C4E96FC068CC" to "double-3DES-CBC",
    "AD8BB5B0-0E72-4198-A362-96D52C1B7ED1" to "DES-ECB-six",
    "252F0396-5254-4A42-9390-FA2EF1EB8D6E" to "DES-ECB-six",
)
