package io.github.betterzslf.feature.schedule

/** Strict UTF-8 first. Legacy decoding is a platform codec, never a lossy replacement or handwritten character map. */
internal fun decodeAcademicHtml(bytes: ByteArray): String {
    if (bytes.isEmpty() || bytes.size > 2_097_152) throw AcademicProtocolException()
    return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()?.removePrefix("\uFEFF")
        ?: decodeAcademicGb18030(bytes)?.removePrefix("\uFEFF")
        ?: throw AcademicProtocolException()
}

internal expect fun decodeAcademicGb18030(bytes: ByteArray): String?
