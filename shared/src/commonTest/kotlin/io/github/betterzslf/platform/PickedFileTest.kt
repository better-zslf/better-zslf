package io.github.betterzslf.platform

import kotlin.test.*

class PickedFileTest {
    @Test fun pathsAndControlCharactersDoNotEscapeTheSelectedName() {
        val file = PickedFile("C:\\private\\folder/route\u0000.gpx", "application/gpx+xml", byteArrayOf(1))
        assertEquals("route.gpx", file.name)
        assertFalse(file.toString().contains("route"))
    }
    @Test fun fileBytesAreDefensiveCopies() {
        val original = byteArrayOf(1, 2, 3)
        val file = PickedFile("route.gpx", "application/gpx+xml", original)
        original[0] = 9
        val read = file.readBytes()
        assertContentEquals(byteArrayOf(1, 2, 3), read)
        read[0] = 8
        assertEquals(1, file.readBytes()[0].toInt())
        assertEquals(3, file.size)
    }
    @Test fun oversizedFileIsRejectedBeforeCopy() {
        assertFailsWith<IllegalArgumentException> { PickedFile("large", "application/octet-stream", ByteArray(MAX_PICKED_FILE_BYTES + 1)) }
    }
    @Test fun emptyAndVeryLongNamesAreNormalized() {
        assertEquals("attachment", PickedFile("/", "text/plain", byteArrayOf()).name)
        assertEquals(120, PickedFile("x".repeat(500), "text/plain", byteArrayOf()).name.length)
    }
}
