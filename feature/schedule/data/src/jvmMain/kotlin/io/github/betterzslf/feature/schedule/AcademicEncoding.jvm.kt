package io.github.betterzslf.feature.schedule

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal actual fun decodeAcademicGb18030(bytes: ByteArray): String? = runCatching {
    Charset.forName("GB18030").newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}.getOrNull()
