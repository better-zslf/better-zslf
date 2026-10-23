package io.github.betterzslf.edition

import kotlin.test.*

/** Checks actual host compiler output, not just source text or hidden UI buttons. */
class EditionCompilationTest {
    private fun present(name: String): Boolean = try {
        Class.forName("io.github.betterzslf.$name", false, javaClass.classLoader)
        true
    } catch (_: ClassNotFoundException) { false }

    @Test fun proImplementationsAreActuallyAbsentFromStandardClasspath() {
        for (name in listOf(
            "feature.water.WaterBleController",
            "feature.waterui.WaterHeaterPresenter",
            "ui.water.WaterHeaterScreenKt",
            "platform.BluetoothAccessState",
            "feature.run.RunRouteParser",
            "feature.run.RunRouteValidator",
            "feature.run.RunRouteNormalizer",
            "feature.run.RunDetourPlanner",
            "feature.run.TrackGenerationOptions",
            "feature.run.RunTrackPreviewKt",
            "feature.runui.RunImportController",
            "feature.runui.RunManualDraft",
            "ui.run.RunImportPageKt",
            "ui.run.RunManualDraftSession",
            "ui.run.RunManualDraftSheetKt",
            "edition.ProEditionFeatures",
        )) assertEquals(BuildEdition.isPro, present(name), name)
    }

    @Test fun realRecordingServerTrackAndGeometryRemainInBothClasspaths() {
        for (name in listOf(
            "feature.runui.RunRuntime", "feature.run.RunSessionCoordinator",
            "feature.run.RunGeo", "feature.run.RunLocationFilter", "feature.run.RunCheckpointProgress",
            "feature.run.RunTrackGenerator", "feature.run.RunTrackValidator",
            "feature.run.RunUploadQueue", "feature.runui.RunDataDecoder", "feature.runui.RunDisplay",
            "platform.RunMapWeb", "platform.RunMapCoordinates", "ui.run.RunTrackerPageKt",
        )) assertTrue(present(name), name)
    }
}
