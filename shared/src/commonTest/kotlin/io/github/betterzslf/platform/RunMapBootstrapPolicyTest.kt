package io.github.betterzslf.platform

import io.github.betterzslf.feature.run.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

/** Pure policy/publication models only: no WebView, SDK network, ADB, GPS or school writes. */
class RunMapBootstrapPolicyTest {
    private val context = RunMapBootstrapContext("owned_view_1", 1, RunMapProvider.AMAP)
    private val documentId = "owned_document_1"
    private val key = "public_fixture_key"
    private fun model() = RunMapModel(provider = RunMapProvider.AMAP, apiKey = key)
    private fun document() = RunMapBootstrapDocument(context.ownerId, context.epoch, context.provider, documentId,
        "bootstrap_ticket_1", assertNotNull(RunMapWeb.document(model(), documentId).html))
    private fun request() = RunMapBootstrapRequest(RunMapWeb.baseUrl, "GET", true, "bootstrap_ticket_1")
    private fun consumed() = assertNotNull(RunMapBootstrapPolicy.consume(document(), context, request()))

    @Test fun controlledFirstGetReturnsTheExactOwnedHtmlAndConsumesOnlyPendingAuthority() {
        val before = document()
        assertTrue(RunMapBootstrapPolicy.allowsInitial(before, context, request()))
        val after = assertNotNull(RunMapBootstrapPolicy.consume(before, context, request()))
        assertEquals(before.copy(pending = false), after)
        assertEquals(before.html, after.html)
        assertTrue(before.pending); assertFalse(after.pending)
        assertFalse(after.toString().contains(key)); assertFalse(after.toString().contains(before.html))
    }

    @Test fun allFollowingMainFramesIncludingTheSameBaseAndTicketAreBlocked() {
        val after = consumed()
        for (url in listOf(RunMapWeb.baseUrl, "about:blank", "https://webapi.amap.com/maps", "https://evil.invalid/")) {
            assertNull(RunMapBootstrapPolicy.consume(after, context, request().copy(url = url)))
            assertFalse(RunMapBootstrapPolicy.allowsSubresource(after, context, request().copy(url = url)))
        }
    }

    @Test fun baseMatchIsExactWithoutPortsQueriesFragmentsCredentialsOrOtherPaths() {
        val before = document()
        for (url in listOf("http://map.better-zslf.invalid/", "https://map.better-zslf.invalid", "https://map.better-zslf.invalid:443/",
            "https://map.better-zslf.invalid/next", RunMapWeb.baseUrl + "?next=1", RunMapWeb.baseUrl + "#fragment",
            "https://user@map.better-zslf.invalid/", "https://map.better-zslf.invalid.evil.invalid/", "about:blank", "data:text/html,fixture")) {
            assertNull(RunMapBootstrapPolicy.consume(before, context, request().copy(url = url)))
        }
    }

    @Test fun postRedirectGestureMissingTicketAndNonMainFramesNeverConsumeBootstrap() {
        val before = document()
        val rejected = listOf(request().copy(method = "POST"), request().copy(method = "HEAD"), request().copy(method = "get"),
            request().copy(isRedirect = true), request().copy(hasGesture = true), request().copy(ticket = null),
            request().copy(ticket = "bootstrap_ticket_old"), request().copy(ticket = "bootstrap_ticket_1 "), request().copy(isMainFrame = false))
        for (input in rejected) assertNull(RunMapBootstrapPolicy.consume(before, context, input))
        assertTrue(before.pending)
        assertTrue(RunMapBootstrapPolicy.allowsInitial(before, context, request()))
    }

    @Test fun wrongViewEpochAndProviderFailBeforeAnyHtmlCanBeServed() {
        val before = document()
        for (other in listOf(context.copy(ownerId = "owned_view_other"), context.copy(epoch = 2),
            context.copy(provider = RunMapProvider.TIAN_DI_TU), context.copy(provider = null))) {
            assertFalse(RunMapBootstrapPolicy.isCurrent(before, other))
            assertNull(RunMapBootstrapPolicy.consume(before, other, request()))
            assertFalse(RunMapBootstrapPolicy.allowsSubresource(before.copy(pending = false), other,
                request().copy(isMainFrame = false, url = "https://webapi.amap.com/maps")))
        }
    }

    @Test fun sameBaseStaleRequestCannotStealTheNewEpochEvenOnTheSameViewAndProvider() {
        val old = document()
        val next = old.copy(epoch = 2, documentId = "owned_document_2", ticket = "bootstrap_ticket_2",
            html = assertNotNull(RunMapWeb.document(model(), "owned_document_2").html))
        val nextContext = context.copy(epoch = 2)
        assertNull(RunMapBootstrapPolicy.consume(next, nextContext, request()))
        assertNull(RunMapBootstrapPolicy.consume(next, context, request().copy(ticket = next.ticket)))
        assertNotNull(RunMapBootstrapPolicy.consume(next, nextContext, request().copy(ticket = next.ticket)))
        assertFalse(RunMapBootstrapPolicy.isCurrent(next, context))
    }

    @Test fun unconfiguredEmptyDocumentUsesTheSameSingleUseGateAndPermitsNoResources() {
        val empty = document().copy(provider = null, documentId = null, html = RunMapBootstrapPolicy.emptyDocument)
        val emptyContext = context.copy(provider = null)
        val after = assertNotNull(RunMapBootstrapPolicy.consume(empty, emptyContext, request()))
        assertEquals(RunMapBootstrapPolicy.emptyDocument, after.html)
        assertTrue(after.html.contains("default-src 'none'; script-src 'none'"))
        assertFalse(after.html.contains("<script")); assertFalse(after.html.contains(key))
        assertFalse(RunMapBootstrapPolicy.allowsSubresource(after, emptyContext,
            request().copy(isMainFrame = false, url = "https://webapi.amap.com/maps")))
        assertNull(RunMapBootstrapPolicy.consume(after, emptyContext, request()))
        assertFalse(RunMapBootstrapPolicy.isCurrent(empty.copy(html = "<script>not-owned-empty</script>"), emptyContext))
    }

