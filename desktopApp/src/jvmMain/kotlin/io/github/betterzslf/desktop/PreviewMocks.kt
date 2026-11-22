package io.github.betterzslf.desktop

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.platform.PlatformServices
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * Mock-data preview platform: the same host stubs as JvmPlatform, but the HTTP edge is a
 * MockEngine serving canned offline payloads from desktopApp resources, and the store is
 * pre-seeded with a preview session so the preview opens straight past the campus login.
 * The academic timetable keeps its own interactive binding: in the preview enter account
 * FIXTURE001 with any password; the mock serves synthetic fixture HTML. Never used by any
 * product surface.
 */
private const val SESSION_KEY = "better-zslf.session.v1"
private const val PREVIEW_SESSION =
    """{"token":"preview-token","userInfo":{"id":"preview-user"},"deptInfo":{}}"""

internal fun previewPlatform(): PlatformServices = object : PlatformServices {
    private val values = mutableMapOf(SESSION_KEY to PREVIEW_SESSION)
    override val storage = object : KeyValueStore {
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }
    override val versionName = "desktop-preview"
    override val description = "JVM UI preview with mock data"
    override fun httpClient(): HttpClient = HttpClient(PreviewMocks.engine())
    override fun openUrl(url: String) = Unit
    override fun openPortalGateway(url: String): Boolean =
        io.github.betterzslf.feature.campusnet.campusPortalAddressAllowed(url)
    override fun copyToClipboard(text: String): Boolean = false
}

private object PreviewMocks {
    fun engine(): MockEngine = MockEngine { request ->
        val path = request.url.encodedPath
        println("[mock] ${request.method.value} $path")
        when {
            // Academic timetable: independent binding with its own HTML protocol.
            path == "/jsxsd/" -> html("academic-login.html")
            path == "/jsxsd/xk/LoginToXk" ->
                respond("", HttpStatusCode.Found, headersOf("Location", "/jsxsd/framework/xsMain.jsp"))
            path == "/jsxsd/framework/xsMain.jsp" -> html("academic-identity.html")
            path == "/jsxsd/xskb/xskb_list.do" -> html("academic-schedule.html")
            path == "/jsxsd/jxzl/jxzl_query" -> html("academic-calendar.html")
            // Campus app API.
            path == "/app/login" -> json("login.json")
            path == "/app/appUser/getUserInfo" -> json("user-info.json")
            path == "/app/appUser/getUserPhotoURL" -> json("user-photo.json")
            path == "/app/appUser/getUserArctileStatis" -> json("user-statistics.json")
            path == "/app/appCmsArticle/articleDetailsByName" -> json("document.json")
            path == "/app/checkVersion" -> json("check-version.json")
            path == "/run/errand/getErrandList" -> json("errand-list.json")
            path == "/run/errand/getErrandDetail" -> json("errand-detail.json")
            path == "/run/errand/getUserErrandTrackRecord" -> json("track-records.json")
            path.startsWith("/run/errand/") -> json("generic-run.json")
            path.startsWith("/app/verification/") || path.startsWith("/app/campusNetwork/") ->
                json("campus-generic.json")
            // Avatar bytes travel through the same app client; serve the canned image.
            path.startsWith("/userfiles/") -> bytes("avatar.png")
            else -> {
                println("[mock] unhandled path served with empty success: $path")
                respond("""{"code":200,"data":{},"message":"ok"}""")
            }
        }
    }

    private fun MockRequestHandleScope.html(name: String) = respond(resource(name))
    private fun MockRequestHandleScope.json(name: String) = respond(resource(name))
    private fun MockRequestHandleScope.bytes(name: String) =
        respond(resourceBytes(name), HttpStatusCode.OK, headersOf("Content-Type", "image/png"))

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/mock/$name")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Missing preview mock resource: $name")

    private fun resourceBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/mock/$name")?.use { it.readBytes() }
            ?: error("Missing preview mock resource: $name")
}
