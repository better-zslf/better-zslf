@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.betterzslf.feature.schedule

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

internal actual fun decodeAcademicGb18030(bytes: ByteArray): String? = runCatching {
    if (bytes.isEmpty()) return@runCatching ""
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
    val encoding = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.convert())
    NSString.create(data, encoding) as String?
}.getOrNull()