    @Test fun publicationCasAllowsOnlyOneOfTwoClaimsFromTheSamePendingSnapshot() {
        val before = document()
        val slot = MutableStateFlow<RunMapBootstrapDocument?>(before)
        val first = assertNotNull(RunMapBootstrapPolicy.consume(before, context, request()))
        val second = assertNotNull(RunMapBootstrapPolicy.consume(before, context, request()))
        assertTrue(slot.compareAndSet(before, first))
        assertFalse(slot.compareAndSet(before, second))
        assertFalse(assertNotNull(slot.value).pending)
    }

    @Test fun releaseOrReplacementBeforePublicationDiscardsAnAlreadyComputedClaim() {
        val before = document()
        val slot = MutableStateFlow<RunMapBootstrapDocument?>(before)
        val claim = assertNotNull(RunMapBootstrapPolicy.consume(before, context, request()))
        slot.value = null
        assertFalse(slot.compareAndSet(before, claim)); assertNull(slot.value)
        val next = before.copy(epoch = 2, documentId = "owned_document_2", ticket = "bootstrap_ticket_2")
        slot.value = next
        assertFalse(slot.compareAndSet(before, claim)); assertSame(next, slot.value)
    }

    @Test fun providerResourcesStayGetOnlyAllowlistedAndNeverFetchTheFakeBase() {
        val before = document()
        val resource = request().copy(isMainFrame = false, ticket = null, url = "https://webapi.amap.com/maps?v=1.4.15")
        assertFalse(RunMapBootstrapPolicy.allowsSubresource(before, context, resource))
        val after = consumed()
        assertTrue(RunMapBootstrapPolicy.allowsSubresource(after, context, resource))
        for (input in listOf(resource.copy(url = RunMapWeb.baseUrl), resource.copy(url = "https://api.tianditu.gov.cn/api"),
            resource.copy(url = "https://amap.com.evil.invalid/"), resource.copy(url = "file:///fixture"),
            resource.copy(method = "POST"), resource.copy(isRedirect = true), resource.copy(isMainFrame = true))) {
            assertFalse(RunMapBootstrapPolicy.allowsSubresource(after, context, input))
        }
        val tdt = after.copy(provider = RunMapProvider.TIAN_DI_TU,
            html = assertNotNull(RunMapWeb.document(model().copy(provider = RunMapProvider.TIAN_DI_TU), documentId).html))
        assertTrue(RunMapBootstrapPolicy.allowsSubresource(tdt, context.copy(provider = RunMapProvider.TIAN_DI_TU), resource.copy(url = "https://api.tianditu.gov.cn/api?v=4.0")))
        assertFalse(RunMapBootstrapPolicy.allowsSubresource(tdt, context.copy(provider = RunMapProvider.TIAN_DI_TU), resource))
    }

    @Test fun invalidAuthorityAndRequestsAreRedactedAndNotGranted() {
        val before = document()
        for (invalid in listOf(before.copy(ownerId = ""), before.copy(epoch = 0), before.copy(epoch = -1),
            before.copy(ticket = "bad ticket"), before.copy(documentId = "bad document"), before.copy(provider = null),
            before.copy(html = ""), before.copy(documentId = null))) {
            assertFalse(RunMapBootstrapPolicy.isCurrent(invalid, context))
            assertNull(RunMapBootstrapPolicy.consume(invalid, context, request()))
        }
        assertFalse(request().copy(url = "https://fixture.invalid/private-value").toString().contains("private-value"))
    }

    @Test fun bootstrapPreservesReadonlyMapCspPermissionGuardsAndNonceBoundUiEvents() {
        val point = GeoPoint(116.397, 39.916)
        val readonly = model().copy(importedPath = RunMapPath(List(6) { point }, RunMapCoordinateSystem.WGS84),
            checkpoints = listOf(RunMapCheckpoint("checkpoint_fixture", point, coordinateSystem = RunMapCoordinateSystem.WGS84)), editMode = false)
        val html = assertNotNull(RunMapWeb.document(readonly, documentId).html)
        val after = assertNotNull(RunMapBootstrapPolicy.consume(document().copy(html = html), context, request()))
        assertEquals(html, after.html)
        for (guard in listOf("default-src 'none'", "base-uri 'none'", "frame-src 'none'", "form-action 'none'", "media-src 'none'",
            "nonce-$documentId", "getCurrentPosition:denyCallback", "watchPosition:denyCallback", "getDisplayMedia", "Object.defineProperty")) assertTrue(html.contains(guard))
        val draw = """{"documentId":"$documentId","type":"draw","longitude":116.397,"latitude":39.916,"frame":"GCJ02"}"""
        assertNull(RunMapWeb.parseEvent(draw, readonly, documentId))
        assertEquals(RunMapEvent.Ready, RunMapWeb.parseEvent("""{"documentId":"$documentId","type":"ready"}""", readonly, documentId))
        assertIs<RunMapEvent.CheckpointSelected>(RunMapWeb.parseEvent("""{"documentId":"$documentId","type":"checkpoint","id":"checkpoint_fixture"}""", readonly, documentId))
        assertNull(RunMapWeb.parseEvent("""{"documentId":"old_document","type":"ready"}""", readonly, documentId))
        assertNull(RunMapWeb.parseEvent("x".repeat(4097), readonly, documentId))
    }
}
