package io.github.betterzslf.feature.schedule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KtorAcademicSourceTest {
    private fun body(content: OutgoingContent): Parameters = parseQueryString(assertIs<OutgoingContent.ByteArrayContent>(content).bytes().decodeToString())

    @Test fun constructionUnauthenticatedReadsAndCloseNeverStartNetworkWork() = runTest {
        var requests = 0
        val source = createAcademicSource(HttpClient(MockEngine { requests++; error("No automatic request") }))
        assertEquals(0, requests)
        assertFailsWith<AcademicSessionExpiredException> { source.terms() }
        assertFailsWith<AcademicSessionExpiredException> { source.fetch(ScheduleFixtures.term) }
        source.close(); source.close()
        assertFailsWith<CancellationException> { source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) }
        assertEquals(0, requests)
    }

    @Test fun privateCookiePathsHeadersIdentityAndAllWeekFormsAreIndependentOfTheCampusClient() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val cookieHeaders = HeadersBuilder().apply {
            append(HttpHeaders.SetCookie, "JSESSIONID=fixture-private; Path=/jsxsd/; Secure; HttpOnly")
            append(HttpHeaders.SetCookie, "loginOnly=fixture-path; Path=/jsxsd/xk; Secure; HttpOnly")
        }.build()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += request.method to path
            assertEquals("jwgln.zsc.edu.cn", request.url.host)
            assertEquals("https", request.url.protocol.name)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertFalse(request.headers[HttpHeaders.Cookie].orEmpty().contains("fixture-campus"))
            if (path != "/jsxsd/") assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("JSESSIONID=fixture-private"))
            if (path != "/jsxsd/xk/LoginToXk") assertFalse(request.headers[HttpHeaders.Cookie].orEmpty().contains("loginOnly="))
            when (path) {
                "/jsxsd/" -> respond(ScheduleFixtures.login, headers = cookieHeaders)
                "/jsxsd/xk/LoginToXk" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("loginOnly=fixture-path"))
                    assertEquals("RklYVFVSRTAwMQ==%%%Zml4dHVyZS1wYXNzd29yZA==", body(request.body)["encoded"])
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/jsxsd/framework/xsMain.jsp"))
                }
                "/jsxsd/framework/xsMain.jsp" -> { assertEquals(HttpMethod.Get, request.method); respond(ScheduleFixtures.identity) }
                "/jsxsd/xskb/xskb_list.do" -> {
                    if (request.method == HttpMethod.Post) {
                        val form = body(request.body)
                        assertEquals(ScheduleFixtures.selectedId, form["xnxq01id"])
                        assertEquals("0", form["zc"])
                        assertEquals("1", form["sfFD"])
                        assertNull(form["encoded"])
                    }
                    respond(ScheduleFixtures.schedule())
                }
                "/jsxsd/jxzl/jxzl_query" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(ScheduleFixtures.selectedId, body(request.body)["xnxq01id"])
                    respond(ScheduleFixtures.calendar())
                }
                else -> error("Unexpected fixture path")
            }
        }
        val owner = HttpClient(engine) { defaultRequest {
            header(HttpHeaders.Authorization, "fixture-campus-token")
            header(HttpHeaders.Cookie, "fixture-campus-cookie")
        } }
        val source = createAcademicSource(owner)
        try {
            assertTrue(requests.isEmpty())
            assertEquals(ScheduleFixtures.account, source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password).account)
            assertEquals(ScheduleFixtures.selectedId, source.terms().single { it.isDefault }.id)
            val snapshot = source.fetch(ScheduleFixtures.term)
            assertEquals(ScheduleFixtures.selectedId, snapshot.term.id)
            assertTrue(snapshot.complete)
            assertEquals(3, snapshot.courses.size)
            assertEquals("2026-08-31", snapshot.weekMondays[0])
            assertEquals(1, requests.count { it.second == "/jsxsd/xk/LoginToXk" })
        } finally { source.close() }
    }

    @Test fun loginResponseMayRetainItsFormWhileAFreshStrictIdentityProvesTheSession() = runTest {
        var profileGets = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/jsxsd/" -> respond(ScheduleFixtures.login)
                "/jsxsd/xk/LoginToXk" -> respond(ScheduleFixtures.login, headers = headersOf(HttpHeaders.SetCookie,
                    "JSESSIONID=fixture-template-session; Path=/jsxsd/; Secure; HttpOnly"))
                "/jsxsd/framework/xsMain.jsp" -> {
                    profileGets++
                    assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("JSESSIONID=fixture-template-session"))
                    respond(ScheduleFixtures.identity)
                }
                else -> error("Unexpected login-template fixture path")
            }
        }))
        try {
            assertTrue(academicLoginShell(ScheduleFixtures.login))
            assertEquals(ScheduleFixtures.account, source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password).account)
            assertEquals(1, profileGets)
        } finally { source.close() }
    }

    @Test fun incorrectPasswordStillFailsWhenTheFreshMainPageIsALoginForm() = runTest {
        var loginPosts = 0
        var profileGets = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/jsxsd/xk/LoginToXk") loginPosts++
            if (request.url.encodedPath == "/jsxsd/framework/xsMain.jsp") profileGets++
            respond(ScheduleFixtures.login)
        }))
        try {
            assertFailsWith<AcademicAuthenticationException> { source.authenticate(ScheduleFixtures.account, "fixture-incorrect-password") }
            assertEquals(1, loginPosts)
            assertEquals(1, profileGets)
            assertFailsWith<AcademicSessionExpiredException> { source.terms() }
        } finally { source.close() }
    }

    @Test fun loginRootPostRedirectAndFinalProfileAllUseRootRefererWithoutChangingUaOrAccept() = runTest {
        val paths = mutableListOf<String>()
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            assertEquals(ACADEMIC_ORIGIN + ACADEMIC_BASE_PATH, request.headers["Referer"])
            assertEquals("better-zslf-academic", request.headers[HttpHeaders.UserAgent])
            assertEquals("text/html,application/xhtml+xml", request.headers[HttpHeaders.Accept])
            when (path) {
                "/jsxsd/" -> respond(ScheduleFixtures.login)
                "/jsxsd/xk/LoginToXk" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/jsxsd/framework/xsMain.jsp"))
                "/jsxsd/framework/xsMain.jsp" -> { assertEquals(HttpMethod.Get, request.method); respond(ScheduleFixtures.identity) }
                else -> error("Unexpected Referer fixture path")
            }
        }))
        try {
            source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password)
            assertEquals(listOf("/jsxsd/", "/jsxsd/xk/LoginToXk", "/jsxsd/framework/xsMain.jsp", "/jsxsd/framework/xsMain.jsp"), paths)
        } finally { source.close() }
    }

    private fun sessionValues(header: String?): List<String> = header.orEmpty().split(';').mapNotNull { part ->
        val item = part.trim()
        if (item.substringBefore('=') == "JSESSIONID") item.substringAfter('=', "").removeSurrounding("\"") else null
    }

    @Test fun postPathlessSessionCannotReplaceTheRootScopedSessionNeededByMain() = runTest {
        var mainGets = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/jsxsd/" -> respond(ScheduleFixtures.login, headers = headersOf(HttpHeaders.SetCookie,
                    "JSESSIONID=fixture-broad-root; Path=/jsxsd/; Secure; HttpOnly"))
                "/jsxsd/xk/LoginToXk" -> {
                    assertEquals(listOf("fixture-broad-root"), sessionValues(request.headers[HttpHeaders.Cookie]))
                    val headers = HeadersBuilder().apply {
                        append(HttpHeaders.Location, "/jsxsd/framework/xsMain.jsp")
                        append(HttpHeaders.SetCookie, "JSESSIONID=fixture-post-narrow; Secure; HttpOnly")
                    }.build()
                    respond("", HttpStatusCode.Found, headers)
                }
                "/jsxsd/framework/xsMain.jsp" -> {
                    mainGets++
                    assertEquals(listOf("fixture-broad-root"), sessionValues(request.headers[HttpHeaders.Cookie]))
                    respond(ScheduleFixtures.identity)
                }
                else -> error("Unexpected scoped-session fixture path")
            }
        }))
        try {
            assertEquals(ScheduleFixtures.account, source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password).account)
            assertEquals(2, mainGets)
        } finally { source.close() }
    }

    @Test fun defaultSessionPathIsTheRequestDirectoryAndLongerMatchingScopesAreSentFirst() = runTest {
        var siblingGets = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/jsxsd/" -> respond(ScheduleFixtures.login, headers = headersOf(HttpHeaders.SetCookie,
                    "JSESSIONID=fixture-broad-root; Path=/jsxsd/; Secure; HttpOnly"))
                "/jsxsd/xk/LoginToXk" -> respond("", HttpStatusCode.Found, HeadersBuilder().apply {
                    append(HttpHeaders.Location, "/jsxsd/xk/fixture-step.jsp")
                    append(HttpHeaders.SetCookie, "JSESSIONID=fixture-post-narrow; Secure; HttpOnly")
                }.build())
                "/jsxsd/xk/fixture-step.jsp" -> {
                    siblingGets++
                    assertEquals(listOf("fixture-post-narrow", "fixture-broad-root"), sessionValues(request.headers[HttpHeaders.Cookie]))
                    respond("<p>Fixture intermediate page</p>")
                }
                "/jsxsd/framework/xsMain.jsp" -> {
                    assertEquals(listOf("fixture-broad-root"), sessionValues(request.headers[HttpHeaders.Cookie]))
                    respond(ScheduleFixtures.identity)
                }
                else -> error("Unexpected default-path fixture path")
            }
        }))
        try {
            source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password)
            assertEquals(1, siblingGets)
        } finally { source.close() }
    }

    @Test fun scopedCookieJarHonorsSecureHostOnlyDomainAndForeignDomainRejection() = runTest {
        val jar = AcademicCookies()
        val origin = Url("https://jwgln.zsc.edu.cn/jsxsd/")
        val main = Url("https://jwgln.zsc.edu.cn/jsxsd/framework/xsMain.jsp")
        try {
            jar.addCookie(origin, Cookie("secureHost", "fixture-secure", path = "/jsxsd/", secure = true))
            jar.addCookie(origin, Cookie("plainHost", "fixture-plain", path = "/jsxsd/"))
            jar.addCookie(origin, Cookie("parentDomain", "fixture-parent", domain = ".ZSC.EDU.CN", path = "/jsxsd/", secure = true))
            jar.addCookie(origin, Cookie("foreignDomain", "fixture-foreign", domain = "other.invalid", path = "/jsxsd/"))
            assertEquals(setOf("secureHost", "plainHost", "parentDomain"), jar.get(main).map { it.name }.toSet())
            assertEquals(listOf("plainHost"), jar.get(Url("http://jwgln.zsc.edu.cn/jsxsd/framework/xsMain.jsp")).map { it.name })
            assertEquals(listOf("parentDomain"), jar.get(Url("https://sub.jwgln.zsc.edu.cn/jsxsd/framework/xsMain.jsp")).map { it.name })
            assertTrue(jar.get(Url("https://other.invalid/jsxsd/framework/xsMain.jsp")).isEmpty())
            assertTrue(jar.get(Url("https://evilzsc.edu.cn/jsxsd/framework/xsMain.jsp")).isEmpty())
        } finally { jar.close() }
    }

    @Test fun expiringOneSessionScopeDoesNotDeleteAnotherAndNormalizedDomainsShareAnIdentity() = runTest {
        val jar = AcademicCookies()
        val root = Url("https://jwgln.zsc.edu.cn/jsxsd/")
        val login = Url("https://jwgln.zsc.edu.cn/jsxsd/xk/LoginToXk")
        try {
            jar.addCookie(root, Cookie("JSESSIONID", "fixture-root", path = "/jsxsd/", secure = true))
            jar.addCookie(login, Cookie("JSESSIONID", "fixture-narrow", path = "/jsxsd/xk", secure = true))
            assertEquals(listOf("fixture-narrow", "fixture-root"), jar.get(login).map { it.value })
            jar.addCookie(login, Cookie("JSESSIONID", "fixture-expired", path = "/jsxsd/xk", secure = true, expires = GMTDate(0)))
            assertEquals(listOf("fixture-root"), jar.get(login).map { it.value })
            jar.addCookie(root, Cookie("domainIdentity", "fixture-first", domain = ".ZSC.EDU.CN", path = "/jsxsd/"))
            jar.addCookie(root, Cookie("domainIdentity", "fixture-second", domain = "zsc.edu.cn", path = "/jsxsd/"))
            assertEquals("fixture-second", jar.get(root).single { it.name == "domainIdentity" }.value)
        } finally { jar.close() }
    }

    @Test fun jarHasSixtyFourScopeBoundAndResetClearsOldScopesWithoutClosingFreshAuthentication() = runTest {
        val jar = AcademicCookies()
        val root = Url("https://jwgln.zsc.edu.cn/jsxsd/")
        try {
            repeat(80) { jar.addCookie(root, Cookie("fixtureCookie$it", "fixture-value", path = "/jsxsd/")) }
            assertEquals(64, jar.get(root).size)
            jar.reset()
            assertTrue(jar.get(root).isEmpty())
            jar.addCookie(root, Cookie("fresh", "fixture-new-generation", path = "/jsxsd/"))
            assertEquals(listOf("fresh"), jar.get(root).map { it.name })
        } finally { jar.close() }
    }

    @Test fun queuedLateAddsAndResetCannotReviveAClosedJar() = runTest {
        val jar = AcademicCookies()
        val root = Url("https://jwgln.zsc.edu.cn/jsxsd/")
        jar.addCookie(root, Cookie("beforeClose", "fixture-before", path = "/jsxsd/"))
        val late = List(8) { index -> launch { jar.addCookie(root, Cookie("late$index", "fixture-late", path = "/jsxsd/")) } }
        jar.close()
        late.forEach { it.join() }
        jar.reset()
        jar.addCookie(root, Cookie("afterReset", "fixture-after", path = "/jsxsd/"))
        assertTrue(jar.get(root).isEmpty())
        jar.close()
    }

    @Test fun invalidForeignAndExpiredCookieBurstsCannotEvictAHealthyBroadSession() = runTest {
        val jar = AcademicCookies()
        val root = Url("https://jwgln.zsc.edu.cn/jsxsd/")
        try {
            jar.addCookie(root, Cookie("JSESSIONID", "fixture-healthy", path = "/jsxsd/", secure = true))
            repeat(80) { index ->
                jar.addCookie(root, Cookie("foreign$index", "fixture-value", domain = "other.invalid", path = "/jsxsd/"))
                jar.addCookie(root, Cookie("expired$index", "fixture-value", path = "/jsxsd/", expires = GMTDate(0)))
            }
            assertEquals(listOf("fixture-healthy"), jar.get(root).map { it.value })
        } finally { jar.close() }
    }

    @Test fun httpSuccessAndARandomAccountMentionAreNeverAuthenticationSuccess() = runTest {
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/jsxsd/" -> respond(ScheduleFixtures.login)
                "/jsxsd/xk/LoginToXk" -> respond("<html>200 does not prove identity</html>")
                else -> respond("<p>Unrelated reference FIXTURE001</p>")
            }
        }))
        try { assertFailsWith<AcademicAuthenticationException> { source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) } }
        finally { source.close() }
    }

    @Test fun invalidPasswordLoginShellReturnsAuthenticationExceptionWithoutSavingCredentials() = runTest {
        var posts = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) posts++
            respond(ScheduleFixtures.login)
        }))
        try {
            val failure = assertFailsWith<AcademicAuthenticationException> { source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) }
            assertFalse(failure.toString().contains(ScheduleFixtures.password))
            assertEquals(1, posts)
            assertFailsWith<AcademicSessionExpiredException> { source.terms() }
        } finally { source.close() }
    }

    @Test fun expiredReadIsReportedAndNeverAutomaticallyReauthenticates() = runTest {
        var loginPosts = 0
        var requests = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            requests++
            when (request.url.encodedPath) {
                "/jsxsd/" -> respond(ScheduleFixtures.login)
                "/jsxsd/xk/LoginToXk" -> { loginPosts++; respond(ScheduleFixtures.identity) }
                "/jsxsd/framework/xsMain.jsp" -> respond(ScheduleFixtures.identity)
                else -> respond("fixture stale session", HttpStatusCode.Unauthorized)
            }
        }))
        try {
            source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password)
            assertFailsWith<AcademicSessionExpiredException> { source.terms() }
            val before = requests
            assertFailsWith<AcademicSessionExpiredException> { source.terms() }
            assertEquals(before, requests)
            assertEquals(1, loginPosts)
        } finally { source.close() }
    }

    @Test fun aReadLoginShellAlsoExpiresTheSessionWithoutAnotherLoginPost() = runTest {
        var loginPosts = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/jsxsd/" -> respond(ScheduleFixtures.login)
                "/jsxsd/xk/LoginToXk" -> { loginPosts++; respond(ScheduleFixtures.identity) }
                "/jsxsd/framework/xsMain.jsp" -> respond(ScheduleFixtures.identity)
                else -> respond(ScheduleFixtures.login)
            }
        }))
        try {
            source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password)
            assertFailsWith<AcademicSessionExpiredException> { source.terms() }
            assertEquals(1, loginPosts)
        } finally { source.close() }
    }

    @Test fun crossOriginAndNonHttpsRedirectsAreBlockedBeforeASecondRequest() = runTest {
        for (location in listOf("https://other.invalid/jsxsd/", "http://jwgln.zsc.edu.cn/jsxsd/", "https://jwgln.zsc.edu.cn.evil.invalid/jsxsd/", "https://fixture:secret@jwgln.zsc.edu.cn/jsxsd/", "/outside", "/jsxsd/%2e%2e/outside")) {
            var count = 0
            val source = createAcademicSource(HttpClient(MockEngine { count++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location)) }))
            try {
                assertFailsWith<AcademicProtocolException> { source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) }
                assertEquals(1, count)
            } finally { source.close() }
        }
    }

    @Test fun temporaryPostRedirectNeverReplaysThePasswordForm() = runTest {
        var requests = 0
        val source = createAcademicSource(HttpClient(MockEngine { request ->
            requests++
            if (request.method == HttpMethod.Get) respond(ScheduleFixtures.login)
            else respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "/jsxsd/framework/xsMain.jsp"))
        }))
        try {
            assertFailsWith<AcademicProtocolException> { source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) }
            assertEquals(2, requests)
        } finally { source.close() }
    }

    @Test fun networkErrorsCannotExposeEngineSecretsOrCredentialValues() = runTest {
        val source = createAcademicSource(HttpClient(MockEngine { throw IllegalStateException("FIXTURE_ENGINE_SECRET") }))
        try {
            val error = assertFailsWith<AcademicNetworkException> { source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) }
            assertFalse(error.toString().contains("FIXTURE_ENGINE_SECRET"))
            assertFalse(error.toString().contains(ScheduleFixtures.password))
            assertNull(error.cause)
        } finally { source.close() }
    }

    private fun sourceWithCalendar(calendarHtml: String, status: HttpStatusCode = HttpStatusCode.OK): AcademicSource =
        createAcademicSource(HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/jsxsd/" -> respond(ScheduleFixtures.login)
                "/jsxsd/xk/LoginToXk", "/jsxsd/framework/xsMain.jsp" -> respond(ScheduleFixtures.identity)
                "/jsxsd/xskb/xskb_list.do" -> respond(ScheduleFixtures.schedule())
                "/jsxsd/jxzl/jxzl_query" -> respond(calendarHtml, status)
                else -> error("Unexpected calendar fixture path")
            }
        }))

    @Test fun calendarNetworkOrProtocolFailureKeepsTheValidWeekScheduleWithoutInventedDates() = runTest {
        for ((html, status) in listOf("fixture calendar failure" to HttpStatusCode.ServiceUnavailable, "" to HttpStatusCode.OK)) {
            val source = sourceWithCalendar(html, status)
            try {
                source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password)
                val snapshot = source.fetch(ScheduleFixtures.term)
                assertTrue(snapshot.complete)
                assertEquals(3, snapshot.courses.size)
                assertTrue(snapshot.weekMondays.isEmpty())
                assertTrue(snapshot.warnings.contains("CALENDAR_UNAVAILABLE"))
                assertTrue(scheduleOccurrences(snapshot, defaultSchedulePeriods(), 0, Long.MAX_VALUE).isEmpty())
            } finally { source.close() }
        }
    }

    @Test fun calendarSessionExpiryAndLoginShellAreNotSwallowedByTheCalendarDowngrade() = runTest {
        for ((html, status) in listOf("fixture expired" to HttpStatusCode.Unauthorized, ScheduleFixtures.login to HttpStatusCode.OK)) {
            val source = sourceWithCalendar(html, status)
            try {
                source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password)
                assertFailsWith<AcademicSessionExpiredException> { source.fetch(ScheduleFixtures.term) }
                assertFailsWith<AcademicSessionExpiredException> { source.terms() }
            } finally { source.close() }
        }
    }

    @Test fun aDifferentOrUnverifiedCalendarTermNeverSuppliesDatesForTheRequestedSchedule() = runTest {
        for (html in listOf(ScheduleFixtures.calendar(selected = "2025-2026-2"), "<table><tr><td>0</td><td title='2026年08月31日'></td></tr></table>")) {
            val source = sourceWithCalendar(html)
            try {
                source.authenticate(ScheduleFixtures.account, ScheduleFixtures.password)
                val snapshot = source.fetch(ScheduleFixtures.term)
                assertEquals(ScheduleFixtures.selectedId, snapshot.term.id)
                assertEquals(3, snapshot.courses.size)
                assertTrue(snapshot.weekMondays.isEmpty())
                assertTrue(snapshot.warnings.contains("CALENDAR_TERM_UNVERIFIED"))
            } finally { source.close() }
        }
    }

    @Test fun oversizedResponseIsRejectedBeforeParsingAndRedirectCyclesAreBounded() = runTest {
        val tooLarge = createAcademicSource(HttpClient(MockEngine {
            respond("x", headers = headersOf(HttpHeaders.ContentLength, "2097153"))
        }))
        try { assertFailsWith<AcademicProtocolException> { tooLarge.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) } }
        finally { tooLarge.close() }
        var count = 0
        val cycle = createAcademicSource(HttpClient(MockEngine { count++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/jsxsd/")) }))
        try {
            assertFailsWith<AcademicProtocolException> { cycle.authenticate(ScheduleFixtures.account, ScheduleFixtures.password) }
            assertEquals(1, count)
        } finally { cycle.close() }
    }
}
