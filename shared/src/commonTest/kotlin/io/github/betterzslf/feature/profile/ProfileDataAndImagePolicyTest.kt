package io.github.betterzslf.feature.profile

import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic headers test the predecode gate only, not a successful PNG/JPEG render. */
internal fun profilePngHeader(width: Int = 64, height: Int = 64): ByteArray = ByteArray(33).apply {
    val magic = intArrayOf(137, 80, 78, 71, 13, 10, 26, 10)
    for (index in magic.indices) this[index] = magic[index].toByte()
    this[11] = 13
    for ((index, char) in "IHDR".withIndex()) this[12 + index] = char.code.toByte()
    fun integer(offset: Int, number: Int) { for (index in 0..3) this[offset + index] = (number ushr (24 - index * 8)).toByte() }
    integer(16, width); integer(20, height); this[24] = 8; this[25] = 6
}
internal fun profileJpegHeader(width: Int = 64, height: Int = 64): ByteArray = intArrayOf(
    255, 216, 255, 192, 0, 11, 8, height ushr 8, height and 255, width ushr 8, width and 255, 1, 1, 17, 0,
).map { it.toByte() }.toByteArray()

class ProfileDataAndImagePolicyTest {
    @Test
    fun emptyMissingMalformedAndUnsupportedMetadataAreNotConflated() {
        fun parse(raw: String) = ProfileDataDecoder.photo(Json.parseToJsonElement(raw)) { AvatarImagePolicy.filesBaseUrl + it }
        assertEquals(ProfilePhotoMetadata.Empty, parse("""{"attachment":{"filepath":""}}"""))
        assertEquals(ProfilePhotoMetadata.Missing, parse("{}"))
        assertEquals(ProfilePhotoMetadata.Missing, parse("""{"attachment":null}"""))
        assertEquals(ProfilePhotoMetadata.Missing, parse("""{"attachment":{"filename":"not-a-path.png"},"url":"https://evil.invalid"}"""))
        assertNull(parse("[]")); assertNull(parse("""{"attachment":"wrong"}""")); assertNull(parse("""{"attachment":{"filepath":123}}"""))
        assertEquals(ProfilePhotoMetadata.UnsupportedPath, parse("""{"attachment":{"filepath":"https://evil.invalid/avatar.png"}}"""))
        val resource = assertIs<ProfilePhotoMetadata.Resource>(parse("""{"attachment":{"filepath":"/image/fixture.png"}}"""))
        assertEquals(AvatarImagePolicy.filesBaseUrl + "image/fixture.png", resource.url)
        assertFalse(resource.toString().contains("fixture.png"))
    }

    @Test
    fun relativePathsNeverResolveForeignUrisTraversalOrDoubleUserfilesPrefix() {
        for (bad in listOf("", "https://evil.invalid/a", "data:image/png;base64,AAAA", "file:///fixture", "//evil.invalid/a", "userfiles/a.png", "/userfiles/a.png", "USERFILES/a.png", "a/../b", "./a", "a//b", "a\\b", "a%2fb", "a?token=x", "a#x", " a.png", "a\u007f.png")) {
            assertNull(AvatarImagePolicy.relativePath(bad), "Path gate failed")
        }
        assertEquals("image/a.png", AvatarImagePolicy.relativePath("/image/a.png"))
        assertTrue(AvatarImagePolicy.isAllowedUrl(AvatarImagePolicy.filesBaseUrl + "image/a.png"))
        assertFalse(AvatarImagePolicy.isAllowedUrl(AvatarImagePolicy.filesBaseUrl + "userfiles/image/a.png"))
        assertFalse(AvatarImagePolicy.isAllowedUrl("https://evil.invalid/image/a.png"))
    }

    @Test
    fun fieldsUseOnlyEvidenceBackedUserInfoAndMissingCountersStayUnknown() {
        val data = Json.parseToJsonElement("""{"userInfo":{"schoolName":"Fixture school","studentNum":"Fixture student","account":"Fixture account","typeValue":"Fixture role"},"deptInfo":{"name":"not a name fallback","department":"not evidenced"}}""")
        val fields = assertNotNull(ProfileDataDecoder.fields(data))
        assertNull(fields.name); assertEquals("Fixture school", fields.schoolName)
        assertEquals("Fixture student", fields.studentNumber); assertEquals("Fixture account", fields.account)
        assertEquals("Fixture role", fields.identity)
        assertNull(ProfileDataDecoder.fields(Json.parseToJsonElement("""{"deptInfo":{"schoolName":"not interchangeable"}}""")))
        val stats = assertNotNull(ProfileDataDecoder.statistics(Json.parseToJsonElement("""{"collect":0,"comment":-1,"history":"999999999999999999999999"}""")))
        assertEquals(0L, stats.collected); assertNull(stats.comments); assertNull(stats.history)
        assertEquals(ProfileStatistics(null, null, null), ProfileDataDecoder.statistics(JsonObject(emptyMap())))
    }

    @Test
    fun pngAndJpegHeadersAreRecognizedWithoutClaimingFullDecode() {
        val png = assertIs<AvatarHeaderCheck.Accepted>(AvatarImagePolicy.inspect(profilePngHeader()))
        assertEquals(AvatarHeader(64, 64, AvatarFormat.PNG), png.header)
        val jpeg = assertIs<AvatarHeaderCheck.Accepted>(AvatarImagePolicy.inspect(profileJpegHeader(128, 256)))
        assertEquals(AvatarHeader(128, 256, AvatarFormat.JPEG), jpeg.header)
        val exifSegment = intArrayOf(255, 216, 255, 225, 0, 4, 0, 0).map { it.toByte() }.toByteArray()
        assertIs<AvatarHeaderCheck.Accepted>(AvatarImagePolicy.inspect(exifSegment + profileJpegHeader().drop(2).toByteArray()))
    }

    @Test
    fun malformedOrWebpHeadersAndExcessiveDimensionsAreRejectedBeforeDecoder() {
        fun issue(bytes: ByteArray) = assertIs<AvatarHeaderCheck.Rejected>(AvatarImagePolicy.inspect(bytes)).issue
        assertEquals(AvatarIssue.UNSUPPORTED_FORMAT, issue("RIFFxxxxWEBP".encodeToByteArray()))
        assertEquals(AvatarIssue.MALFORMED_IMAGE, issue(profilePngHeader().copyOf(20)))
        assertEquals(AvatarIssue.MALFORMED_IMAGE, issue(profileJpegHeader().apply { this[5] = 0 }))
        assertEquals(AvatarIssue.DIMENSIONS, issue(profilePngHeader(0, 64)))
        assertEquals(AvatarIssue.DIMENSIONS, issue(profilePngHeader(2049, 1)))
        assertEquals(AvatarIssue.DIMENSIONS, issue(profilePngHeader(2048, 2048)))
        assertEquals(AvatarIssue.DIMENSIONS, issue(profilePngHeader(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertEquals(AvatarIssue.DIMENSIONS, issue(profileJpegHeader(2049, 1)))
        assertIs<AvatarHeaderCheck.Accepted>(AvatarImagePolicy.inspect(profilePngHeader(2000, 2000)))
        assertEquals(AvatarIssue.TOO_LARGE, issue(ByteArray(AvatarImagePolicy.MAX_BYTES + 1)))
    }
}
