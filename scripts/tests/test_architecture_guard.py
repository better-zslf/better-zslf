"""V2 首批架构边界的离线 stdlib fixtures；不执行 Gradle/Kotlin/网络。"""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import architecture_guard as guard


# 独立fixture，不从实现常量导出期望模块/迁移文件。
MODULES = {
    ":core:text": "core/text",
    ":core:run-model": "core/run-model",
    ":core:logging": "core/logging",
    ":core:network-session": "core/network-session",
    ":design-system": "design-system",
    ":feature:run:api": "feature/run/api",
    ":feature:run:domain": "feature/run/domain",
    ":feature:run:data": "feature/run/data",
    ":feature:run:presentation": "feature/run/presentation",
    ":feature:run:ui": "feature/run/ui",
    ":feature:run:runtime": "feature/run/runtime",
    ":feature:services:presentation": "feature/services/presentation",
    ":feature:services:ui": "feature/services/ui",
    ":platform-ui:readonly-map": "platform-ui/readonly-map",
    ":feature:schedule:api": "feature/schedule/api",
    ":feature:schedule:domain": "feature/schedule/domain",
    ":feature:schedule:data": "feature/schedule/data",
    ":feature:home:presentation": "feature/home/presentation",
    ":feature:home:ui": "feature/home/ui",
    ":feature:update:domain": "feature/update/domain",
    ":feature:update:data": "feature/update/data",
    ":feature:update:presentation": "feature/update/presentation",
    ":feature:update:ui": "feature/update/ui",
    ":feature:accountrecovery:domain": "feature/accountrecovery/domain",
    ":feature:accountrecovery:data": "feature/accountrecovery/data",
    ":feature:accountrecovery:presentation": "feature/accountrecovery/presentation",
    ":feature:accountrecovery:ui": "feature/accountrecovery/ui",
    ":feature:campusnet:domain": "feature/campusnet/domain",
    ":feature:campusnet:data": "feature/campusnet/data",
    ":feature:campusnet:presentation": "feature/campusnet/presentation",
    ":feature:campusnet:ui": "feature/campusnet/ui",
    ":feature:auth:domain": "feature/auth/domain",
    ":feature:auth:data": "feature/auth/data",
    ":feature:auth:presentation": "feature/auth/presentation",
    ":feature:auth:ui": "feature/auth/ui",
    ":feature:profile:domain": "feature/profile/domain",
    ":feature:profile:data": "feature/profile/data",
    ":feature:profile:presentation": "feature/profile/presentation",
    ":feature:content:domain": "feature/content/domain",
    ":feature:content:data": "feature/content/data",
    ":feature:content:presentation": "feature/content/presentation",
    ":platform:updates": "platform/updates",
    ":platform:schedule-native": "platform/schedule-native",
    ":feature:schedule:runtime": "feature/schedule/runtime",
    ":feature:schedule:ui": "feature/schedule/ui",
    ":core:storage": "core/storage",
    ":platform:storage": "platform/storage",
    ":platform:location": "platform/location",
}
ROOT = "/src/commonMain/kotlin/io/github/betterzslf/"


class ArchitectureGuardTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="architecture-guard-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for directory in (*MODULES.values(), "shared"):
            (self.root / directory / "src/commonMain/kotlin").mkdir(parents=True)
        self.write("settings.gradle.kts", 'include(":core:text", ":core:run-model", ":core:logging", ":core:network-session", ":design-system", ":feature:run:api", ":feature:services:presentation", ":feature:services:ui", ":platform-ui:readonly-map", ":feature:schedule:api", ":feature:schedule:domain", ":feature:schedule:data", ":feature:home:presentation", ":feature:home:ui", ":feature:update:domain", ":feature:update:data", ":feature:update:presentation", ":feature:update:ui", ":feature:accountrecovery:domain", ":feature:accountrecovery:data", ":feature:accountrecovery:presentation", ":feature:accountrecovery:ui", ":feature:campusnet:domain", ":feature:campusnet:data", ":feature:campusnet:presentation", ":feature:campusnet:ui", ":feature:auth:domain", ":feature:auth:data", ":feature:auth:presentation", ":feature:auth:ui", ":feature:profile:domain", ":feature:profile:data", ":feature:profile:presentation", ":feature:content:domain", ":feature:content:data", ":feature:content:presentation", ":platform:updates", ":platform:schedule-native", ":feature:schedule:runtime", ":core:storage", ":platform:storage", ":platform:location", ":feature:run:domain", ":feature:run:data", ":feature:run:presentation", ":feature:run:ui", ":feature:run:runtime", ":feature:schedule:ui", ":shared")\n')
        self.write("core/text/build.gradle.kts", 'plugins { id("betterzslf.kmp-library") }\n')
        self.write("core/run-model/build.gradle.kts", self.build('implementation(project(":core:text"))\napi(libs.serialization.json)'))
        self.write("core/logging/build.gradle.kts", self.build('api(libs.coroutines.core)'))
        self.write("core/network-session/build.gradle.kts", self.build('implementation(project(":core:text"))\nimplementation(project(":core:logging"))\napi(project(":core:storage"))\napi(libs.ktor.core)\napi(libs.serialization.json)'))
        self.write("core/storage/build.gradle.kts", 'plugins { id("betterzslf.kmp-library") }\n')
        self.write("platform/storage/build.gradle.kts", self.build('api(project(":core:storage"))') + 'kotlin { sourceSets { androidMain.dependencies { implementation(project(":core:text")) } } }\n')
        self.write("platform/schedule-native/build.gradle.kts", self.build('api(project(":feature:schedule:domain"))\napi(libs.coroutines.core)\nimplementation(project(":core:text"))\nimplementation(libs.serialization.json)') + 'kotlin { sourceSets { androidMain.dependencies { implementation(project(":platform:storage")) } } }\n')
        self.write("feature/schedule/runtime/build.gradle.kts", self.build('api(project(":feature:schedule:domain"))\napi(project(":platform:schedule-native"))\napi(project(":core:storage"))\napi(libs.coroutines.core)\nimplementation(project(":core:text"))\nimplementation(libs.serialization.json)'))
        self.write("design-system/build.gradle.kts", self.build('implementation(project(":core:text"))\napi(libs.compose.runtime)'))
        self.write("feature/run/api/build.gradle.kts", self.build('api(project(":core:run-model"))\napi(libs.coroutines.core)'))
        self.write("feature/run/domain/build.gradle.kts", self.build('api(project(":core:run-model"))'))
        self.write("feature/run/data/build.gradle.kts", self.build('api(project(":feature:run:domain"))\napi(project(":core:network-session"))\nimplementation(project(":core:text"))'))
        self.write("feature/run/presentation/build.gradle.kts", self.build('api(project(":feature:run:domain"))\napi(libs.coroutines.core)\nimplementation(project(":core:text"))'))
        self.write("feature/run/ui/build.gradle.kts", self.build('api(project(":feature:run:presentation"))\nimplementation(project(":design-system"))\nimplementation(project(":core:text"))'))
        self.write("feature/run/runtime/build.gradle.kts", self.build('api(project(":core:run-model"))\napi(project(":platform:location"))\napi(project(":feature:run:data"))\nimplementation(project(":core:network-session"))\nimplementation(project(":core:storage"))\nimplementation(project(":core:text"))\nimplementation(libs.coroutines.core)\nimplementation(libs.serialization.json)'))
        self.write("platform/location/build.gradle.kts", self.build('api(project(":core:run-model"))\nimplementation(project(":core:text"))\nimplementation(libs.compose.runtime)\nimplementation(libs.coroutines.core)\n') + 'kotlin { sourceSets { androidMain.dependencies { implementation(libs.activity.compose)\nimplementation(libs.compose.ui) } } }\n' + 'kotlin { sourceSets { iosMain.dependencies { implementation(libs.compose.ui) } } }\n')
        self.write("feature/profile/domain/build.gradle.kts", self.build('implementation(project(":core:network-session"))\nimplementation(project(":core:text"))\nimplementation(libs.serialization.json)'))
        self.write("feature/profile/data/build.gradle.kts", self.build('api(project(":feature:profile:domain"))\nimplementation(project(":core:network-session"))\nimplementation(project(":core:text"))\nimplementation(libs.ktor.core)\nimplementation(libs.serialization.json)'))
        self.write("feature/profile/presentation/build.gradle.kts", self.build('api(project(":feature:profile:domain"))\nimplementation(project(":core:network-session"))\nimplementation(project(":core:text"))\nimplementation(libs.coroutines.core)'))
        self.write("feature/content/domain/build.gradle.kts", self.build('implementation(project(":core:network-session"))\nimplementation(project(":core:text"))\nimplementation(libs.serialization.json)'))
        self.write("feature/content/data/build.gradle.kts", self.build('api(project(":feature:content:domain"))\nimplementation(project(":core:network-session"))'))
        self.write("feature/content/presentation/build.gradle.kts", self.build('api(project(":feature:content:domain"))\nimplementation(project(":core:network-session"))\nimplementation(project(":core:text"))\nimplementation(libs.coroutines.core)\nimplementation(libs.serialization.json)'))
        self.write("feature/services/presentation/build.gradle.kts", 'plugins { id("betterzslf.kmp-library") }\n')
        self.write("feature/services/ui/build.gradle.kts", self.build('api(project(":feature:services:presentation"))\nimplementation(project(":design-system"))'))
        self.write("platform-ui/readonly-map/build.gradle.kts", self.build('api(project(":core:run-model"))\nimplementation(project(":core:text"))\nimplementation(libs.serialization.json)'))
        self.write("feature/schedule/api/build.gradle.kts", self.build('api(libs.serialization.json)'))
        self.write("feature/schedule/domain/build.gradle.kts", self.build('api(project(":feature:schedule:api"))\napi(libs.kotlinx.datetime)'))
        self.write("feature/schedule/data/build.gradle.kts", self.build('api(project(":feature:schedule:domain"))\napi(libs.ktor.core)'))
        self.write("feature/schedule/ui/build.gradle.kts", self.build('implementation(project(":design-system"))\nimplementation(project(":core:text"))\nimplementation(project(":feature:schedule:api"))\nimplementation(project(":feature:schedule:runtime"))\nimplementation(libs.kotlinx.datetime)\nimplementation(libs.compose.runtime)\nimplementation(libs.compose.foundation)\nimplementation(libs.compose.ui)\nimplementation(libs.miuix.ui)'))
        self.write("feature/home/presentation/build.gradle.kts", self.build('api(project(":feature:schedule:api"))\nimplementation(project(":feature:schedule:domain"))\nimplementation(project(":core:text"))'))
        self.write("feature/home/ui/build.gradle.kts", self.build('api(project(":feature:home:presentation"))\nimplementation(project(":design-system"))\nimplementation(project(":core:text"))'))
        self.write("feature/update/domain/build.gradle.kts", self.build('api(libs.coroutines.core)'))
        self.write("feature/update/data/build.gradle.kts", self.build('api(project(":feature:update:domain"))\napi(libs.ktor.core)'))
        self.write("feature/update/presentation/build.gradle.kts", self.build('api(project(":feature:update:domain"))\nimplementation(project(":core:text"))'))
        self.write("feature/update/ui/build.gradle.kts", self.build('api(project(":feature:update:presentation"))\nimplementation(project(":design-system"))\nimplementation(project(":core:text"))'))
        for feature in ("accountrecovery", "campusnet"):
            prefix = "feature/" + feature + "/"
            self.write(prefix + "domain/build.gradle.kts", self.build(''))
            self.write(prefix + "data/build.gradle.kts", self.build('api(project(":feature:' + feature + ':domain"))\napi(project(":core:network-session"))'))
            self.write(prefix + "presentation/build.gradle.kts", self.build('api(project(":feature:' + feature + ':domain"))\nimplementation(project(":core:text"))\napi(libs.coroutines.core)'))
            self.write(prefix + "ui/build.gradle.kts", self.build('api(project(":feature:' + feature + ':presentation"))\nimplementation(project(":design-system"))\nimplementation(project(":core:text"))'))
        self.write("platform/updates/build.gradle.kts", self.build('api(project(":feature:update:domain"))\nimplementation(project(":core:text"))\nimplementation(libs.coroutines.core)') + 'kotlin { sourceSets { androidMain.dependencies { implementation(libs.androidx.core) } } }\n')
        self.write("shared/build.gradle.kts", self.build('\n'.join('implementation(project("' + module + '"))' for module in MODULES)))
        self.write("core/text" + ROOT + "core/text/AppText.kt", "package io.github.betterzslf.core.text\nobject AppText { const val LABEL: String = \"正文\" }\n")
        self.write("core/run-model" + ROOT + "feature/run/RunGeometry.kt", "package io.github.betterzslf.feature.run\nimport io.github.betterzslf.core.text.AppText\ndata class GeoPoint(val longitude: Double, val latitude: Double)\nobject RunGeo { fun length(): Double = 0.0 }\n")
        self.write("core/run-model" + ROOT + "feature/run/RunTrack.kt", "package io.github.betterzslf.feature.run\ndata class RecordedTrack(val points: List<GeoPoint>)\ndata class RunLocationSample(val point: GeoPoint, val timestampMillis: Long, val accuracyMeters: Double)\n")
        self.write("core/run-model" + ROOT + "platform/RunMapCoordinates.kt", "package io.github.betterzslf.platform\nimport io.github.betterzslf.feature.run.GeoPoint\nobject RunMapCoordinates { fun convert(point: GeoPoint): GeoPoint = point }\n")
        self.write("design-system" + ROOT + "ui/DesignComponents.kt", "package io.github.betterzslf.ui\nimport androidx.compose.runtime.Composable\nimport io.github.betterzslf.core.text.AppText\n@Composable fun Card(text: String = AppText.LABEL) = Unit\n")
        self.write("design-system" + ROOT + "ui/ConfirmationCheckRow.kt", "package io.github.betterzslf.ui\nfun ConfirmationCheckRow(checked: Boolean, onChange: (Boolean) -> Unit) = Unit\n")
        self.write("feature/services/presentation" + ROOT + "feature/services/presentation/ServicesUiState.kt", "package io.github.betterzslf.feature.services.presentation\ndata class ServicesUiState(val title: String)\n")
        self.write("feature/services/ui" + ROOT + "feature/services/ui/ServicesContent.kt", "package io.github.betterzslf.feature.services.ui\nimport io.github.betterzslf.feature.services.presentation.*\nimport io.github.betterzslf.ui.Card\nfun ServicesContent(state: ServicesUiState) = Unit\n")
        self.write("shared" + ROOT + "feature/run/Upload.kt", "package io.github.betterzslf.feature.run\nclass LegacySharedUpload\n")
        self.write("shared" + ROOT + "data/Repository.kt", "package io.github.betterzslf.data\nclass SchoolRepository\n")
        self.write("shared" + ROOT + "App.kt", "package io.github.betterzslf\nclass AppGraph\n")
        self.write("platform-ui/readonly-map" + ROOT + "platform/RunMapPanel.kt", "package io.github.betterzslf.platform\nimport io.github.betterzslf.feature.run.GeoPoint\ndata class RunMapModel(val point: GeoPoint)\n")
        self.write("platform-ui/readonly-map" + ROOT + "platform/RunMapWeb.kt", "package io.github.betterzslf.platform\nobject RunMapWeb { fun document(model: RunMapModel): String = model.toString() }\n")
        self.write("platform-ui/readonly-map" + ROOT + "platform/RunMapBootstrapPolicy.kt", "package io.github.betterzslf.platform\ninternal object RunMapBootstrapPolicy\n")
        self.write("feature/schedule/api" + ROOT + "feature/schedule/ScheduleModels.kt", "package io.github.betterzslf.feature.schedule\nimport kotlinx.serialization.Serializable\n@Serializable data class ScheduleTerm(val id: String, val name: String, val isDefault: Boolean = false)\n@Serializable data class ScheduleCourse(val id: String, val title: String, val periods: List<Int>)\n@Serializable data class SchedulePeriod(val number: Int, val start: String, val end: String)\n@Serializable data class ScheduleSnapshot(val term: ScheduleTerm, val courses: List<ScheduleCourse>)\n")
        self.write("feature/schedule/domain" + ROOT + "feature/schedule/AcademicSource.kt", "package io.github.betterzslf.feature.schedule\ninterface AcademicSource { suspend fun read(): ScheduleSnapshot? }\n")
        self.write("feature/schedule/domain" + ROOT + "feature/schedule/ScheduleDates.kt", "package io.github.betterzslf.feature.schedule\nimport kotlinx.datetime.TimeZone\nconst val SCHEDULE_TIME_ZONE_ID: String = \"Asia/Shanghai\"\nfun scheduleWeek(): Int = 1\nfun validateSchedulePeriods(periods: List<SchedulePeriod>): Boolean = true\nfun scheduleOccurrences(snapshot: ScheduleSnapshot, periods: List<SchedulePeriod>, from: Long, until: Long): List<ScheduleCourse> = emptyList()\n")
        for file in ("AcademicHtmlParsing", "AcademicEncoding", "ScheduleWeekParsing"):
            self.write("feature/schedule/data" + ROOT + "feature/schedule/" + file + ".kt", "package io.github.betterzslf.feature.schedule\ninternal fun " + file[0].lower() + file[1:] + "(): String = \"fixture\"\n")
        self.write("feature/schedule/data" + ROOT + "feature/schedule/KtorAcademicSource.kt", "package io.github.betterzslf.feature.schedule\nimport io.ktor.client.HttpClient\nclass KtorAcademicSource(val client: HttpClient)\n")
        self.write("feature/home/presentation" + ROOT + "feature/home/presentation/HomePresentation.kt", "package io.github.betterzslf.feature.home.presentation\nimport io.github.betterzslf.feature.schedule.ScheduleSnapshot\ndata class HomeUiState(val snapshot: ScheduleSnapshot)\n")
        self.write("feature/home/ui" + ROOT + "feature/home/ui/HomeContent.kt", "package io.github.betterzslf.feature.home.ui\nimport io.github.betterzslf.feature.home.presentation.HomeUiState\nfun HomeContent(state: HomeUiState) = Unit\n")
        self.write("feature/update/domain" + ROOT + "feature/update/ProductUpdatePolicy.kt", "package io.github.betterzslf.feature.update\nclass ProductUpdatePolicy(val repository: String)\n")
        self.write("feature/update/domain" + ROOT + "feature/update/GithubReleaseModels.kt", "package io.github.betterzslf.feature.update\ninterface GithubReleaseSource\n")
        self.write("feature/update/domain" + ROOT + "platform/NativeAppUpdater.kt", "package io.github.betterzslf.platform\ninterface NativeAppUpdater\n")
        self.write("feature/update/data" + ROOT + "feature/update/GithubReleaseRepository.kt", "package io.github.betterzslf.feature.update\nimport io.ktor.client.HttpClient\nclass GithubReleaseRepository(val client: HttpClient) : GithubReleaseSource\n")
        self.write("feature/update/presentation" + ROOT + "feature/update/AppUpdateController.kt", "package io.github.betterzslf.feature.update\nclass AppUpdateController(val source: GithubReleaseSource)\n")
        self.write("feature/update/ui" + ROOT + "feature/update/ui/AppUpdateContent.kt", "package io.github.betterzslf.feature.update.ui\nimport io.github.betterzslf.feature.update.*\nimport io.github.betterzslf.platform.NativeAppUpdater\nfun AppUpdateContent(state: AppUpdateController, updater: NativeAppUpdater) = Unit\n")
        for file in ("AppLog", "LogEntry", "LogRedaction"):
            self.write("core/logging" + ROOT + "core/log/" + file + ".kt", "package io.github.betterzslf.core.log\nobject " + file + "\n")
        network = "core/network-session" + ROOT
        self.write(network + "core/model/Session.kt", "package io.github.betterzslf.core.model\nimport kotlinx.serialization.json.JsonObject\ndata class Session(val token: String, val userInfo: JsonObject, val deptInfo: JsonObject, val mapToken: String? = null, val mapWebType: String? = null,) {}\n")
        self.write(network + "core/net/ApiClient.kt", 'package io.github.betterzslf.core.net\nimport kotlinx.serialization.json.*\nclass ApiClient(httpClient: HttpClient, private val sessionStore: SessionStore, val config: ApiConfig = ApiConfig(), private val json: Json = Json { ignoreUnknownKeys = true },) {\n fun captureSessionBinding(): ApiSessionBinding? = null\n fun boundOwnerId(binding: ApiSessionBinding): String? = null\n fun isBindingCurrent(binding: ApiSessionBinding): Boolean = false\n fun clearCurrentBinding(binding: ApiSessionBinding): Boolean = false\n suspend fun execute(\n        endpoint: ApiEndpoint,\n        query: JsonObject = JsonObject(emptyMap()),\n        body: JsonObject? = null,\n        expectedSession: ApiSessionBinding? = null,\n    ): ApiResult<JsonElement> = executeWithOptionalLoginGate(endpoint, query, body, expectedSession, loginAttempt = null)\n suspend fun upload(file: UploadFile, tableId: String, childType: String = "feedbackImage", tablename: String = "sch_feedback",): ApiResult<JsonElement> { error("fixture") }\n}\n')
        self.write(network + "core/net/ApiSessionBinding.kt", 'package io.github.betterzslf.core.net\nclass ApiSessionBinding internal constructor(private val authority: Any, private val snapshot: SessionSnapshot,) {\n internal fun matches(authority: Any, current: SessionSnapshot): Boolean = false\n internal fun ownerFor(authority: Any): String? = null\n internal fun tokenFor(authority: Any): String? = null\n override fun toString(): String = "ApiSessionBinding(redacted)"\n}\n')
        self.write(network + "core/storage/SessionStore.kt", 'package io.github.betterzslf.core.storage\nimport kotlinx.serialization.json.Json\ninternal class SessionSnapshot internal constructor(val session: Session?, private val epoch: Long)\nclass SessionStore(private val store: KeyValueStore, private val json: Json = Json { ignoreUnknownKeys = true },) {\n internal fun captureSnapshot(): SessionSnapshot = error("fixture")\n internal fun clearIfCurrent(expected: SessionSnapshot): Boolean = false\n}\n')
        self.write(network + "core/net/QueryParameters.kt", 'package io.github.betterzslf.core.net\nimport kotlinx.serialization.json.*\nfun queryParameters(vararg values: Pair<String, String?>): JsonObject = buildJsonObject { }\n')
        for path, source in (("core/net/ApiConfig.kt", "class ApiConfig"), ("core/net/ApiEndpoint.kt", "enum class ApiEndpoint { READ }"),
                             ("core/net/UploadFile.kt", "class UploadFile"), ("core/result/ApiResult.kt", "sealed interface ApiResult<out T>"),
                             ("core/storage/KeyValueStore.kt", "interface KeyValueStore")):
            self.write(("core/storage" + ROOT if path == "core/storage/KeyValueStore.kt" else network) + path, "package io.github.betterzslf." + path.rsplit("/", 1)[0].replace("/", ".") + "\n" + source + "\n")
        fixtures = {
            "feature/accountrecovery/domain": ("AccountRecoverySource", "RecoveryValidation"),
            "feature/accountrecovery/data": ("ApiAccountRecoverySource",),
            "feature/accountrecovery/presentation": ("AccountRecoveryController", "AccountRecoveryBackPolicy", "AccountRecoveryState", "RecoveryMessages"),
            "feature/accountrecovery/ui": ("AccountRecoveryContent", "RecoveryContentControls"),
            "feature/campusnet/domain": ("CampusPortalModel", "CampusSources"),
            "feature/campusnet/data": ("CampusDataDecoder", "KtorPortalProbe", "RepositoryCampusSources"),
            "feature/campusnet/presentation": ("CampusNetworkController", "CampusNetworkModel", "CampusPortalController", "CampusPortalState"),
            "feature/campusnet/ui": ("CampusNetworkContent", "CampusNetworkContentControls"),
        }
        for module, names in fixtures.items():
            feature = module.split("/")[1]
            package = "ui.campusnet" if module == "feature/campusnet/ui" else "feature." + feature + (".ui" if module.endswith("/ui") else "")
            for name in names:
                self.write(module + ROOT + package.replace(".", "/") + "/" + name + ".kt", "package io.github.betterzslf." + package + "\nclass " + name + "\n")
        for name in ("CampusNetworkRepository", "CampusPortalRepository"):
            self.write("feature/campusnet/data" + ROOT + "data/" + name + ".kt", "package io.github.betterzslf.data\nclass " + name + "\n")
        # Campus login batch: four feature/auth modules with their reviewed finite contract.
        self.write("feature/auth/domain/build.gradle.kts", 'plugins { id("betterzslf.kmp-library") }\n')
        self.write("feature/auth/data/build.gradle.kts", self.build('api(project(":feature:auth:domain"))\napi(project(":core:network-session"))'))
        self.write("feature/auth/presentation/build.gradle.kts", self.build('api(project(":feature:auth:domain"))\nimplementation(project(":core:text"))\napi(libs.coroutines.core)'))
        self.write("feature/auth/ui/build.gradle.kts", self.build('implementation(project(":design-system"))\nimplementation(project(":core:text"))\nimplementation(libs.compose.runtime)\nimplementation(libs.compose.foundation)\nimplementation(libs.compose.ui)\nimplementation(libs.miuix.ui)\nimplementation(libs.miuix.icons)'))
        self.write("feature/auth/domain" + ROOT + "feature/auth/CampusLoginDomain.kt", "package io.github.betterzslf.feature.auth\nenum class CampusLoginIssue { VALIDATION, AUTHENTICATION, BUSINESS, SESSION_CHANGED, TIMEOUT, NETWORK, MALFORMED, HTTP, STORAGE }\nsealed interface CampusLoginOutcome { data object Accepted : CampusLoginOutcome; data class Rejected(val issue: CampusLoginIssue) : CampusLoginOutcome; data class Unknown(val issue: CampusLoginIssue) : CampusLoginOutcome }\ninterface CampusLoginSource { fun claim(): Boolean; fun isCurrent(): Boolean; suspend fun submit(username: String, password: String): CampusLoginOutcome; fun cancel(); fun consumeAcceptedContinuation(): Boolean }\nfun campusLoginCredentialsIssue(username: String, password: String): CampusLoginIssue? = if (username.isBlank() || password.isBlank()) CampusLoginIssue.VALIDATION else null\nfun normalizeCampusLoginUsername(username: String): String = username.trim()\n")
        self.write(network + "core/net/ApiLoginContract.kt", "package io.github.betterzslf.core.net\nclass ApiLoginAttempt internal constructor(private val authority: Any) { internal fun ownedBy(authority: Any): Boolean = this.authority === authority }\nenum class ApiLoginIssue { VALIDATION, AUTHENTICATION, BUSINESS, SESSION_CHANGED, TIMEOUT, NETWORK, MALFORMED, HTTP, STORAGE }\nsealed interface ApiLoginOutcome { data object Accepted : ApiLoginOutcome; data class Rejected(val issue: ApiLoginIssue) : ApiLoginOutcome; data class Unknown(val issue: ApiLoginIssue) : ApiLoginOutcome }\n")
        self.write("feature/auth/data" + ROOT + "feature/auth/ApiCampusLoginSource.kt", 'package io.github.betterzslf.feature.auth\nimport io.github.betterzslf.core.net.ApiClient\nimport io.github.betterzslf.core.net.ApiLoginAttempt\nimport io.github.betterzslf.core.net.ApiLoginIssue\nimport io.github.betterzslf.core.net.ApiLoginOutcome\nclass ApiCampusLoginSource private constructor(private val api: ApiClient, private val attempt: ApiLoginAttempt,) : CampusLoginSource {\n override fun claim(): Boolean = api.claimLoginAttempt(attempt)\n override fun isCurrent(): Boolean = api.isLoginAttemptCurrent(attempt)\n override suspend fun submit(username: String, password: String): CampusLoginOutcome = api.executeLogin(attempt, username, password).toCampusLoginOutcome()\n override fun cancel() { api.cancelLoginAttempt(attempt) }\n override fun consumeAcceptedContinuation(): Boolean = api.consumeLoginContinuation(attempt)\n override fun toString(): String = "ApiCampusLoginSource(redacted)"\n companion object { fun capture(api: ApiClient): CampusLoginSource = ApiCampusLoginSource(api, api.captureLoginAttempt()) }\n}\ninternal fun ApiLoginOutcome.toCampusLoginOutcome(): CampusLoginOutcome = when (this) { ApiLoginOutcome.Accepted -> CampusLoginOutcome.Accepted; is ApiLoginOutcome.Rejected -> CampusLoginOutcome.Rejected(issue.toCampusLoginIssue()); is ApiLoginOutcome.Unknown -> CampusLoginOutcome.Unknown(issue.toCampusLoginIssue()) }\ninternal fun ApiLoginIssue.toCampusLoginIssue(): CampusLoginIssue = when (this) { ApiLoginIssue.VALIDATION -> CampusLoginIssue.VALIDATION; ApiLoginIssue.AUTHENTICATION -> CampusLoginIssue.AUTHENTICATION; ApiLoginIssue.BUSINESS -> CampusLoginIssue.BUSINESS; ApiLoginIssue.SESSION_CHANGED -> CampusLoginIssue.SESSION_CHANGED; ApiLoginIssue.TIMEOUT -> CampusLoginIssue.TIMEOUT; ApiLoginIssue.NETWORK -> CampusLoginIssue.NETWORK; ApiLoginIssue.MALFORMED -> CampusLoginIssue.MALFORMED; ApiLoginIssue.HTTP -> CampusLoginIssue.HTTP; ApiLoginIssue.STORAGE -> CampusLoginIssue.STORAGE }\n')
        self.write("feature/auth/presentation" + ROOT + "feature/auth/CampusLoginPresentation.kt", 'package io.github.betterzslf.feature.auth\nimport io.github.betterzslf.core.text.AppText\nclass CampusLoginController(private val sourceFactory: () -> CampusLoginSource?, private val isCurrent: () -> Boolean,) {\n val state: CampusLoginState get() = CampusLoginState(CampusLoginPhase.EDITING, null, 0L, false, true)\n fun beginSubmission(): CampusLoginSubmission? = null\n suspend fun submit(ticket: CampusLoginSubmission, username: String, password: String): Boolean = false\n fun consumeAcceptedContinuation(ticket: CampusLoginSubmission): Boolean = false\n fun inputsChanged(): Unit = Unit\n fun close(): Unit = Unit\n}\nenum class CampusLoginPhase { EDITING, SUBMITTING, ACCEPTED, REJECTED, UNKNOWN, SESSION_CHANGED, CLOSED }\ndata class CampusLoginState(val phase: CampusLoginPhase, val issue: CampusLoginIssue?, val inputRevision: Long, val busy: Boolean, val submitAllowed: Boolean,) { companion object { fun initial(): CampusLoginState = CampusLoginState(CampusLoginPhase.EDITING, null, 0L, false, true) } }\nclass CampusLoginSubmission internal constructor(internal val owner: CampusLoginController, internal val source: CampusLoginSource,) { internal var started: Boolean = false\n internal var retired: Boolean = false\n internal var consumed: Boolean = false\n override fun toString(): String = "CampusLoginSubmission(redacted)"\n}\nfun CampusLoginIssue.displayText(): String = AppText.LABEL\nfun CampusLoginState.noticeText(): String? = null\n')
        self.write("feature/auth/ui" + ROOT + "ui/BackToChooser.kt", "package io.github.betterzslf.ui\nimport io.github.betterzslf.core.text.AppText\nfun BackToChooser(onBack: () -> Unit) = onBack\n")
        self.write("feature/auth/ui" + ROOT + "ui/auth/AuthLoginUi.kt", "package io.github.betterzslf.ui.auth\nenum class AuthLoginUiEvent { CHOOSE_CAMPUS, CHOOSE_ACADEMIC, CHOOSE_DORM, ENTER_APP, BACK, SUBMIT, RECOVER_PASSWORD, USER_AGREEMENT, PRIVACY_POLICY }\ndata class AuthChooserDisplay(val showDormLink: Boolean = true, val campusBound: Boolean = false, val academicBound: Boolean = false,) { val canEnter: Boolean get() = campusBound || academicBound }\ndata class CampusLoginDisplay(val busy: Boolean = false, val notice: CampusLoginNotice? = null, val extraLabel: String? = null, val extraKey: String = "",)\ndata class CampusLoginNotice(val title: String, val message: String,)\ndata class CampusLoginControls(val submitEnabled: Boolean = false, val recoveryAvailable: Boolean = false,)\n")
        self.write("platform/updates" + ROOT + "platform/UnavailableNativeUpdateOwner.kt", "package io.github.betterzslf.platform\nfun newUnavailableAppUpdater(): NativeAppUpdater = error(\"fixture\")\n")
        self.write("platform/updates/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidNativeUpdateOwner.kt", "package io.github.betterzslf.platform\nimport android.content.Context\nfun newAndroidAppUpdater(context: () -> Context): NativeAppUpdater = error(\"fixture\")\n")
        native = "platform/schedule-native"
        self.write(native + ROOT + "platform/ScheduleNativeContract.kt", "package io.github.betterzslf.platform\ninterface ScheduleNativeBridge\ndata class ScheduleNativeOpenRequest(val ownerRevision: String?)\nenum class ScheduleWidgetKind { TODAY, NEXT }\nfun ScheduleNativeOpenRequest.matchesRevision(revision: String): Boolean = scheduleNativeRevisionValid(revision) && ownerRevision == revision\n")
        self.write(native + ROOT + "platform/ScheduleNativePolicy.kt", "package io.github.betterzslf.platform\ninternal fun scheduleNativeRevisionValid(value: String): Boolean = value.isNotBlank()\n")
        self.write(native + ROOT + "platform/UnsupportedNativeScheduleOwner.kt", "package io.github.betterzslf.platform\nfun newUnsupportedScheduleNativeBridge(): ScheduleNativeBridge = UnsupportedScheduleNativeBridge()\ninternal class UnsupportedScheduleNativeBridge : ScheduleNativeBridge\n")
        self.write(native + ROOT + "feature/schedule/ScheduleLiveState.kt", "package io.github.betterzslf.feature.schedule\nclass ScheduleLiveState\n")
        self.write(native + ROOT + "schedule/vendor/VendorLivePolicy.kt", "package io.github.betterzslf.schedule.vendor\ninternal object VendorLivePolicy\n")
        android_files = ("platform/AndroidNativeScheduleOwner.kt", "schedule/ScheduleLiveNotifications.kt", "schedule/ScheduleNativeEngine.kt", "schedule/ScheduleReceivers.kt", "schedule/ScheduleWidgetProviders.kt", "schedule/vendor/FlymeLiveRenderer.kt", "schedule/vendor/LiveCard.kt", "schedule/vendor/VendorDiagnostics.kt", "schedule/vendor/VendorLiveContract.kt", "schedule/vendor/VendorLiveDispatcher.kt", "schedule/vendor/VivoLiveRenderer.kt", "schedule/vendor/XiaomiLiveRenderer.kt")
        for path in android_files:
            self.write(native + "/src/androidMain/kotlin/io/github/betterzslf/" + path, "package io.github.betterzslf." + path.rsplit("/", 1)[0].replace("/", ".") + "\n")
        resources = ("drawable/schedule_ic_notification.xml", "drawable/schedule_widget_background.xml", "layout/schedule_widget_next.xml", "layout/schedule_widget_today.xml", "raw/schedule_keep.xml", "values/schedule_colors.xml", "values/schedule_strings.xml", "values/schedule_styles.xml", "values-night/schedule_colors.xml", "xml/schedule_widget_today_info.xml", "xml/schedule_widget_next_info.xml", "drawable/schedule_live_end.xml", "drawable/schedule_live_progress_fill.xml", "drawable/schedule_live_progress_track.xml", "drawable/schedule_live_start.xml", "drawable/schedule_live_tracker.xml", "layout/schedule_flyme_capsule.xml", "layout/schedule_live_card.xml", "values/schedule_live_colors.xml", "values-night/schedule_live_colors.xml")
        for path in resources:
            self.write(native + "/src/androidMain/res/" + path, "<resources/>\n")
        receivers = ("ScheduleAlarmReceiver", "ScheduleDemoTickReceiver", "ScheduleLiveDismissReceiver", "ScheduleReconcileReceiver", "ScheduleTodayWidgetProvider", "ScheduleNextWidgetProvider")
        manifest = '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
        manifest += ''.join('<uses-permission android:name="android.permission.' + p + '" />' for p in ("POST_PROMOTED_NOTIFICATIONS", "SCHEDULE_EXACT_ALARM", "RECEIVE_BOOT_COMPLETED"))
        manifest += '<application><activity android:name="io.github.betterzslf.schedule.ScheduleNotificationPermissionActivity" android:exported="false" />'
        manifest += ''.join('<receiver android:name="io.github.betterzslf.schedule.' + r + '" android:exported="false" />' for r in receivers) + '</application></manifest>\n'
        self.write(native + "/src/androidMain/AndroidManifest.xml", manifest)
        self.write("platform/android-host/src/main/AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application/></manifest>\n')
        self.write("platform/storage/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidEncryptedStorage.kt", "package io.github.betterzslf.platform\nimport io.github.betterzslf.core.storage.KeyValueStore\nimport android.content.Context\nfun newAndroidEncryptedStore(context: Context): KeyValueStore = EncryptedStore(context.applicationContext)\nprivate class EncryptedStore(context: Context) : KeyValueStore\n")
        for name in ("ScheduleRuntime", "ScheduleState", "SchedulePersistence"):
            self.write("feature/schedule/runtime" + ROOT + "feature/schedule/runtime/" + name + ".kt", "package io.github.betterzslf.feature.schedule.runtime\n" + ("internal " if name == "SchedulePersistence" else "") + "class " + name + "\n")
        self.write("core/run-model" + ROOT + "feature/run/RunCheckpoint.kt", "package io.github.betterzslf.feature.run\nclass RunCheckpoint\n")
        self.write("core/run-model" + ROOT + "platform/RunMapCoordinateSystem.kt", "package io.github.betterzslf.platform\nenum class RunMapCoordinateSystem { GCJ02 }\n")
        for filename in ("RunPreviewSource.kt", "RunPreviewContext.kt"):
            relative = "feature/run/api" + ROOT + "feature/run/api/" + filename
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((Path(__file__).resolve().parents[2] / relative).read_bytes())
        run_fixtures = {
            "feature/run/domain": ("RunDataModels", "RunReadOutcome", "RunReadSources"),
            "feature/run/data": ("RunDataDecoder", "RunReadIdentity", "RunReadTransport", "ApiRunReadSource", "RunReadProjection"),
            "feature/run/presentation": ("RunTaskController", "RunRecordController", "RunDisplay", "RunReadMessages"),
        }
        for module, names in run_fixtures.items():
            for name in names:
                self.write(module + ROOT + "feature/runui/" + name + ".kt", "package io.github.betterzslf.feature.runui\n" + ("internal " if module.endswith("/data") else "") + "class " + name + "\n")
        self.write("feature/run/domain" + ROOT + "feature/runui/RunDataModels.kt", "package io.github.betterzslf.feature.runui\ndata class RunDisplayField(val label: String, val value: String)\n")
        self.write("feature/run/domain" + ROOT + "feature/runui/RunReadOutcome.kt", "package io.github.betterzslf.feature.runui\nenum class RunReadIssue { AUTHENTICATION, BUSINESS, VALIDATION, TIMEOUT, NETWORK, MALFORMED, HTTP, STORAGE }\nsealed interface RunReadOutcome<out T> { data class Success<T>(val data: T) : RunReadOutcome<T>; data class Failure(val issue: RunReadIssue) : RunReadOutcome<Nothing> }\n")
        self.write("feature/run/domain" + ROOT + "feature/runui/RunReadSources.kt", "package io.github.betterzslf.feature.runui\ninterface RunReadSource { val boundOwnerId: String; fun isCurrentSession(): Boolean }\n")
        self.write("feature/run/ui" + ROOT + "ui/run/RunListControls.kt", "package io.github.betterzslf.ui.run\nenum class RunListUiEvent { BACK, REFRESH, MORE, LOCAL_TRACKER, SELECT_TASKS }\nenum class RunTaskRowAction { DETAIL, RECORDS, RECORD_REAL }\n")
        for name in ("RunTaskListContent", "RunRecordsContent", "RunListDisplay", "RunWidgets", "RunWarnings"):
            self.write("feature/run/ui" + ROOT + "ui/run/" + name + ".kt", "package io.github.betterzslf.ui.run\nclass " + name + "\n")
        # Run write-kernel batch: the migrated repository, the nine runtime files and the whole
        # location module mirror the reviewed real sources byte-for-byte, like the RunPreview pins.
        self.write("core/network-session" + ROOT + "core/result/ApiError.kt", "package io.github.betterzslf.core.result\nsealed class ApiError\n")
        moved_kernel_sources = (
            "feature/run/data/src/commonMain/kotlin/io/github/betterzslf/data/RunRepository.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunBatcher.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunSessionCoordinator.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunSessionJournal.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunSessionModels.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunUploadQueue.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunUploadWorker.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunWireCoordinates.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunRuntime.kt",
            "feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunTaskLinks.kt",
            "platform/location/src/commonMain/kotlin/io/github/betterzslf/platform/PlatformCapabilities.kt",
            "platform/location/src/androidMain/kotlin/io/github/betterzslf/platform/LocationAndroidContext.kt",
            "platform/location/src/androidMain/kotlin/io/github/betterzslf/platform/LocationPermission.android.kt",
            "platform/location/src/androidMain/kotlin/io/github/betterzslf/platform/LocationTracker.android.kt",
            "platform/location/src/iosMain/kotlin/io/github/betterzslf/platform/IosCapabilityRules.kt",
            "platform/location/src/iosMain/kotlin/io/github/betterzslf/platform/LocationPermission.ios.kt",
            "platform/location/src/iosMain/kotlin/io/github/betterzslf/platform/LocationTracker.ios.kt",
            "platform/location/src/jvmMain/kotlin/io/github/betterzslf/platform/PlatformCapabilities.jvm.kt",
        )
        for relative in moved_kernel_sources:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((Path(__file__).resolve().parents[2] / relative).read_bytes())
        # Remaining schedule UI batch: seven migrated pages/models, the runtime-owned selection/
        # opener records and the design-system dialog mirror the reviewed real sources byte-for-byte.
        schedule_ui_sources = (
            "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleTokens.kt",
            "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleWeekBoard.kt",
            "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleComponents.kt",
            "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/SchedulePeriodsPage.kt",
            "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleUiModel.kt",
            "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleTermDetailPages.kt",
            "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleWidgetPage.kt",
            "feature/schedule/runtime/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleDetailSelection.kt",
            "feature/schedule/runtime/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleNativeOpenTarget.kt",
            "design-system/src/commonMain/kotlin/io/github/betterzslf/ui/ConfirmDialog.kt",
        )
        for relative in schedule_ui_sources:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((Path(__file__).resolve().parents[2] / relative).read_bytes())
        # V2 closing batch: the profile/content verticals mirror the reviewed real sources.
        profile_content_sources = (
            "feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt",
            "feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/AvatarImagePolicy.kt",
            "feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt",
            "feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/feature/profile/AvatarByteRepository.kt",
            "feature/profile/presentation/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileController.kt",
            "feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentModels.kt",
            "feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentSource.kt",
            "feature/content/data/src/commonMain/kotlin/io/github/betterzslf/data/ContentRepository.kt",
            "feature/content/data/src/commonMain/kotlin/io/github/betterzslf/feature/content/RepositoryContentSource.kt",
            "feature/content/presentation/src/commonMain/kotlin/io/github/betterzslf/feature/content/ArticleController.kt",
        )
        for relative in profile_content_sources:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((Path(__file__).resolve().parents[2] / relative).read_bytes())
        authority = "package io.github.betterzslf.feature.schedule.runtime\n"
        for name in ("ScheduleBindingLease", "ScheduleVerificationAttempt", "ScheduleBindingCommit"):
            authority += "class " + name + " internal constructor(internal val runtime: ScheduleRuntime) { override fun toString(): String = \"" + name + "(<redacted>)\" }\n"
        self.write("feature/schedule/runtime" + ROOT + "feature/schedule/runtime/ScheduleBindingAuthority.kt", authority)
        prefix = "feature/schedule/ui" + ROOT + "ui/schedule/"
        self.write(prefix + "ScheduleBindingContent.kt", "package io.github.betterzslf.ui.schedule\nfun ScheduleBindingPromptContent(visible: Boolean, onEvent: (ScheduleBindingUiEvent) -> Unit) = Unit\nfun ScheduleBindingPageContent(display: ScheduleBindingDisplay, controls: ScheduleBindingControls, onEvent: (ScheduleBindingUiEvent) -> Unit, accountField: @Composable () -> Unit, passwordField: @Composable () -> Unit,) = Unit\n")
        self.write(prefix + "ScheduleBindingDisplay.kt", 'package io.github.betterzslf.ui.schedule\ndata class ScheduleBindingDisplay(val updatingPassword: Boolean = false, val hasSlot: Boolean = false, val storageFailure: Boolean = false, val testing: Boolean = false, val saving: Boolean = false, val notice: ScheduleBindingNotice = ScheduleBindingNotice.NOT_VERIFIED, val verifiedLabel: String = "", val runtimeMessage: ScheduleBindingMessage? = null,)\nenum class ScheduleBindingNotice { NOT_VERIFIED, INPUT_CHANGED, VERIFY_FAILED, SAVE_FAILED }\nenum class ScheduleBindingMessage { STORAGE_FAILURE, VERIFY_FAILED, CREDENTIALS_REQUIRED, CACHE_RETAINED, NO_CACHE, PERIODS_INVALID, UNBIND_FAILED, NATIVE_UPDATE_FAILED, PARTIAL_PARSE_RETAINED, READ_FAILED }\n')
        self.write(prefix + "ScheduleBindingControls.kt", "package io.github.betterzslf.ui.schedule\nenum class ScheduleBindingUiEvent { PROMPT_BIND, PROMPT_SKIP, PROMPT_DISMISS, BACK, VERIFY, CONFIRM }\ndata class ScheduleBindingControls(val canVerify: Boolean = false, val canConfirm: Boolean = false, val canBack: Boolean = true,)\n")
        self.write(prefix + "ScheduleBindingPrimitives.kt", "package io.github.betterzslf.ui.schedule\nfun SchedulePanel(content: @Composable ColumnScope.() -> Unit) = Unit\nfun ScheduleProgress(message: String) = Unit\n")
        self.probe = "core/run-model" + ROOT + "feature/run/Probe.kt"

    @staticmethod
    def build(dependencies):
        return "kotlin { sourceSets { commonMain.dependencies {\n" + dependencies + "\n} } }\n"

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="") as stream:
            stream.write(content)

    def append(self, relative, content):
        path = self.root / relative
        with path.open("a", encoding="utf-8", newline="") as stream:
            stream.write(content)

    def run_guard(self):
        return guard.run_guard(self.root)

    def codes(self):
        result = self.run_guard()
        return {item["code"] for item in (*result.errors, *result.violations)}

    def test_valid_extraction_allows_legacy_packages_without_edition_substitutes(self):
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_retired_private_source_trees_fail_even_if_not_selected(self):
        self.write("shared/src/proMain/kotlin/StillMigrating.kt", "package old.pro\nclass PrivateImplementation\n")
        self.assertIn("edition_source_overlay", self.codes())

    def test_private_implementation_symbols_packages_and_libraries_fail_publicly(self):
        for source, code in (("package io.github.betterzslf.feature.water\nclass Protocol\n", "private_production_package"),
                             ("import com.juul.kable.Peripheral\n", "private_production_import"),
                             ("class RunImportController\n", "private_production_symbol"),
                             ("object AppText { object Water { val text = \"value\" } }\n", "private_text_catalog")):
            with self.subTest(source=source):
                self.write("shared/src/commonMain/kotlin/Private.kt", source)
                self.assertIn(code, self.codes())

    def test_public_migration_denylist_and_real_location_are_not_private_implementations(self):
        self.write("shared/src/commonMain/kotlin/ProductNavigation.kt", 'private val retiredPrivatePages = setOf("water", "runimport", "runmanual", "runplan", "runsupplement")\nclass NativeLocationTracker\nfun reject(kind: String): Boolean = kind in retiredPrivatePages\n')
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_migration_denylist_cannot_register_or_admit_a_private_page(self):
        path = "shared/src/commonMain/kotlin/io/github/betterzslf/navigation/ProductNavigation.kt"
        keys = 'private val retiredPrivatePages = setOf("water", "runimport", "runmanual", "runplan", "runsupplement")\n'
        self.write(path, keys + 'fun accept(kind: String): Boolean = kind !in retiredPrivatePages\n')
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.write(path, keys + 'fun accept(kind: String): Boolean = kind in retiredPrivatePages\n')
        self.assertIn("navigation_migration_contract", self.codes())
        self.write(path, keys + 'fun open() = Route.Page("water")\n')
        self.assertIn("private_route_registration", self.codes())

    def test_composition_public_contract_cannot_hide_business_payload_in_shared(self):
        self.write("shared/src/commonMain/kotlin/io/github/betterzslf/composition/Contributions.kt", "interface Contributions { fun create(value: Any): Unit }\n")
        self.assertIn("untyped_public_api", self.codes())

    def test_private_manifest_and_included_build_are_not_public_inputs(self):
        self.write("androidApp/src/pro/AndroidManifest.xml", "<manifest/>\n")
        self.assertIn("private_product_overlay", self.codes())
        self.append("settings.gradle.kts", '\nincludeBuild("work/pro-ci")\n')
        self.assertIn("private_included_build", self.codes())

    def test_public_source_selection_excludes_and_private_dependency_fail(self):
        self.append("shared/build.gradle.kts", '\nkotlin { sourceSets { commonMain { kotlin.srcDir("src/${edition}Main/kotlin"); kotlin.exclude(proOnlyMainSources) } } }\nimplementation(libs.kable)\n')
        self.assertIn("source_overlay", self.codes())
        self.assertIn("private_production_dependency", self.codes())

    def test_missing_scope_and_settings_or_build_fail_closed(self):
        (self.root / "feature/services/ui/src/commonMain/kotlin/io/github/betterzslf/feature/services/ui/ServicesContent.kt").unlink()
        self.write("settings.gradle.kts", 'include(":shared")')
        self.assertIn("missing_module_include", self.codes())
        (self.root / "core/text/build.gradle.kts").unlink()
        self.assertIn("source_error", self.codes())
        self.assertFalse(guard.run_guard(self.root / "absent").ok)

    def test_r5_pure_api_domain_and_presentations_reject_transport_ui_graph_and_raw_json(self):
        for module in ("feature/schedule/api", "feature/schedule/domain", "feature/home/presentation",
                       "feature/update/domain", "feature/update/presentation"):
            path = module + "/src/commonMain/kotlin/R5Probe.kt"
            for name in ("io.ktor.client.HttpClient", "androidx.compose.runtime.Composable", "io.github.betterzslf.AppGraph", "io.github.betterzslf.data.SchoolRepository"):
                with self.subTest(module=module, name=name):
                    self.write(path, "import " + name + "\n")
                    self.assertIn("forbidden_import", self.codes())
            self.write(path, "fun read(value: Map<String, Any>): String = \"\"\n")
            self.assertIn("untyped_public_api", self.codes())
            (self.root / path).unlink()

    def test_r5_data_allows_transport_but_not_shared_or_ui(self):
        for module in ("feature/schedule/data", "feature/update/data"):
            path = module + "/src/commonMain/kotlin/R5Probe.kt"
            self.write(path, "import io.ktor.client.HttpClient\nprivate val client: HttpClient? = null\n")
            self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
            for name in ("io.github.betterzslf.data.SchoolRepository", "io.github.betterzslf.AppGraph", "androidx.compose.runtime.Composable"):
                with self.subTest(module=module, name=name):
                    self.write(path, "import " + name + "\n")
                    self.assertIn("forbidden_import", self.codes())
            (self.root / path).unlink()
            self.append(module + "/build.gradle.kts", self.build('implementation(project(":shared"))'))
            self.assertIn("forbidden_project_dependency", self.codes())
            # Restore only the injected line, not a production exception.
            self.write(module + "/build.gradle.kts", self.build('api(project(":feature:' + ("schedule" if "schedule" in module else "update") + ':domain"))\napi(libs.ktor.core)'))

    def test_r5_schedule_serialization_and_home_pure_date_rules_are_allowed(self):
        self.write("feature/schedule/api/src/commonMain/kotlin/R5Probe.kt", "import kotlinx.serialization.Serializable\n@Serializable data class ReadModel(val title: String)\n")
        self.write("feature/home/presentation/src/commonMain/kotlin/R5Probe.kt", "import kotlinx.datetime.TimeZone\nval zone: TimeZone = TimeZone.UTC\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_r5_all_platform_main_are_guarded_but_test_fixtures_are_not(self):
        self.write("feature/update/data/src/commonTest/kotlin/Negative.kt", 'import io.github.betterzslf.AppGraph\n$$\"\"\"unsupported\"\"\"')
        self.write("feature/schedule/data/src/iosMain/kotlin/R5Probe.kt", "import io.github.betterzslf.data.SchoolRepository\n")
        result = self.run_guard()
        self.assertIn("forbidden_import", {item["code"] for item in result.violations})
        self.assertFalse(result.errors)

    def test_r5_module_dag_rejects_reverse_edges_not_just_shared(self):
        for module, dependency in (("feature/schedule/api", ":feature:schedule:data"),
                                   ("feature/update/domain", ":feature:update:presentation"),
                                   ("feature/home/presentation", ":feature:home:ui"),
                                   ("feature/update/ui", ":feature:update:data")):
            with self.subTest(module=module):
                self.append(module + "/build.gradle.kts", self.build('implementation(project("' + dependency + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())

    def test_r5_legacy_package_wildcard_exposes_only_referenced_visible_owners(self):
        path = "feature/update/ui/src/commonMain/kotlin/R5Probe.kt"
        self.write(path, "import io.github.betterzslf.feature.update.*\nfun view(controller: AppUpdateController, source: GithubReleaseSource) = Unit\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.write(path, "import io.github.betterzslf.feature.update.*\nfun view(source: GithubReleaseRepository) = Unit\n")
        self.assertIn("forbidden_import", self.codes())

    def test_r5_implementation_edge_does_not_export_domain_to_its_consumers(self):
        self.write("feature/update/presentation/build.gradle.kts", self.build('implementation(project(":feature:update:domain"))\nimplementation(project(":core:text"))'))
        self.assertIn("forbidden_import", self.codes())  # UI's NativeAppUpdater import loses visibility.

    def test_r5_package_local_functions_cannot_bypass_data_boundary(self):
        self.write("feature/schedule/domain/src/commonMain/kotlin/R5Probe.kt", "package io.github.betterzslf.feature.schedule\nfun read(): String = academicHtmlParsing()\n")
        self.assertIn("forbidden_reference", self.codes())

    def test_r5_new_whole_files_cannot_have_a_legacy_shared_owner(self):
        self.write("shared" + ROOT + "feature/schedule/ScheduleModels.kt", "package io.github.betterzslf.feature.schedule\ndata class ScheduleSnapshot(val title: String)\n")
        self.write("shared" + ROOT + "feature/update/ProductUpdatePolicy.kt", "package io.github.betterzslf.feature.update\nclass ProductUpdatePolicy\n")
        self.assertIn("legacy_moved_source", self.codes())
        self.assertIn("duplicate_declaration", self.codes())

    def test_anonymous_shared_objects_do_not_own_parameter_punctuation(self):
        self.write("shared/src/commonMain/kotlin/Anon.kt", "package io.github.betterzslf.feature.schedule\nfun source(): AcademicSource = object : AcademicSource { override suspend fun read(): ScheduleSnapshot? = null }\n")
        self.write("feature/schedule/domain/src/commonMain/kotlin/Callback.kt", "package io.github.betterzslf.feature.schedule\nfun observe(callback: (ScheduleSnapshot) -> Unit) = Unit\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_repeated_leaf_names_require_nonconstant_convention_component_identity(self):
        path = "build-logic/src/main/groovy/betterzslf.kmp-library.gradle"
        self.write(path, "group = 'io.github.betterzslf'\nversion = '0.1.0-SNAPSHOT'\n")
        self.assertIn("module_coordinate_collision", self.codes())
        self.write(path, "def ownerPath = project.path.substring(1).split(':').dropRight(1).join('.')\ngroup = 'io.github.betterzslf' + (ownerPath ? '.' + ownerPath : '')\nversion = '0.1.0-SNAPSHOT'\n")
        self.assertNotIn("module_coordinate_collision", self.codes())

    def test_core_transport_and_logging_have_exact_dependency_boundaries(self):
        network = "core/network-session/src/commonMain/kotlin/Probe.kt"
        logging = "core/logging/src/commonMain/kotlin/Probe.kt"
        self.write(network, "import io.ktor.client.HttpClient\nimport io.github.betterzslf.core.log.AppLog\nprivate val client: HttpClient? = null\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        for path, dependency in ((network, "io.github.betterzslf.data.SchoolRepository"), (network, "io.github.betterzslf.feature.schedule.ScheduleSnapshot"),
                                 (network, "androidx.compose.runtime.Composable"), (logging, "io.ktor.client.HttpClient"),
                                 (logging, "io.github.betterzslf.feature.run.GeoPoint"), (logging, "io.github.betterzslf.AppGraph")):
            with self.subTest(path=path, dependency=dependency):
                self.write(path, "import " + dependency + "\n")
                self.assertIn("forbidden_import", self.codes())
        self.append("core/network-session/build.gradle.kts", self.build('implementation(project(":shared"))'))
        self.assertIn("forbidden_project_dependency", self.codes())

    def test_core_thirteen_exact_moved_files_cannot_be_copied_back(self):
        self.write("shared" + ROOT + "core/net/ApiClient.kt", "package io.github.betterzslf.core.net\nclass ApiClient\n")
        self.write("shared" + ROOT + "core/log/LogEntry.kt", "package io.github.betterzslf.core.log\nclass LogEntry\n")
        self.assertIn("legacy_moved_source", self.codes())
        self.assertIn("duplicate_declaration", self.codes())
        (self.root / ("core/network-session" + ROOT + "core/net/UploadFile.kt")).unlink()
        self.assertIn("missing_moved_source", self.codes())

    def test_core_raw_json_is_only_the_exact_legacy_declaration_not_a_module_skip(self):
        path = "core/network-session" + ROOT + "core/net/Foo.kt"
        for source in ("fun newRead(): JsonObject = error(\"fixture\")", "class Foo(val payload: JsonElement)",
                       "fun newRead(value: Any): String = \"\"", "fun execute(query: JsonObject): JsonElement = error(\"fixture\")"):
            with self.subTest(source=source):
                self.write(path, "package io.github.betterzslf.core.net\nimport kotlinx.serialization.json.*\n" + source + "\n")
                self.assertIn("untyped_public_api", self.codes())
        path = "core/network-session" + ROOT + "core/net/ApiClient.kt"
        self.append(path, "\nclass Foo { fun upload(file: UploadFile, tableId: String, childType: String = \"feedbackImage\", tablename: String = \"sch_feedback\",): ApiResult<JsonElement> { error(\"fixture\") } }\n")
        self.assertIn("untyped_public_api", self.codes())

    def test_core_allowlisted_headers_reject_additional_json_fields_any_and_alias(self):
        path = "core/network-session" + ROOT + "core/model/Session.kt"
        original = (self.root / path).read_text(encoding="utf-8")
        for old, new in (("val deptInfo: JsonObject,", "val deptInfo: JsonObject, val extra: JsonObject,"),
                         ("val userInfo: JsonObject", "val userInfo: Any"),
                         ("val userInfo: JsonObject", "val userInfo: Payload")):
            with self.subTest(new=new):
                self.write(path, original.replace(old, new).replace("import kotlinx.serialization.json.JsonObject", "import kotlinx.serialization.json.JsonObject\nimport kotlinx.serialization.json.JsonObject as Payload"))
                self.assertIn("untyped_public_api", self.codes())

    def test_core_binding_constructor_token_and_snapshot_remain_internal(self):
        base = "core/network-session" + ROOT
        for suffix, old, new in (("core/net/ApiSessionBinding.kt", "class ApiSessionBinding internal constructor", "class ApiSessionBinding constructor"),
                                 ("core/net/ApiSessionBinding.kt", "internal fun tokenFor", "fun tokenFor"),
                                 ("core/net/ApiSessionBinding.kt", "private val snapshot", "val snapshot"),
                                 ("core/storage/SessionStore.kt", "internal class SessionSnapshot", "class SessionSnapshot"),
                                 ("core/storage/SessionStore.kt", "internal fun captureSnapshot", "fun captureSnapshot"),
                                 ("core/storage/SessionStore.kt", "internal fun clearIfCurrent", "fun clearIfCurrent")):
            path = base + suffix
            original = (self.root / path).read_text(encoding="utf-8")
            with self.subTest(suffix=suffix, new=new):
                self.write(path, original.replace(old, new))
                self.assertIn("session_capability_visibility", self.codes())
                self.write(path, original)

    def test_core_binding_public_credential_property_and_external_snapshot_signature_fail(self):
        path = "core/network-session" + ROOT + "core/net/ApiSessionBinding.kt"
        original = (self.root / path).read_text(encoding="utf-8")
        self.write(path, original.rsplit("}", 1)[0] + " val rawToken: String? get() = tokenFor(authority)\n}\n")
        self.assertIn("session_capability_visibility", self.codes())
        self.write(path, original)
        leak = "core/network-session" + ROOT + "core/net/Leak.kt"
        for declaration in ("data class PublicSnapshot(val value: SessionSnapshot)", "fun snapshot(): SessionSnapshot = error(\"fixture\")",
                            "fun inspect(binding: ApiSessionBinding): Boolean = true"):
            with self.subTest(declaration=declaration):
                self.write(leak, "package io.github.betterzslf.core.net\n" + declaration + "\n")
                self.assertIn("session_capability_visibility", self.codes())
        self.write(leak, "package io.github.betterzslf.core.net\nprivate fun snapshot(): SessionSnapshot = error(\"fixture\")\n")
        self.assertNotIn("session_capability_visibility", self.codes())

    def test_core_only_four_finite_binding_ops_and_no_inferred_snapshot_escape(self):
        path = "core/network-session" + ROOT + "core/net/ApiClient.kt"
        original = (self.root / path).read_text(encoding="utf-8")
        self.write(path, original.rsplit("}", 1)[0] + " fun exposeSnapshot() = sessionStore.captureSnapshot()\n}\n")
        self.assertIn("session_capability_visibility", self.codes())
        self.write(path, original.rsplit("}", 1)[0] + " fun fork(binding: ApiSessionBinding): ApiSessionBinding = binding\n}\n")
        self.assertIn("session_capability_visibility", self.codes())

    def test_sensitive_flow_domains_and_presentations_have_no_transport_or_core_session(self):
        for module in ("feature/accountrecovery/domain", "feature/accountrecovery/presentation", "feature/campusnet/domain", "feature/campusnet/presentation"):
            path = module + "/src/commonMain/kotlin/Probe.kt"
            for name in ("io.ktor.client.HttpClient", "io.github.betterzslf.core.model.Session", "io.github.betterzslf.core.result.ApiResult", "io.github.betterzslf.core.net.ApiClient", "androidx.compose.runtime.Composable"):
                with self.subTest(module=module, name=name):
                    self.write(path, "import " + name + "\n")
                    self.assertIn("forbidden_import", self.codes())
            self.write(path, "fun transport(): ApiResult<String> = error(\"fixture\")\n")
            self.assertIn("untyped_public_api", self.codes())
            (self.root / path).unlink()

    def test_new_data_can_use_core_but_public_raw_transport_is_not_a_contract(self):
        for module in ("feature/accountrecovery/data", "feature/campusnet/data"):
            path = module + "/src/commonMain/kotlin/Probe.kt"
            self.write(path, "import io.github.betterzslf.core.net.ApiClient\nprivate fun call(api: ApiClient) = api.execute()\n")
            self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
            for source in ("fun call(api: ApiClient) = api.execute()", "fun result(): ApiResult<JsonElement> = error(\"fixture\")", "fun result() = JsonObject(emptyMap())", "val result = api.execute()"):
                with self.subTest(module=module, source=source):
                    self.write(path, "import io.github.betterzslf.core.net.ApiClient\n" + source + "\n")
                    self.assertIn("untyped_public_api", self.codes())
            (self.root / path).unlink()

    def test_data_explicit_typed_when_expression_local_val_is_not_a_public_wire_leak(self):
        path = "feature/accountrecovery/data/src/commonMain/kotlin/Probe.kt"
        self.write(path, "fun send(): RecoveryOutcome = when (val result = api.execute()) { else -> RecoveryOutcome.Done }\n")
        self.assertNotIn("untyped_public_api", self.codes())

    def test_sensitive_ui_has_no_controller_ticket_target_raw_fields_even_under_alias(self):
        for module, controller in (("feature/accountrecovery/ui", "AccountRecoveryController"), ("feature/campusnet/ui", "CampusNetworkController")):
            path = module + "/src/commonMain/kotlin/Probe.kt"
            for declaration in ("fun Content(controller: " + controller + ") = Unit", "data class Controls(val password: String)", "fun Content(portalUrl: String) = Unit", "class Props(val ticket: RecoveryResetTicket)"):
                with self.subTest(module=module, declaration=declaration):
                    self.write(path, declaration + "\n")
                    self.assertIn("sensitive_ui_api", self.codes())
            (self.root / path).unlink()
        self.write("feature/accountrecovery/ui/src/commonMain/kotlin/Probe.kt", "import io.github.betterzslf.feature.accountrecovery.AccountRecoveryController as Binder\nfun Content(controller: Binder) = Unit\n")
        self.assertIn("sensitive_ui_api", self.codes())
        self.write("feature/accountrecovery/ui/src/commonMain/kotlin/Probe.kt", "internal typealias Alias = RecoveryResetTicket\ninternal typealias Alias2 = Alias\nfun Content(authority: Alias2) = Unit\n")
        self.assertIn("sensitive_ui_api", self.codes())

    def test_safe_ui_masked_controls_opaque_key_and_zero_payload_event_are_allowed(self):
        self.write("feature/campusnet/ui/src/commonMain/kotlin/Probe.kt", "data class Controls(val maskedPhone: String, val finalMaskedAccount: String, val canPrepare: Boolean)\nfun Content(onSelectAccount: (String) -> Unit, passwordFields: (enabled: Boolean) -> Unit) = Unit\nenum class Event { OPEN, CONFIRM }\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_new_flow_and_platform_reverse_project_edges_fail(self):
        for module, forbidden in (("feature/accountrecovery/domain", ":feature:accountrecovery:data"), ("feature/campusnet/presentation", ":feature:campusnet:data"), ("feature/campusnet/ui", ":core:network-session"), ("platform/updates", ":shared")):
            with self.subTest(module=module):
                self.append(module + "/build.gradle.kts", self.build('implementation(project("' + forbidden + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())

    def test_platform_owned_updater_has_no_global_shared_host_or_transport(self):
        path = "platform/updates/src/androidMain/kotlin/Probe.kt"
        for name in ("io.github.betterzslf.AppGraph", "io.ktor.client.HttpClient", "androidx.compose.runtime.Composable"):
            with self.subTest(name=name):
                self.write(path, "import " + name + "\n")
                self.assertIn("forbidden_import", self.codes())
        self.write(path, "class Probe\n")
        self.append("platform/updates/build.gradle.kts", self.build('implementation(libs.androidx.core)'))
        self.assertIn("forbidden_library_dependency", self.codes())

    def test_new_moved_owner_and_platform_resource_copies_fail(self):
        self.write("shared" + ROOT + "data/CampusNetworkRepository.kt", "package io.github.betterzslf.data\nclass CampusNetworkRepository\n")
        self.write("shared/src/androidMain/AndroidManifest.xml", "<manifest/>\n")
        self.assertIn("legacy_moved_source", self.codes())
        self.assertIn("duplicate_declaration", self.codes())
        (self.root / "platform/updates/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidNativeUpdateOwner.kt").unlink()
        self.assertIn("missing_moved_source", self.codes())

    def test_runtime_storage_is_explicit_and_has_no_network_ui_or_root_authority(self):
        path = "feature/schedule/runtime/src/commonMain/kotlin/Probe.kt"
        for name in ("io.github.betterzslf.core.storage.SessionStore", "io.github.betterzslf.core.net.ApiClient", "io.ktor.client.HttpClient", "androidx.compose.runtime.Composable", "io.github.betterzslf.platform.createPlatformServices"):
            with self.subTest(name=name):
                self.write(path, "import " + name + " as Hidden\n")
                self.assertIn("forbidden_import", self.codes())
        for source in ("fun f() = io.github.betterzslf.platform.createPlatformServices()", "fun f() = io.github.betterzslf.core.storage.SessionStore()", "fun f() = createScheduleNativeBridge()"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertTrue(self.codes() & {"schedule_forbidden_capability", "forbidden_reference"})
        self.write(path, "import io.github.betterzslf.core.storage.KeyValueStore\nimport kotlinx.serialization.json.Json\ninternal fun serialize(storage: KeyValueStore) = Json { ignoreUnknownKeys = true }\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_runtime_credential_records_and_aliases_do_not_become_public(self):
        path = "feature/schedule/runtime/src/commonMain/kotlin/Probe.kt"
        for source in ("data class StoredSchedule(val password: String)", "class SchedulePersistence", "class Verification(val password: String)", "internal typealias Alias = StoredSchedule\ninternal typealias Chain = Alias\nfun read(): Chain = error(\"fixture\")", "val password: String get() = secret", "fun getCredentials(): String = secret"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertTrue(self.codes() & {"untyped_public_api", "schedule_capability_visibility"})
        self.write(path, "class ScheduleRuntime { fun verifyBinding(account: String, password: String, leak: (String) -> Unit): Boolean = false }\n")
        self.assertIn("schedule_capability_visibility", self.codes())

    def test_class_literal_annotation_preserves_internal_visibility_but_public_class_still_fails(self):
        path = "feature/schedule/runtime/src/commonMain/kotlin/Probe.kt"
        self.write(path, "@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)\npackage fixture\nimport kotlinx.serialization.json.Json\ninternal data class StoredSchedule(val password: String)\ninternal class SchedulePersistence { private val json = Json { ignoreUnknownKeys = true } }\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.write(path, "@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)\npackage fixture\ndata class StoredSchedule(val password: String)\n")
        self.assertIn("untyped_public_api", self.codes())

    def test_typed_storage_factory_does_not_export_private_expression_type(self):
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        path = "platform/storage/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidEncryptedStorage.kt"
        self.write(path, "package io.github.betterzslf.platform\nimport io.github.betterzslf.core.storage.KeyValueStore\nfun newAndroidEncryptedStore(context: Context): EncryptedStore = error(\"fixture\")\nprivate class EncryptedStore : KeyValueStore\n")
        self.assertIn("untyped_public_api", self.codes())

    def test_native_policy_broad_exports_and_same_package_vendor_capture_fail(self):
        policy = "platform/schedule-native" + ROOT + "platform/ScheduleNativePolicy.kt"
        for source in ("fun scheduleNativeRevisionValid(value: String): Boolean = true", "public object Policy { fun matches(value: String) = true }", "typealias PublicPlan = ScheduleNativeReminderPlan"):
            with self.subTest(source=source):
                self.write(policy, "package io.github.betterzslf.platform\n" + source + "\n")
                self.assertIn("schedule_capability_visibility", self.codes())
        self.write(policy, "package io.github.betterzslf.platform\ninternal fun scheduleNativeRevisionValid(value: String) = true\n")
        self.write("shared" + ROOT + "schedule/vendor/RootPolicy.kt", "package io.github.betterzslf.schedule.vendor\ninternal object RootPolicy\n")
        self.write("platform/schedule-native/src/androidMain/kotlin/Probe.kt", "package io.github.betterzslf.schedule.vendor\nfun render() = RootPolicy\n")
        self.assertIn("forbidden_reference", self.codes())

    def test_native_storage_cold_start_may_use_platform_factory_not_global_or_callback(self):
        path = "platform/schedule-native/src/androidMain/kotlin/Probe.kt"
        self.write(path, "package io.github.betterzslf.schedule\nimport android.content.Context\nimport io.github.betterzslf.platform.newAndroidEncryptedStore\ninternal fun storage(context: Context) = newAndroidEncryptedStore(context)\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        for source in ("fun storage() = io.github.betterzslf.platform.createPlatformServices().storage", "fun storage() = io.github.betterzslf.platform.AndroidPlatform.context", "import io.github.betterzslf.core.net.ApiClient as Secret\ninternal fun storage(client: Secret) = Unit"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertTrue(self.codes() & {"schedule_forbidden_capability", "forbidden_reference", "forbidden_import"})

    def test_native_resources_manifest_and_old_policy_are_single_owned(self):
        resource = "platform/schedule-native/src/androidMain/res/layout/schedule_live_card.xml"
        (self.root / resource).unlink()
        self.assertIn("missing_schedule_owner", self.codes())
        self.write(resource, "<resources/>\n")
        self.write("shared/src/androidMain/res/layout/schedule_live_card.xml", "<resources/>\n")
        self.assertIn("legacy_schedule_owner", self.codes())
        manifest = "platform/schedule-native/src/androidMain/AndroidManifest.xml"
        self.write(manifest, '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><receiver android:name="io.github.betterzslf.schedule.ScheduleAlarmReceiver" android:exported="true" /></application></manifest>\n')
        self.assertIn("schedule_manifest_contract", self.codes())
        self.write("shared" + ROOT + "schedule/vendor/VendorLivePolicy.kt", "package io.github.betterzslf.schedule.vendor\ninternal object VendorLivePolicy\n")
        self.assertIn("legacy_moved_source", self.codes())
        self.assertIn("duplicate_declaration", self.codes())

    def test_schedule_runtime_native_and_storage_gradle_edges_cannot_reverse_or_reenter_network(self):
        for module, dependency in (("feature/schedule/runtime", ":core:network-session"), ("platform/schedule-native", ":shared"), ("core/storage", ":core:text"), ("platform/storage", ":core:network-session")):
            with self.subTest(module=module):
                self.append(module + "/build.gradle.kts", self.build('implementation(project("' + dependency + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())
        self.append("platform/schedule-native/build.gradle.kts", self.build('implementation(project(":platform:storage"))'))
        self.assertIn("forbidden_project_dependency", self.codes())

    def test_run_domain_presentation_reject_session_transport_json_and_runtime_even_aliases(self):
        for module in ("feature/run/domain", "feature/run/presentation"):
            path = module + "/src/commonMain/kotlin/Probe.kt"
            for source in ("import io.github.betterzslf.core.model.Session as Hidden\ninternal val snapshot: Hidden? = null", "internal typealias Hidden = ApiResult<String>\nfun leak(): Hidden = error(\"fixture\")", "internal fun decode() = kotlinx.serialization.json.JsonObject(emptyMap())", "internal fun start(runtime: RunRuntime) = Unit"):
                with self.subTest(module=module, source=source):
                    self.write(path, source + "\n")
                    self.assertTrue(self.codes() & {"forbidden_import", "forbidden_reference", "run_forbidden_capability", "untyped_public_api"})
            (self.root / path).unlink()

    def test_run_data_raw_transport_decoder_and_inferred_json_remain_internal(self):
        path = "feature/run/data/src/commonMain/kotlin/Probe.kt"
        for source in ("class RunReadTransport internal constructor() { internal fun tasks() = api.execute() }", "object RunDataDecoder { fun tasks(data: JsonElement) = data }", "fun tasks(api: ApiClient) = api.execute()", "fun rows() = JsonObject(emptyMap())", "typealias PublicRows = JsonObject", "class ApiRunReadSource { val raw: JsonObject? = null }"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertTrue(self.codes() & {"run_read_api_visibility", "untyped_public_api"})
        self.write(path, "internal class Local { private fun decode(data: JsonElement): JsonObject = error(\"fixture\") }\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_run_domain_finite_schema_rejects_encoded_raw_alias_fields_and_extra_write_port(self):
        path = "feature/run/domain" + ROOT + "feature/runui/RunDataModels.kt"
        for source in ("internal typealias Encoded = String\ndata class RunDisplayField(val label: String, val value: String, val rawPayload: Encoded)", "data class RunRecordItem(val raw: String, val identityKey: String)", "data class UnexpectedDto(val response: String)", "interface RunReadSource { fun punch(body: String): Boolean }"):
            with self.subTest(source=source):
                self.write(path, "package io.github.betterzslf.feature.runui\n" + source + "\n")
                self.assertIn("run_read_api_visibility", self.codes())

    def test_run_ui_rejects_real_ids_domain_models_and_capability_typealias_chains(self):
        path = "feature/run/ui/src/commonMain/kotlin/Probe.kt"
        for source in ("data class Display(val trid: String)", "fun Content(errandScheduleId: String) = Unit", "fun Content(state: RunTaskItem) = Unit", "import io.github.betterzslf.feature.runui.RunRuntime as Hidden\nfun Content(value: Hidden) = Unit", "internal typealias Authority = RunReadSource\ninternal typealias Hidden = Authority\nfun Content(value: Hidden) = Unit", "class Props(val source: () -> RunTaskController)", "fun Content(onOpen: (NavKey) -> Unit) = Unit"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertTrue(self.codes() & {"sensitive_ui_api", "run_forbidden_capability", "forbidden_import"})
        self.write(path, "data class Display(val localKey: String, val title: String, val canOpen: Boolean)\nfun Content(onOpenRow: (localKey: String) -> Unit, onEvent: (RunListUiEvent) -> Unit) = Unit\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_run_write_repository_is_data_owned_and_kernel_stays_confined(self):
        path = "feature/run/data" + ROOT + "data/RunRepository.kt"
        original = (self.root / path).read_text(encoding="utf-8")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.append(path, "fun tasks() = execute()\n")
        self.assertIn("legacy_moved_source", self.codes())
        self.write(path, original)
        for old, new in (("fun punch(parameters: JsonObject)", "fun punch(parameters: JsonObject, extra: Boolean)"),
                         ("private constructor(private val api: ApiClient", "constructor(private val api: ApiClient")):
            with self.subTest(new=new):
                self.write(path, original.replace(old, new))
                self.assertTrue(self.codes() & {"run_repository_api_visibility", "untyped_public_api", "run_read_api_visibility"}, self.codes())
                self.write(path, original)
        self.write(path, original.replace(
            'override fun toString(): String = "RunRepository(bound=${binding != null})"',
            'override fun toString(): String = "RunRepository(bound=${binding != null})"\n    fun ownerNote(): String = "fixture"'))
        self.assertIn("run_repository_api_visibility", self.codes())
        self.write(path, original)
        # A shared copy of the moved repository is a physical-owner regression.
        self.write("shared" + ROOT + "data/RunRepository.kt", "package io.github.betterzslf.data\nclass RunRepository\n")
        result = self.codes()
        self.assertIn("legacy_moved_source", result)
        self.assertIn("duplicate_declaration", result)
        self.write("feature/run/data/src/commonMain/kotlin/Probe.kt", "internal fun kernel(queue: RunUploadQueue) = Unit\n")
        self.assertIn("run_forbidden_capability", self.codes())

    def test_run_runtime_and_location_imports_libraries_and_edges_stay_reviewed(self):
        path = "feature/run/runtime/src/commonMain/kotlin/Probe.kt"
        for source, code in (("import io.ktor.client.HttpClient\n", "forbidden_import"),
                             ("import androidx.compose.runtime.Composable\n", "forbidden_import"),
                             ("fun payload(): JsonObject = error(\"fixture\")\n", "untyped_public_api")):
            with self.subTest(source=source):
                self.write(path, "package io.github.betterzslf.feature.run\n" + source)
                self.assertIn(code, self.codes())
        (self.root / path).unlink()
        probe = "platform/location/src/commonMain/kotlin/Probe.kt"
        self.write(probe, "package io.github.betterzslf.platform\nimport io.ktor.client.HttpClient\n")
        self.assertIn("forbidden_import", self.codes())
        self.write(probe, "fun payload(): JsonObject = error(\"fixture\")\n")
        self.assertIn("untyped_public_api", self.codes())
        (self.root / probe).unlink()
        for module, dependency in (("feature/run/runtime", ":feature:run:presentation"), ("feature/run/runtime", ":shared"),
                                   ("platform/location", ":core:network-session"), ("platform/location", ":feature:run:runtime")):
            with self.subTest(module=module, dependency=dependency):
                self.append(module + "/build.gradle.kts", self.build('implementation(project("' + dependency + '"))\n'))
                self.assertIn("forbidden_project_dependency", self.codes())
        self.append("feature/run/runtime/build.gradle.kts", self.build("implementation(libs.ktor.core)"))
        self.assertIn("forbidden_library_dependency", self.codes())
        self.append("platform/location/build.gradle.kts", self.build("implementation(libs.ktor.core)"))
        self.assertIn("forbidden_library_dependency", self.codes())

    def test_read_only_run_layers_and_location_seam_reject_kernel_and_shared_globals(self):
        for module in ("feature/run/domain", "feature/run/presentation", "feature/run/ui"):
            path = module + "/src/commonMain/kotlin/Probe.kt"
            for token in ("RunRuntime", "RunUploadQueue", "RunSessionCoordinator", "RunRepository", "RUN_PUNCH"):
                with self.subTest(module=module, token=token):
                    self.write(path, "package io.github.betterzslf.feature.run\ninternal fun probe(): String = " + token + ".toString()\n")
                    self.assertIn("run_forbidden_capability", self.codes())
            (self.root / path).unlink()
        # The location module keeps the injected-context seam; shared platform globals stay out.
        self.write("shared/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidPlatform.kt", "package io.github.betterzslf.platform\nobject AndroidPlatform\n")
        location = "platform/location/src/androidMain/kotlin/Probe.kt"
        self.write(location, "package io.github.betterzslf.platform\nimport io.github.betterzslf.platform.AndroidPlatform\n")
        self.assertIn("forbidden_import", self.codes())
        self.write(location, "package io.github.betterzslf.platform\nval context = AndroidPlatform\n")
        self.assertIn("forbidden_reference", self.codes())
        (self.root / location).unlink()

    def test_location_platform_moves_and_kernel_files_have_single_owners(self):
        for old in ("shared/src/androidMain/kotlin/io/github/betterzslf/platform/LocationTracker.android.kt",
                    "shared/src/iosMain/kotlin/io/github/betterzslf/platform/LocationTracker.ios.kt",
                    "shared/src/iosMain/kotlin/io/github/betterzslf/platform/LocationPermission.ios.kt"):
            with self.subTest(old=old):
                self.write(old, "package io.github.betterzslf.platform\nobject Probe\n")
                self.assertIn("legacy_moved_source", self.codes())
                (self.root / old).unlink()
        for missing in ("feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunUploadQueue.kt",
                        "feature/run/data/src/commonMain/kotlin/io/github/betterzslf/data/RunRepository.kt",
                        "platform/location/src/androidMain/kotlin/io/github/betterzslf/platform/LocationTracker.android.kt"):
            with self.subTest(missing=missing):
                (self.root / missing).unlink()
                self.assertIn("missing_moved_source", self.codes())
                destination = self.root / missing
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((Path(__file__).resolve().parents[2] / missing).read_bytes())

    def test_run_preview_source_context_and_lease_freeze_detects_new_authority(self):
        path = "feature/run/api" + ROOT + "feature/run/api/RunPreviewSource.kt"
        self.append(path, "interface Writer { fun punch() }\n")
        self.assertIn("run_preview_api_changed", self.codes())

    def test_run_reverse_gradle_edges_and_production_test_only_presentation_are_distinct(self):
        self.append("feature/run/data/build.gradle.kts", 'kotlin { sourceSets { commonTest.dependencies { implementation(project(":feature:run:presentation")) } } }\n')
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        for module, dependency in (("feature/run/data", ":feature:run:presentation"), ("feature/run/domain", ":core:network-session"), ("feature/run/presentation", ":feature:run:data"), ("feature/run/ui", ":feature:run:data")):
            with self.subTest(module=module):
                self.append(module + "/build.gradle.kts", self.build('implementation(project("' + dependency + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())

    def test_abstract_members_and_bodyless_generic_dto_do_not_swallow_next_modifiers(self):
        self.write("feature/run/domain" + ROOT + "feature/runui/RunDataModels.kt", "package io.github.betterzslf.feature.runui\ndata class RunRows<T>(val items: List<T>, val recognized: Boolean, val warnings: List<String>, val rawSize: Int = items.size,)\ndata class RunReadValue<T>(val value: T?, val recognized: Boolean, val warnings: List<String>)\n")
        self.write("feature/run/domain" + ROOT + "feature/runui/RunReadSources.kt", "package io.github.betterzslf.feature.runui\ninterface RunReadSource {\n val boundOwnerId: String\n fun isCurrentSession(): Boolean\n suspend fun tasks(ownerId: String, offset: Int = 0, limit: Int = 20, isFinish: String = \"0\"): RunReadOutcome<RunRows<RunTaskItem>>\n}\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.append("feature/run/domain" + ROOT + "feature/runui/RunReadSources.kt", "interface Extra { fun raw(): JsonObject }\n")
        self.assertIn("untyped_public_api", self.codes())

    def test_wildcard_field_spelling_does_not_grant_actual_shared_extension_calls(self):
        self.write("shared" + ROOT + "ui/Components.kt", "package io.github.betterzslf.ui\nfun JsonElement.text(name: String): String = name\nfun JsonElement.rows(name: String): List<String> = emptyList()\n")
        path = "feature/run/ui/src/commonMain/kotlin/Probe.kt"
        self.write(path, "import io.github.betterzslf.ui.*\nfun Content(state: Display) { Text(text = state.title); state.rows.forEach { row -> Text(row.title) } }\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.write("feature/run/ui" + ROOT + "ui/run/LocalCopy.kt", "package io.github.betterzslf.ui.run\ninternal fun Notice.text(): String = \"local\"\n")
        self.write(path, "package io.github.betterzslf.ui.run\nimport io.github.betterzslf.ui.*\nfun Content(notice: Notice) = notice.text()\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        for source in ("fun Content(payload: Display) = payload.text(\"name\")", "val leaked = ::rows"):
            with self.subTest(source=source):
                self.write(path, "import io.github.betterzslf.ui.*\n" + source + "\n")
                self.assertIn("forbidden_import", self.codes())

    def test_binding_runtime_keeps_both_legacy_and_owned_overloads_but_no_widened_raw_operation(self):
        path = "feature/schedule/runtime" + ROOT + "feature/schedule/runtime/ScheduleRuntime.kt"
        self.write(path, "package io.github.betterzslf.feature.schedule.runtime\nclass ScheduleRuntime {\nfun captureBindingLease(): ScheduleBindingLease? = null\nfun isBindingLeaseCurrent(lease: ScheduleBindingLease): Boolean = false\nfun beginBindingVerification(lease: ScheduleBindingLease): ScheduleVerificationAttempt? = null\nsuspend fun verifyBinding(account: String, password: String): Boolean = false\nsuspend fun verifyBinding(attempt: ScheduleVerificationAttempt, account: String, password: String): Boolean = false\nfun cancelBindingVerification(attempt: ScheduleVerificationAttempt) = Unit\nsuspend fun confirmBinding(): Boolean = false\nsuspend fun confirmBinding(attempt: ScheduleVerificationAttempt): ScheduleBindingCommit? = null\nfun consumeBindingContinuation(commit: ScheduleBindingCommit): Boolean = false\n}\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.append(path, "fun export(attempt: ScheduleVerificationAttempt): ScheduleVerificationAttempt = attempt\n")
        self.assertIn("schedule_opaque_visibility", self.codes())
        self.write(path, "package io.github.betterzslf.feature.schedule.runtime\nclass ScheduleRuntime { fun confirmBinding(attempt: ScheduleVerificationAttempt, owner: String): ScheduleBindingCommit? = null }\n")
        self.assertIn("schedule_capability_visibility", self.codes())

    def test_binding_opaque_public_constructor_data_serialization_or_getters_are_rejected(self):
        path = "feature/schedule/runtime" + ROOT + "feature/schedule/runtime/ScheduleBindingAuthority.kt"
        for source in ("class ScheduleBindingLease(val owner: String)", "data class ScheduleBindingLease internal constructor(internal val epoch: Long)", "@Serializable class ScheduleBindingLease internal constructor(internal val epoch: Long)", "class ScheduleBindingLease internal constructor(val owner: String)", "class ScheduleBindingLease internal constructor() { val epoch: Long get() = 0 }", "class ScheduleVerificationAttempt internal constructor() { fun copy(): ScheduleVerificationAttempt = this }", "class ScheduleBindingLease internal constructor() { constructor(owner: String) : this() }", "class ScheduleBindingLease internal constructor() { fun getOwner(): String = owner }",  'class ScheduleBindingCommit internal constructor() { override fun toString(): String = owner }'):
            with self.subTest(source=source):
                self.write(path, "package io.github.betterzslf.feature.schedule.runtime\n" + source + "\n")
                self.assertIn("schedule_opaque_visibility", self.codes())

    def test_binding_opaque_alias_chains_cannot_create_public_authority_ports(self):
        path = "feature/schedule/runtime/src/commonMain/kotlin/Probe.kt"
        for source in ("internal typealias First = ScheduleBindingLease\ninternal typealias Second = First\nfun leak(): Second = error(\"fixture\")", "typealias Visible = ScheduleVerificationAttempt", "data class PublicState(val receipt: ScheduleBindingCommit)"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertIn("schedule_opaque_visibility", self.codes())

    def test_binding_ui_exact_credential_slots_accept_safe_display_but_reject_raw_values_and_capabilities(self):
        path = "feature/schedule/ui/src/commonMain/kotlin/Probe.kt"
        for source in ("fun Content(account: String, password: String) = Unit", "import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime as Authority\nfun Content(state: Authority) = Unit", "internal typealias Alias = ScheduleBindingLease\ninternal typealias Chain = Alias\nfun Content(capability: Chain) = Unit", "data class Display(val owner: String, val inputValue: String)", "fun Content(state: ScheduleState) = Unit", "fun ScheduleBindingPageContent(display: ScheduleBindingDisplay, controls: ScheduleBindingControls, onEvent: (ScheduleBindingUiEvent) -> Unit, accountField: @Composable (String) -> Unit, passwordField: @Composable () -> Unit,) = Unit"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertTrue(self.codes() & {"sensitive_ui_api", "schedule_forbidden_capability", "forbidden_import"})
        (self.root / path).unlink()
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_binding_ui_reverse_dependencies_and_duplicate_root_primitives_fail(self):
        self.write("shared" + ROOT + "ui/schedule/Other.kt", "package io.github.betterzslf.ui.schedule\nfun SchedulePanel(content: () -> Unit) = Unit\n")
        self.assertIn("duplicate_declaration", self.codes())
        # The runtime render edge is reviewed wiring now; domain/data/shared and the native
        # platform stay forbidden direct edges even where transitively visible.
        for dependency in (":feature:schedule:domain", ":feature:schedule:data", ":platform:schedule-native", ":shared"):
            with self.subTest(dependency=dependency):
                self.append("feature/schedule/ui/build.gradle.kts", self.build('implementation(project("' + dependency + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())

    def test_schedule_migrated_pages_keep_reviewed_signatures_and_binding_ban(self):
        path = "feature/schedule/ui" + ROOT + "ui/schedule/SchedulePeriodsPage.kt"
        original = (self.root / path).read_text(encoding="utf-8")
        for old, new in (("fun SchedulePeriodsPage(runtime: ScheduleRuntime, onBack: () -> Unit)",
                          "fun SchedulePeriodsPage(runtime: ScheduleRuntime, onBack: () -> Unit, extra: Boolean = false)"),
                         ("fun SchedulePeriodsPage(runtime: ScheduleRuntime", "fun SchedulePeriodsPage(state: ScheduleRuntime")):
            with self.subTest(new=new):
                self.write(path, original.replace(old, new))
                self.assertIn("schedule_page_api_visibility", self.codes())
                self.write(path, original)
        draft = "feature/schedule/ui" + ROOT + "ui/schedule/ScheduleUiModel.kt"
        original = (self.root / draft).read_text(encoding="utf-8")
        for old, new in (("fun accountChanged(value: String)", "fun accountChanged(value: String, confirm: Boolean = false)"),
                         ('override fun toString(): String = "ScheduleBindingDraft(redacted)"',
                          'override fun toString(): String = "ScheduleBindingDraft"'),
                         ('    val account: String = "",', '    val token: String = "",')):
            with self.subTest(new=new):
                self.write(draft, original.replace(old, new))
                self.assertTrue(self.codes() & {"schedule_page_api_visibility", "sensitive_ui_api"}, self.codes())
                self.write(draft, original)
        # A public export outside the reviewed name set fails; internal helpers stay allowed.
        probe = "feature/schedule/ui/src/commonMain/kotlin/Probe.kt"
        self.write(probe, "package io.github.betterzslf.ui.schedule\nfun unreviewedPage() = Unit\n")
        self.assertIn("sensitive_ui_api", self.codes())
        self.write(probe, "package io.github.betterzslf.ui.schedule\ninternal fun localHelper() = Unit\n")
        self.assertNotIn("sensitive_ui_api", self.codes())
        (self.root / probe).unlink()

    def test_schedule_binding_files_keep_full_capability_ban_pages_get_reduced_set(self):
        content = "feature/schedule/ui" + ROOT + "ui/schedule/ScheduleBindingContent.kt"
        original = (self.root / content).read_text(encoding="utf-8")
        self.append(content, "\ninternal fun captureProbe(): String = ScheduleRuntime.toString() + ScheduleBindingDraft().toString()\n")
        self.assertIn("schedule_forbidden_capability", self.codes())
        self.write(content, original)
        # Migrated pages admit state/snapshot/runtime/draft but never opaque or session authority.
        probe = "feature/schedule/ui/src/commonMain/kotlin/Probe.kt"
        for token in ("ScheduleBindingLease", "ScheduleVerificationAttempt", "ScheduleBindingCommit", "ScheduleBindingSession",
                      "AcademicSource", "Session", "NavKey", "Route"):
            with self.subTest(token=token):
                self.write(probe, "package io.github.betterzslf.ui.schedule\ninternal fun probe(): String = " + token + ".toString()\n")
                self.assertIn("schedule_forbidden_capability", self.codes())
        self.write(probe, "package io.github.betterzslf.ui.schedule\ninternal fun probe(): String = ScheduleState.toString() + ScheduleSnapshot.toString() + ScheduleRuntime.toString() + ScheduleBindingDraft.toString()\n")
        self.assertNotIn("schedule_forbidden_capability", self.codes())
        (self.root / probe).unlink()

    def test_schedule_ui_gradle_edges_libraries_and_moves_stay_reviewed(self):
        for dependency in (":feature:schedule:data", ":shared"):
            with self.subTest(dependency=dependency):
                self.append("feature/schedule/ui/build.gradle.kts", self.build('implementation(project("' + dependency + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())
        self.append("feature/schedule/ui/build.gradle.kts", self.build("implementation(libs.ktor.core)"))
        self.assertIn("forbidden_library_dependency", self.codes())
        self.write("shared" + ROOT + "ui/schedule/ScheduleTokens.kt", "package io.github.betterzslf.ui.schedule\nclass ScheduleCoursePalette\n")
        result = self.codes()
        self.assertIn("legacy_moved_source", result)
        self.assertIn("duplicate_declaration", result)
        for missing in ("feature/schedule/ui" + ROOT + "ui/schedule/ScheduleComponents.kt",
                        "feature/schedule/runtime" + ROOT + "ui/schedule/ScheduleNativeOpenTarget.kt",
                        "design-system" + ROOT + "ui/ConfirmDialog.kt"):
            with self.subTest(missing=missing):
                (self.root / missing).unlink()
                self.assertIn("missing_moved_source", self.codes())
                destination = self.root / missing
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((Path(__file__).resolve().parents[2] / missing).read_bytes())

    def test_schedule_runtime_records_and_design_dialog_stay_pinned(self):
        selection = "feature/schedule/runtime" + ROOT + "ui/schedule/ScheduleDetailSelection.kt"
        original = (self.root / selection).read_text(encoding="utf-8")
        for old, new in (("class ScheduleDetailSelection(val termId: String, val courseIds: List<String>, val week: Int)",
                          "class ScheduleDetailSelection(val termId: String, val courseIds: List<String>, val week: Int, val extra: String = \"\")"),
                         ("fun encode(): String", "fun encode(pretty: Boolean = false): String"),
                         ("fun decode(value: String): ScheduleDetailSelection?", "fun decode(value: String, strict: Boolean = true): ScheduleDetailSelection?")):
            with self.subTest(new=new):
                self.write(selection, original.replace(old, new))
                self.assertIn("schedule_runtime_ui_visibility", self.codes())
                self.write(selection, original)
        opener = "feature/schedule/runtime" + ROOT + "ui/schedule/ScheduleNativeOpenTarget.kt"
        original = (self.root / opener).read_text(encoding="utf-8")
        self.write(opener, original.replace("fun scheduleNativeOpenTarget(", "fun scheduleNativeOpenTarget(extra: Boolean = false, "))
        self.assertIn("schedule_runtime_ui_visibility", self.codes())
        self.write(opener, original)
        dialog = "design-system" + ROOT + "ui/ConfirmDialog.kt"
        original = (self.root / dialog).read_text(encoding="utf-8")
        self.write(dialog, original.replace("fun ConfirmDialog(show: Boolean", "fun ConfirmDialog(show: Boolean, destructive: Boolean = false"))
        self.assertIn("design_system_api_visibility", self.codes())
        self.write(dialog, original)

    def test_profile_content_verticals_keep_reviewed_edges_and_imports(self):
        for module, dependency in (("feature/profile/domain", ":feature:profile:data"), ("feature/profile/domain", ":shared"),
                                   ("feature/profile/data", ":feature:profile:presentation"), ("feature/content/domain", ":feature:content:data"),
                                   ("feature/content/presentation", ":feature:content:data"), ("feature/content/data", ":core:text")):
            with self.subTest(module=module, dependency=dependency):
                self.append(module + "/build.gradle.kts", self.build('implementation(project("' + dependency + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())
        self.append("feature/profile/domain/build.gradle.kts", self.build("implementation(libs.ktor.core)"))
        self.assertIn("forbidden_library_dependency", self.codes())
        self.append("feature/content/data/build.gradle.kts", self.build("implementation(libs.serialization.json)"))
        self.assertIn("forbidden_library_dependency", self.codes())
        self.write("feature/profile/domain/src/commonMain/kotlin/Probe.kt", "package io.github.betterzslf.feature.profile\nimport io.ktor.client.HttpClient\nprivate val client: HttpClient? = null\n")
        self.assertIn("forbidden_import", self.codes())
        self.write("feature/profile/presentation/src/commonMain/kotlin/Probe.kt", "package io.github.betterzslf.feature.profile\nimport io.ktor.client.HttpClient\nprivate val client: HttpClient? = null\n")
        self.assertIn("forbidden_import", self.codes())
        self.write("feature/profile/data/src/commonMain/kotlin/Probe.kt", "package io.github.betterzslf.data\nimport io.github.betterzslf.core.net.ApiClient\nfun call(api: ApiClient) = api.execute()\n")
        self.assertIn("untyped_public_api", self.codes())
        for probe in ("feature/profile/domain/src/commonMain/kotlin/Probe.kt", "feature/profile/presentation/src/commonMain/kotlin/Probe.kt", "feature/profile/data/src/commonMain/kotlin/Probe.kt"):
            (self.root / probe).unlink()

    def test_profile_content_moves_have_single_owners(self):
        for old in ("shared" + ROOT + "feature/profile/ProfileModels.kt", "shared" + ROOT + "data/ProfileRepository.kt",
                    "shared" + ROOT + "feature/content/ContentModels.kt", "shared" + ROOT + "data/ContentRepository.kt",
                    "shared" + ROOT + "feature/content/ContentSource.kt"):
            with self.subTest(old=old):
                self.write(old, "package " + ("io.github.betterzslf.data" if "/data/" in old else "io.github.betterzslf.feature." + ("profile" if "profile" in old else "content")) + "\nclass DuplicateProbe\n")
                self.assertIn("legacy_moved_source", self.codes())
                (self.root / old).unlink()
        for missing in ("feature/profile/domain" + ROOT + "feature/profile/ProfileModels.kt",
                        "feature/profile/data" + ROOT + "data/ProfileRepository.kt",
                        "feature/content/presentation" + ROOT + "feature/content/ArticleController.kt",
                        "feature/content/data" + ROOT + "feature/content/RepositoryContentSource.kt"):
            with self.subTest(missing=missing):
                (self.root / missing).unlink()
                self.assertIn("missing_moved_source", self.codes())
                destination = self.root / missing
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((Path(__file__).resolve().parents[2] / missing).read_bytes())

    def test_profile_disclosed_records_and_legacy_headers_stay_pinned(self):
        models = "feature/profile/domain" + ROOT + "feature/profile/ProfileModels.kt"
        original = (self.root / models).read_text(encoding="utf-8")
        for old, new in (("class ProfileOwner private constructor(private val id: String", "class ProfileOwner constructor(private val id: String"),
                         ('override fun toString(): String = "ProfileOwner(redacted)"', 'override fun toString(): String = "ProfileOwner"'),
                         ("fun photo(data: JsonElement, fileUrl: (String) -> String?): ProfilePhotoMetadata?",
                          "fun photo(data: JsonElement, fileUrl: (String) -> String?, extra: Boolean = false): ProfilePhotoMetadata?")):
            with self.subTest(new=new):
                self.write(models, original.replace(old, new))
                self.assertTrue(self.codes() & {"profile_disclosed_api_visibility", "untyped_public_api"}, self.codes())
                self.write(models, original)
        policy = "feature/profile/domain" + ROOT + "feature/profile/AvatarImagePolicy.kt"
        original = (self.root / policy).read_text(encoding="utf-8")
        for old, new in (("class AvatarBytes public constructor(public val encoded: ByteArray", "class AvatarBytes public constructor(val encoded: ByteArray"),
                         ("fun inspect(bytes: ByteArray): AvatarHeaderCheck", "fun inspect(bytes: ByteArray, strict: Boolean = false): AvatarHeaderCheck")):
            with self.subTest(new=new):
                self.write(policy, original.replace(old, new))
                self.assertIn("profile_disclosed_api_visibility", self.codes())
                self.write(policy, original)
        repository = "feature/profile/data" + ROOT + "data/ProfileRepository.kt"
        original = (self.root / repository).read_text(encoding="utf-8")
        self.write(repository, original.replace("fun userInfo() = api.execute(ApiEndpoint.USER_INFO, expectedSession = binding)",
                                                 "fun userInfo(strict: Boolean = false) = api.execute(ApiEndpoint.USER_INFO, expectedSession = binding)"))
        self.assertIn("untyped_public_api", self.codes())
        self.write(repository, original)
        content = "feature/content/domain" + ROOT + "feature/content/ContentModels.kt"
        original = (self.root / content).read_text(encoding="utf-8")
        self.write(content, original.replace('fun article(raw: JsonObject) = ContentItem(raw.text("id")', 'fun article(raw: JsonElement) = ContentItem(raw.text("id")'))
        self.assertIn("untyped_public_api", self.codes())
        self.write(content, original)

    def test_auth_domain_is_pure_and_exports_only_the_finite_login_contract(self):
        path = "feature/auth/domain/src/commonMain/kotlin/Probe.kt"
        for source in ("import io.ktor.client.HttpClient\n", "import io.github.betterzslf.core.net.ApiClient\n",
                       "import androidx.compose.runtime.Composable\n", "fun read(value: Any): String = \"\"\n",
                       "data class ExtraDto(val payload: String)\n", "fun extraHelper(): String = \"fixture\"\n"):
            with self.subTest(source=source):
                self.write(path, source)
                self.assertTrue(self.codes() & {"forbidden_import", "untyped_public_api", "auth_domain_api_visibility"})
        (self.root / path).unlink()

    def test_auth_data_adapter_stays_single_owner_and_has_no_public_session_or_raw_outlet(self):
        base = "feature/auth/data" + ROOT + "feature/auth/"
        path = base + "Probe.kt"
        for source in ("class CampusSessionOutlet { fun clear(store: SessionStore): Boolean = false }",
                       "fun outlet(binding: ApiSessionBinding): Boolean = false",
                       "fun sessionLeak(): Session = error(\"fixture\")",
                       "fun payload(): JsonObject = error(\"fixture\")",
                       "fun call(api: ApiClient) = api.execute()",
                       "class SecondAdapter { fun submit(username: String, password: String): CampusLoginOutcome = error(\"fixture\") }"):
            with self.subTest(source=source):
                self.write(path, "package io.github.betterzslf.feature.auth\n" + source + "\n")
                self.assertTrue(self.codes() & {"auth_data_api_visibility", "untyped_public_api"})
        (self.root / path).unlink()
        original = (self.root / (base + "ApiCampusLoginSource.kt")).read_text(encoding="utf-8")
        for old, new in (("private constructor", "constructor"),
                         ("override fun claim(): Boolean", "fun claim(username: String): Boolean"),
                         ("fun capture(api: ApiClient): CampusLoginSource", "fun capture(api: ApiClient, retry: Boolean = false): CampusLoginSource")):
            with self.subTest(new=new):
                self.write(base + "ApiCampusLoginSource.kt", original.replace(old, new))
                self.assertIn("auth_data_api_visibility", self.codes())
                self.write(base + "ApiCampusLoginSource.kt", original)
        self.write(base + "ApiCampusLoginSource.kt", original.replace("internal fun ApiLoginOutcome.toCampusLoginOutcome", "fun ApiLoginOutcome.toCampusLoginOutcome"))
        self.assertIn("auth_data_api_visibility", self.codes())
        self.write(base + "ApiCampusLoginSource.kt", original)

    def test_auth_submission_and_credentials_never_become_public_presentation_state(self):
        base = "feature/auth/presentation" + ROOT + "feature/auth/"
        submission = base + "CampusLoginPresentation.kt"
        original = (self.root / submission).read_text(encoding="utf-8")
        for old, new in (("class CampusLoginSubmission internal constructor(internal val owner: CampusLoginController, internal val source: CampusLoginSource,)",
                          "class CampusLoginSubmission(val username: String, val password: String,)"),
                         ("class CampusLoginSubmission internal constructor(internal val owner: CampusLoginController, internal val source: CampusLoginSource,)",
                          "data class CampusLoginSubmission internal constructor(internal val owner: CampusLoginController, internal val source: CampusLoginSource,)"),
                         ("internal val source: CampusLoginSource,", "val source: CampusLoginSource,"),
                         ('override fun toString(): String = "CampusLoginSubmission(redacted)"',
                          'override fun toString(): String = "CampusLoginSubmission"'),
                         ("internal var started: Boolean = false", "var started: Boolean = false")):
            with self.subTest(new=new):
                self.write(submission, original.replace(old, new))
                self.assertIn("auth_submission_visibility", self.codes())
                self.write(submission, original)
        path = base + "Probe.kt"
        for source, expected in (("data class CredentialState(val username: String, val password: String)", "auth_credential_visibility"),
                                 ("val storedToken: String? = null", "auth_credential_visibility"),
                                 ("fun getSessionOwner(): String = \"fixture\"", "auth_credential_visibility"),
                                 ("fun consumeAcceptedContinuationOnce(): Boolean = false", None),
                                 ("fun submit(ticket: CampusLoginSubmission, username: String, password: String): Boolean = false", None)):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                if expected:
                    self.assertIn(expected, self.codes())
                else:
                    self.assertNotIn("auth_credential_visibility", self.codes())
        (self.root / path).unlink()

    def test_auth_ui_rejects_authority_transport_and_credential_fields_but_allows_safe_display(self):
        path = "feature/auth/ui/src/commonMain/kotlin/Probe.kt"
        for source in ("import io.github.betterzslf.feature.auth.CampusLoginController\n",
                       "import io.github.betterzslf.feature.auth.ApiCampusLoginSource as Hidden\n",
                       "data class Display(val username: String, val password: String)",
                       "fun Content(state: CampusLoginState) = Unit",
                       "class Props(val controller: CampusLoginController)",
                       "fun Content(value: ApiCampusLoginSource) = Unit",
                       "internal typealias Alias = CampusLoginSubmission\ninternal typealias Chain = Alias\nfun Content(ticket: Chain) = Unit"):
            with self.subTest(source=source):
                self.write(path, source + "\n")
                self.assertTrue(self.codes() & {"forbidden_import", "sensitive_ui_api", "auth_forbidden_capability"})
        (self.root / path).unlink()
        self.write("feature/auth/ui/src/commonMain/kotlin/Safe.kt",
                   "package io.github.betterzslf.ui.auth\n"
                   "data class Controls(val submitEnabled: Boolean, val canBack: Boolean)\n"
                   "fun Content(display: CampusLoginDisplay, controls: CampusLoginControls, onEvent: (AuthLoginUiEvent) -> Unit, accountField: () -> Unit, passwordField: () -> Unit,) = Unit\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_auth_gradle_edges_and_ui_libraries_fail(self):
        for module, dependency in (("feature/auth/domain", ":feature:auth:data"),
                                   ("feature/auth/data", ":feature:auth:presentation"),
                                   ("feature/auth/presentation", ":feature:auth:ui"),
                                   ("feature/auth/ui", ":feature:auth:presentation"),
                                   ("feature/auth/ui", ":core:network-session")):
            with self.subTest(module=module, dependency=dependency):
                self.append(module + "/build.gradle.kts", self.build('implementation(project("' + dependency + '"))'))
                self.assertIn("forbidden_project_dependency", self.codes())
        self.append("feature/auth/ui/build.gradle.kts", self.build('implementation(libs.ktor.core)'))
        self.assertIn("forbidden_library_dependency", self.codes())

    def test_backtochooser_move_keeps_a_single_physical_owner(self):
        self.write("shared" + ROOT + "ui/BackToChooser.kt", "package io.github.betterzslf.ui\nfun BackToChooser(onBack: () -> Unit) = Unit\n")
        self.assertIn("legacy_moved_source", self.codes())
        self.assertIn("duplicate_declaration", self.codes())
        (self.root / ("feature/auth/ui" + ROOT + "ui/BackToChooser.kt")).unlink()
        self.assertIn("missing_moved_source", self.codes())

    def test_legacy_execute_expression_delegation_is_the_reviewed_exact_header(self):
        path = "core/network-session" + ROOT + "core/net/ApiClient.kt"
        original = (self.root / path).read_text(encoding="utf-8")
        reviewed = "    ): ApiResult<JsonElement> = executeWithOptionalLoginGate(endpoint, query, body, expectedSession, loginAttempt = null)"
        self.assertIn(reviewed, original)
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        for old, new in (("loginAttempt = null)", "loginAttempt = attempt)"),
                         ("executeWithOptionalLoginGate(endpoint, query, body, expectedSession, loginAttempt = null)",
                          "executeWithOptionalLoginGate(endpoint, query, body, expectedSession, null)"),
                         ("body: JsonObject? = null,", "body: JsonObject? = JsonObject(emptyMap()),")):
            with self.subTest(new=new):
                self.write(path, original.replace(old, new))
                self.assertIn("untyped_public_api", self.codes())
                self.write(path, original)

    def test_core_text_imports_only_kotlin_standard_library(self):
        path = "core/text/src/commonMain/kotlin/Probe.kt"
        for name in ("androidx.compose.runtime.Composable", "io.ktor.client.HttpClient", "kotlinx.serialization.Serializable", "io.github.betterzslf.data.SchoolRepository"):
            with self.subTest(name=name):
                self.write(path, "import " + name + "\nval label: String = \"value\"\n")
                self.assertIn("forbidden_import", self.codes())

    def test_run_model_rejects_compose_ktor_graph_data_and_shared_business(self):
        for name in ("androidx.compose.runtime.Composable", "io.ktor.client.HttpClient", "io.github.betterzslf.AppGraph", "io.github.betterzslf.data.SchoolRepository", "io.github.betterzslf.feature.run.RunRepository"):
            with self.subTest(name=name):
                self.write(self.probe, "package io.github.betterzslf.feature.run\nimport " + name + "\n")
                self.assertIn("forbidden_import", self.codes())

    def test_ds_rejects_feature_data_graph_but_accepts_text(self):
        path = "design-system/src/commonMain/kotlin/Probe.kt"
        for name in ("io.github.betterzslf.feature.run.GeoPoint", "io.github.betterzslf.data.SchoolRepository", "io.github.betterzslf.AppGraph"):
            with self.subTest(name=name):
                self.write(path, "import " + name + "\n")
                self.assertIn("forbidden_import", self.codes())
        self.write(path, "import io.github.betterzslf.core.text.AppText\nfun value(): String = AppText.LABEL\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_services_presentation_rejects_compose_and_ui_rejects_data(self):
        self.write("feature/services/presentation/src/commonMain/kotlin/Probe.kt", "import androidx.compose.runtime.Composable\n")
        self.assertIn("forbidden_import", self.codes())
        self.write("feature/services/ui/src/commonMain/kotlin/Probe.kt", "import io.github.betterzslf.data.SchoolRepository\n")
        self.assertGreaterEqual(sum(v["code"] == "forbidden_import" for v in self.run_guard().violations), 2)

    def test_run_api_is_pure_typed_and_does_not_allow_shared_domain_json(self):
        path = "feature/run/api/src/commonMain/kotlin/Probe.kt"
        for source, code in (("import androidx.compose.runtime.Composable\n", "forbidden_import"),
                             ("import io.ktor.client.HttpClient\n", "forbidden_import"),
                             ("import io.github.betterzslf.data.SchoolRepository\n", "forbidden_import"),
                             ("fun read(): Any? = null\n", "untyped_public_api")):
            with self.subTest(source=source):
                self.write(path, source)
                self.assertIn(code, self.codes())
        self.write(path, "import io.github.betterzslf.feature.run.GeoPoint\nfun read(): GeoPoint? = null\n")
        self.write("shared/src/commonMain/kotlin/InternalJson.kt", "import kotlinx.serialization.json.JsonObject\nfun decode(): JsonObject = error(\"fixture\")\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_readonly_map_rejects_editing_capabilities_and_private_imports(self):
        path = "platform-ui/readonly-map/src/commonMain/kotlin/Probe.kt"
        for source in ("val editMode: Boolean = true\n", "class DrawPoint\n", "val value = RunMapEditing.script\n"):
            with self.subTest(source=source):
                self.write(path, source)
                self.assertIn("readonly_map_edit_capability", self.codes())
        self.write(path, "import io.github.betterzslf.pro.map.MapEditorModel\n")
        self.assertIn("forbidden_import", self.codes())
        self.write(path, "// DrawPoint/editMode negative fixture data\nval label: String = \"editMode DrawPoint\"\n")
        self.write("platform-ui/readonly-map/src/commonTest/kotlin/Probe.kt", "val editMode = true\nclass DrawPoint\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_same_package_and_qualified_references_do_not_bypass_boundary(self):
        for code in ("val value: RunUploadBatch? = null", "val value = io.github.betterzslf.data.SchoolRepository()", 'val value = "${io.github.betterzslf.data.SchoolRepository()}"'):
            with self.subTest(code=code):
                self.write(self.probe, "package io.github.betterzslf.feature.run\n" + code + "\n")
                self.assertIn("forbidden_reference", self.codes())

    def test_fully_qualified_ui_and_transport_are_rejected_in_pure_module(self):
        for code in ("val value: androidx.compose.ui.Modifier? = null", "val value: io.ktor.client.HttpClient? = null"):
            with self.subTest(code=code):
                self.write(self.probe, code + "\n")
                self.assertIn("forbidden_reference", self.codes())

    def test_comments_and_literals_are_not_imports_or_business_api(self):
        self.write(self.probe, '/* import io.ktor.client.HttpClient /* nested */ */\n// Any JsonObject AppGraph\nval value: String = "Any JsonObject AppGraph io.github.betterzslf.data.SchoolRepository"\n')
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_platform_main_is_checked_and_tests_resources_generated_are_not(self):
        for relative in ("src/commonTest/kotlin/Probe.kt", "src/commonMain/resources/Probe.kt", "build/generated/Probe.kt"):
            self.write("core/run-model/" + relative, 'import io.ktor.client.HttpClient\n$$"""unsupported"""')
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())
        self.write("core/run-model/src/iosMain/kotlin/Probe.kt", "import io.ktor.client.HttpClient\n")
        self.assertIn("forbidden_import", self.codes())

    def test_module_project_edges_are_enforced(self):
        self.write("design-system/build.gradle.kts", self.build('implementation(project(":feature:services:presentation"))'))
        self.assertIn("forbidden_project_dependency", self.codes())
        self.write("core/run-model/build.gradle.kts", self.build('implementation(project(":shared"))'))
        self.assertIn("forbidden_project_dependency", self.codes())

    def test_shared_requires_real_common_main_project_dependencies(self):
        self.write("shared/build.gradle.kts", '// implementation(project(":core:text"))\nkotlin { sourceSets { commonTest.dependencies { implementation(project(":core:text")) } } }\n')
        result = self.run_guard()
        self.assertEqual(sum(v["code"] == "missing_shared_module_dependency" for v in result.violations), 48)

    def test_missing_dependency_is_not_satisfied_by_platform_or_runtime_only(self):
        self.write("shared/build.gradle.kts", 'kotlin { sourceSets { androidMain.dependencies { implementation(project(":core:text")) } commonMain.dependencies { runtimeOnly(project(":core:run-model")) } } }')
        self.assertIn("missing_shared_module_dependency", self.codes())

    def test_dynamic_project_reference_fails_closed(self):
        self.write("design-system/build.gradle.kts", self.build('implementation(project(otherModule))'))
        self.assertIn("unresolved_project_dependency", self.codes())

    def test_pure_module_library_and_plugin_dependencies_are_checked(self):
        self.write("core/run-model/build.gradle.kts", self.build('implementation(libs.ktor.core)\nimplementation(libs.compose.runtime)'))
        self.assertIn("forbidden_library_dependency", self.codes())
        self.append("core/text/build.gradle.kts", self.build('implementation(libs.coroutines.core)'))
        self.assertIn("text_library_dependency", self.codes())
        self.append("feature/services/presentation/build.gradle.kts", 'plugins { id("betterzslf.compose-library") }')
        self.assertIn("forbidden_plugin", self.codes())

    def test_convention_cannot_hide_compose_or_production_libraries(self):
        self.write("build-logic/src/main/groovy/betterzslf.kmp-library.gradle", "plugins { id 'org.jetbrains.compose' }\nkotlin { sourceSets { commonMain.dependencies { implementation 'io.ktor:ktor-client-core:1' } commonTest.dependencies { implementation kotlin('test') } } }\n")
        self.assertIn("forbidden_convention_plugin", self.codes())
        self.assertIn("convention_production_dependency", self.codes())

    def test_source_overlay_and_friend_paths_are_not_module_dependencies(self):
        self.append("shared/build.gradle.kts", 'kotlin { sourceSets { commonMain { kotlin.srcDir("../core/run-model/src/commonMain/kotlin") } } }\n')
        self.assertIn("source_overlay", self.codes())
        self.append("core/text/build.gradle.kts", 'compilerOptions { freeCompilerArgs.add("-Xfriend-paths=../shared/build/classes") }')
        self.assertIn("friend_path_bypass", self.codes())

    def test_moved_whole_file_must_not_remain_under_shared(self):
        self.write("shared" + ROOT + "core/text/AppText.kt", "package io.github.betterzslf.core.text\nobject AppText\n")
        self.assertIn("legacy_moved_source", self.codes())
        self.assertIn("duplicate_declaration", self.codes())

    def test_duplicate_declaration_is_found_when_copy_is_renamed(self):
        self.write("shared/src/androidMain/kotlin/Other.kt", "package io.github.betterzslf.feature.run\ndata class GeoPoint(val x: Double)\n")
        self.assertIn("duplicate_declaration", self.codes())

    def test_moved_wire_adapter_and_components_keep_single_owners(self):
        self.write("core/run-model" + ROOT + "feature/run/RunWireLocation.kt", "package io.github.betterzslf.feature.run\nfun wgs84ToGcj02WireLocation(value: String): String = value\n")
        self.write("shared" + ROOT + "feature/run/RunWireCoordinates.kt", "package io.github.betterzslf.feature.run\ninternal fun RunUploadBatch.toGcj02WireBody(): String = wgs84ToGcj02WireLocation(\"\")\n")
        result = self.codes()
        self.assertIn("legacy_moved_source", result)
        self.assertIn("duplicate_declaration", result)
        (self.root / ("shared" + ROOT + "feature/run/RunWireCoordinates.kt")).unlink()
        self.write("shared" + ROOT + "ui/Components.kt", "package io.github.betterzslf.ui\ninternal fun AsyncContent() = Unit\n")
        self.assertTrue(self.run_guard().ok, self.run_guard().to_dict())

    def test_public_any_and_json_types_are_rejected_in_contract_positions(self):
        declarations = (
            "fun load(value: Any?): String = \"\"",
            "fun load(): Map<String, JsonObject> = error(\"fixture\")",
            "data class Contract(val payload: List<JsonElement>)",
            "interface Port { fun load(): JsonObject }",
            "class Port { val payload: Any? get() = null }",
            "val payload: JsonObject? = null",
            "typealias Payload = Map<String, Any>",
            "fun load(\n value: kotlin.Any\n): Unit = Unit",
            "fun load() = JsonObject(emptyMap())",
        )
        for declaration in declarations:
            with self.subTest(declaration=declaration):
                self.write(self.probe, declaration + "\n")
                self.assertIn("untyped_public_api", self.codes())

    def test_import_alias_and_local_typealias_do_not_hide_json_contract(self):
        self.write(self.probe, "import kotlinx.serialization.json.JsonObject as Payload\ninternal typealias Alias = Payload\nfun value(): Alias = error(\"fixture\")\n")
        self.assertIn("untyped_public_api", self.codes())

    def test_private_internal_and_local_implementation_any_are_not_public_contract(self):
        self.write(self.probe, "private fun hidden(value: Any): Any = value\ninternal class Hidden { val value: Any? = null }\nfun visible(): String { val local: Any = 1; return local.toString() }\n")
        self.assertNotIn("untyped_public_api", self.codes())

    def test_syntax_error_is_located_source_error_not_vacuous_pass(self):
        self.write(self.probe, 'fun invalid() { val x = "unterminated }')
        self.assertIn("source_error", self.codes())

    def test_cli_json_and_plain_exit_status_write_no_reports(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(guard.main(["--root", str(self.root), "--json"]), 0)
        self.assertTrue(json.loads(output.getvalue())["ok"])
        self.write(self.probe, "fun invalid(value: Any) = Unit\n")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(guard.main(["--root", str(self.root)]), 1)
        self.assertFalse((self.root / "reports").exists())


class RepositoryArchitectureTests(unittest.TestCase):
    def test_current_first_batch_is_within_static_boundaries(self):
        root = Path(__file__).resolve().parents[2]
        result = guard.run_guard(root)
        self.assertTrue(result.ok, result.to_dict())


if __name__ == "__main__":
    unittest.main()
