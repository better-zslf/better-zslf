#!/usr/bin/env python3
"""离线首批 V2 架构守卫（stdlib；只读，不执行 Gradle/Kotlin）。

检查已落地的 text/run-model/DS/run-api/services、只读地图与 schedule/home/update。
保留旧 Kotlin 包名，通过声明所属模块与公开 api 边而不是包名判断依赖；shared wire adapter
可以保留，但迁出声明不可重复。只接受显式 project 依赖，不接受 source overlay。
同时禁止公共生产源的 Pro 实现/旧 edition 替身与跨模块 source overlay 回流。
这是保守的词法守卫，不代替 Gradle DAG、ABI/外部 consumer 或 Native 编译验证。
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import hashlib
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from typing import Sequence

from display_text_guard import LexicalError, scan_source


PACKAGE = "io.github.betterzslf"
MODULES = {
    ":core:text": "core/text",
    ":core:run-model": "core/run-model",
    ":core:logging": "core/logging",
    ":core:network-session": "core/network-session",
    ":core:storage": "core/storage",
    ":platform:storage": "platform/storage",
    ":platform:location": "platform/location",
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
}
ALLOWED_PROJECTS = {
    ":core:text": frozenset(),
    ":core:run-model": frozenset({":core:text"}),
    ":core:logging": frozenset(),
    ":core:network-session": frozenset({":core:text", ":core:logging", ":core:storage"}),
    ":core:storage": frozenset(),
    ":platform:storage": frozenset({":core:storage", ":core:text"}),
    # Location capability: expect/actual platform module over run coordinates only.
    ":platform:location": frozenset({":core:run-model", ":core:text"}),
    ":design-system": frozenset({":core:text"}),
    ":feature:run:api": frozenset({":core:run-model"}),
    ":feature:run:domain": frozenset({":core:run-model"}),
    ":feature:run:data": frozenset({":feature:run:domain", ":core:network-session", ":core:text"}),
    ":feature:run:presentation": frozenset({":feature:run:domain", ":core:text"}),
    ":feature:run:ui": frozenset({":feature:run:presentation", ":design-system", ":core:text"}),
    # Run write kernel: reviewed actual Lead build wiring (api exports + implementation seams).
    ":feature:run:runtime": frozenset({":core:run-model", ":platform:location", ":feature:run:data",
                                      ":core:network-session", ":core:storage", ":core:text"}),
    ":feature:services:presentation": frozenset(),
    ":feature:services:ui": frozenset({":design-system", ":feature:services:presentation"}),
    ":platform-ui:readonly-map": frozenset({":core:text", ":core:run-model"}),
    ":feature:schedule:api": frozenset(),
    ":feature:schedule:domain": frozenset({":feature:schedule:api"}),
    ":feature:schedule:data": frozenset({":feature:schedule:domain"}),
    ":feature:home:presentation": frozenset({":feature:schedule:api", ":feature:schedule:domain", ":core:text"}),
    ":feature:home:ui": frozenset({":feature:home:presentation", ":design-system", ":core:text"}),
    ":feature:update:domain": frozenset(),
    ":feature:update:data": frozenset({":feature:update:domain"}),
    ":feature:update:presentation": frozenset({":feature:update:domain", ":core:text"}),
    ":feature:update:ui": frozenset({":feature:update:presentation", ":design-system", ":core:text"}),
    ":feature:accountrecovery:domain": frozenset(),
    ":feature:accountrecovery:data": frozenset({":feature:accountrecovery:domain", ":core:network-session"}),
    ":feature:accountrecovery:presentation": frozenset({":feature:accountrecovery:domain", ":core:text"}),
    ":feature:accountrecovery:ui": frozenset({":feature:accountrecovery:presentation", ":design-system", ":core:text"}),
    ":feature:campusnet:domain": frozenset(),
    ":feature:campusnet:data": frozenset({":feature:campusnet:domain", ":core:network-session"}),
    ":feature:campusnet:presentation": frozenset({":feature:campusnet:domain", ":core:text"}),
    ":feature:campusnet:ui": frozenset({":feature:campusnet:presentation", ":design-system", ":core:text"}),
    ":feature:auth:domain": frozenset(),
    ":feature:auth:data": frozenset({":feature:auth:domain", ":core:network-session"}),
    ":feature:auth:presentation": frozenset({":feature:auth:domain", ":core:text"}),
    ":feature:auth:ui": frozenset({":design-system", ":core:text"}),
    # V2 closing batch: profile/content verticals follow the actual Lead build wiring.
    ":feature:profile:domain": frozenset({":core:network-session", ":core:text"}),
    ":feature:profile:data": frozenset({":feature:profile:domain", ":core:network-session", ":core:text"}),
    ":feature:profile:presentation": frozenset({":feature:profile:domain", ":core:network-session", ":core:text"}),
    ":feature:content:domain": frozenset({":core:network-session", ":core:text"}),
    ":feature:content:data": frozenset({":feature:content:domain", ":core:network-session"}),
    ":feature:content:presentation": frozenset({":feature:content:domain", ":core:network-session", ":core:text"}),
    ":platform:updates": frozenset({":feature:update:domain", ":core:text"}),
    ":platform:schedule-native": frozenset({":feature:schedule:domain", ":core:text", ":platform:storage"}),
    ":feature:schedule:runtime": frozenset({":feature:schedule:domain", ":platform:schedule-native", ":core:text", ":core:storage"}),
    # Remaining schedule UI batch: pages/models read api models and the runtime render state.
    ":feature:schedule:ui": frozenset({":design-system", ":core:text", ":feature:schedule:api", ":feature:schedule:runtime"}),
}
PURE_MODULES = frozenset({":core:text", ":core:run-model", ":core:logging", ":core:storage", ":feature:run:api", ":feature:services:presentation",
                          ":feature:schedule:api", ":feature:schedule:domain", ":feature:home:presentation",
                          ":feature:update:domain", ":feature:update:presentation",
                          ":feature:accountrecovery:domain", ":feature:accountrecovery:presentation",
                          ":feature:campusnet:domain", ":feature:campusnet:presentation", ":feature:run:domain", ":feature:run:presentation",
                          ":feature:auth:domain", ":feature:auth:presentation"})
DATA_MODULES = frozenset({":feature:schedule:data", ":feature:update:data", ":core:network-session",
                          ":feature:accountrecovery:data", ":feature:campusnet:data", ":feature:run:data", ":feature:auth:data",
                          ":feature:profile:data", ":feature:content:data"})
SENSITIVE_UI_MODULES = frozenset({":feature:accountrecovery:ui", ":feature:campusnet:ui", ":feature:run:ui", ":feature:schedule:ui", ":feature:auth:ui"})
TYPED_FLOW_MODULES = frozenset({":feature:accountrecovery:domain", ":feature:accountrecovery:presentation",
                                ":feature:campusnet:domain", ":feature:campusnet:presentation", ":feature:run:domain", ":feature:run:presentation",
                                ":feature:auth:domain", ":feature:auth:presentation"})
# Run write-kernel batch: nine whole files moved out of shared into feature/run/runtime, including
# the former shared business adapter RunWireCoordinates.kt; the repository moved to feature/run/data.
MOVED_FILES = {
    "core/text": ("core/text/AppText.kt",),
    "core/logging": ("core/log/AppLog.kt", "core/log/LogEntry.kt", "core/log/LogRedaction.kt"),
    "core/network-session": ("core/model/Session.kt", "core/net/ApiClient.kt", "core/net/ApiConfig.kt",
                             "core/net/ApiEndpoint.kt", "core/net/ApiSessionBinding.kt", "core/net/QueryParameters.kt",
                             "core/net/UploadFile.kt", "core/result/ApiResult.kt", "core/storage/SessionStore.kt"),
    "core/storage": ("core/storage/KeyValueStore.kt",),
    "core/run-model": ("feature/run/RunGeometry.kt", "feature/run/RunTrack.kt", "platform/RunMapCoordinates.kt"),
    "design-system": ("ui/DesignComponents.kt", "ui/ConfirmationCheckRow.kt", "ui/ConfirmDialog.kt"),
    "platform-ui/readonly-map": ("platform/RunMapPanel.kt", "platform/RunMapWeb.kt", "platform/RunMapBootstrapPolicy.kt"),
    "feature/schedule/api": ("feature/schedule/ScheduleModels.kt",),
    "feature/schedule/domain": ("feature/schedule/AcademicSource.kt", "feature/schedule/ScheduleDates.kt"),
    "feature/schedule/data": ("feature/schedule/AcademicHtmlParsing.kt", "feature/schedule/AcademicEncoding.kt",
                              "feature/schedule/KtorAcademicSource.kt", "feature/schedule/ScheduleWeekParsing.kt"),
    "feature/update/domain": ("feature/update/ProductUpdatePolicy.kt", "feature/update/GithubReleaseModels.kt"),
    "feature/update/data": ("feature/update/GithubReleaseRepository.kt",),
    "feature/update/presentation": ("feature/update/AppUpdateController.kt",),
    "feature/accountrecovery/domain": ("feature/accountrecovery/AccountRecoverySource.kt", "feature/accountrecovery/RecoveryValidation.kt"),
    "feature/accountrecovery/data": ("feature/accountrecovery/ApiAccountRecoverySource.kt",),
    "feature/accountrecovery/presentation": ("feature/accountrecovery/AccountRecoveryController.kt", "feature/accountrecovery/AccountRecoveryBackPolicy.kt", "feature/accountrecovery/AccountRecoveryState.kt", "feature/accountrecovery/RecoveryMessages.kt"),
    "feature/accountrecovery/ui": ("feature/accountrecovery/ui/AccountRecoveryContent.kt", "feature/accountrecovery/ui/RecoveryContentControls.kt"),
    "feature/campusnet/domain": ("feature/campusnet/CampusPortalModel.kt", "feature/campusnet/CampusSources.kt"),
    "feature/campusnet/data": ("data/CampusNetworkRepository.kt", "data/CampusPortalRepository.kt", "feature/campusnet/KtorPortalProbe.kt", "feature/campusnet/CampusDataDecoder.kt", "feature/campusnet/RepositoryCampusSources.kt"),
    "feature/campusnet/presentation": ("feature/campusnet/CampusNetworkController.kt", "feature/campusnet/CampusNetworkModel.kt", "feature/campusnet/CampusPortalController.kt", "feature/campusnet/CampusPortalState.kt"),
    "feature/campusnet/ui": ("ui/campusnet/CampusNetworkContent.kt", "ui/campusnet/CampusNetworkContentControls.kt"),
    "feature/auth/ui": ("ui/BackToChooser.kt",),
    "platform/updates": ("platform/UnavailableNativeUpdateOwner.kt",),
    "feature/schedule/runtime": ("feature/schedule/runtime/ScheduleRuntime.kt", "feature/schedule/runtime/SchedulePersistence.kt", "feature/schedule/runtime/ScheduleState.kt", "feature/schedule/runtime/ScheduleBindingAuthority.kt",
                                 "ui/schedule/ScheduleDetailSelection.kt", "ui/schedule/ScheduleNativeOpenTarget.kt"),
    "feature/schedule/ui": ("ui/schedule/ScheduleBindingContent.kt", "ui/schedule/ScheduleBindingDisplay.kt", "ui/schedule/ScheduleBindingControls.kt", "ui/schedule/ScheduleBindingPrimitives.kt",
                            "ui/schedule/ScheduleTokens.kt", "ui/schedule/ScheduleWeekBoard.kt", "ui/schedule/ScheduleComponents.kt", "ui/schedule/SchedulePeriodsPage.kt",
                            "ui/schedule/ScheduleUiModel.kt", "ui/schedule/ScheduleTermDetailPages.kt", "ui/schedule/ScheduleWidgetPage.kt"),
    "feature/run/domain": ("feature/runui/RunDataModels.kt", "feature/runui/RunReadOutcome.kt", "feature/runui/RunReadSources.kt"),
    "feature/run/data": ("feature/runui/RunDataDecoder.kt", "feature/runui/RunReadIdentity.kt", "feature/runui/RunReadTransport.kt", "feature/runui/ApiRunReadSource.kt", "feature/runui/RunReadProjection.kt", "data/RunRepository.kt"),
    "feature/run/presentation": ("feature/runui/RunTaskController.kt", "feature/runui/RunRecordController.kt", "feature/runui/RunDisplay.kt", "feature/runui/RunReadMessages.kt"),
    "feature/run/ui": ("ui/run/RunTaskListContent.kt", "ui/run/RunRecordsContent.kt", "ui/run/RunListDisplay.kt", "ui/run/RunListControls.kt", "ui/run/RunWidgets.kt", "ui/run/RunWarnings.kt"),
    "platform/schedule-native": ("platform/ScheduleNativeContract.kt", "platform/ScheduleNativePolicy.kt", "platform/UnsupportedNativeScheduleOwner.kt", "feature/schedule/ScheduleLiveState.kt", "schedule/vendor/VendorLivePolicy.kt"),
    "feature/run/runtime": ("feature/run/RunBatcher.kt", "feature/run/RunSessionCoordinator.kt", "feature/run/RunSessionJournal.kt",
                            "feature/run/RunSessionModels.kt", "feature/run/RunUploadQueue.kt", "feature/run/RunUploadWorker.kt",
                            "feature/run/RunWireCoordinates.kt", "feature/runui/RunRuntime.kt", "feature/runui/RunTaskLinks.kt"),
    "feature/profile/domain": ("feature/profile/ProfileModels.kt", "feature/profile/AvatarImagePolicy.kt"),
    "feature/profile/data": ("feature/profile/AvatarByteRepository.kt", "data/ProfileRepository.kt"),
    "feature/profile/presentation": ("feature/profile/ProfileController.kt",),
    "feature/content/domain": ("feature/content/ContentModels.kt", "feature/content/ContentSource.kt"),
    "feature/content/data": ("data/ContentRepository.kt", "feature/content/RepositoryContentSource.kt"),
    "feature/content/presentation": ("feature/content/ArticleController.kt",),
}
SCHEDULE_ANDROID_FILES = (
    "platform/AndroidNativeScheduleOwner.kt", "schedule/ScheduleLiveNotifications.kt", "schedule/ScheduleNativeEngine.kt",
    "schedule/ScheduleReceivers.kt", "schedule/ScheduleWidgetProviders.kt", "schedule/vendor/FlymeLiveRenderer.kt",
    "schedule/vendor/LiveCard.kt", "schedule/vendor/VendorDiagnostics.kt", "schedule/vendor/VendorLiveContract.kt",
    "schedule/vendor/VendorLiveDispatcher.kt", "schedule/vendor/VivoLiveRenderer.kt", "schedule/vendor/XiaomiLiveRenderer.kt",
)
SCHEDULE_RESOURCES = ("drawable/schedule_ic_notification.xml", "drawable/schedule_widget_background.xml",
                      "layout/schedule_widget_next.xml", "layout/schedule_widget_today.xml", "raw/schedule_keep.xml",
                      "values/schedule_colors.xml", "values/schedule_strings.xml", "values/schedule_styles.xml",
                      "values-night/schedule_colors.xml", "xml/schedule_widget_today_info.xml", "xml/schedule_widget_next_info.xml",
                      "drawable/schedule_live_end.xml", "drawable/schedule_live_progress_fill.xml", "drawable/schedule_live_progress_track.xml",
                      "drawable/schedule_live_start.xml", "drawable/schedule_live_tracker.xml", "layout/schedule_flyme_capsule.xml",
                      "layout/schedule_live_card.xml", "values/schedule_live_colors.xml", "values-night/schedule_live_colors.xml")
SCHEDULE_RECEIVERS = frozenset("io.github.betterzslf.schedule." + name for name in (
    "ScheduleAlarmReceiver", "ScheduleDemoTickReceiver", "ScheduleLiveDismissReceiver", "ScheduleReconcileReceiver",
    "ScheduleTodayWidgetProvider", "ScheduleNextWidgetProvider"))

RUNTIME_PUBLIC_HEADERS = {
    "state": "val state: StateFlow<ScheduleState>", "ownerRevision": "val ownerRevision: String",
    "setOwner": "fun setOwner(owner: String?, force: Boolean = false)", "dismissBindingPrompt": "fun dismissBindingPrompt()",
    "reloadFromStorage": "fun reloadFromStorage()", "courseIdForOccurrence": "fun courseIdForOccurrence(id: String): String?",
    "cancelVerification": "fun cancelVerification()", "verifyBinding": "fun verifyBinding(account: String, password: String): Boolean",
    "confirmBinding": "fun confirmBinding(): Boolean", "refresh": "fun refresh(termId: String? = null)",
    "onForeground": "fun onForeground()", "selectWeek": "fun selectWeek(week: Int)", "selectTerm": "fun selectTerm(id: String)",
    "setReminders": "fun setReminders(enabled: Boolean, leadMinutes: Int = 10)",
    "setCapsuleLongText": "fun setCapsuleLongText(long: Boolean): Boolean",
    "updatePeriods": "fun updatePeriods(periods: List<SchedulePeriod>): Boolean", "unbind": "fun unbind(): Boolean",
    "clearForLogout": "fun clearForLogout(): Boolean", "close": "fun close()", "capsuleFailure": "var capsuleFailure: String",
}
# Overloads are individually pinned; these do not add arbitrary runtime/raw API permission.
RUNTIME_PUBLIC_HEADERS = {name: (header,) for name, header in RUNTIME_PUBLIC_HEADERS.items()}
RUNTIME_PUBLIC_HEADERS.update({
    "captureBindingLease": ("fun captureBindingLease(): ScheduleBindingLease?",),
    "isBindingLeaseCurrent": ("fun isBindingLeaseCurrent(lease: ScheduleBindingLease): Boolean",),
    "beginBindingVerification": ("fun beginBindingVerification(lease: ScheduleBindingLease): ScheduleVerificationAttempt?",),
    "verifyBinding": RUNTIME_PUBLIC_HEADERS["verifyBinding"] + ("fun verifyBinding(attempt: ScheduleVerificationAttempt, account: String, password: String): Boolean",),
    "cancelBindingVerification": ("fun cancelBindingVerification(attempt: ScheduleVerificationAttempt)",),
    "confirmBinding": RUNTIME_PUBLIC_HEADERS["confirmBinding"] + ("fun confirmBinding(attempt: ScheduleVerificationAttempt): ScheduleBindingCommit?",),
    "consumeBindingContinuation": ("fun consumeBindingContinuation(commit: ScheduleBindingCommit): Boolean",),
})
SCHEDULE_OPAQUE_TYPES = frozenset({"ScheduleBindingLease", "ScheduleVerificationAttempt", "ScheduleBindingCommit"})
SCHEDULE_UI_CAPABILITIES = SCHEDULE_OPAQUE_TYPES | frozenset({"ScheduleRuntime", "ScheduleState", "ScheduleBindingDraft", "ScheduleBindingSession", "ScheduleSnapshot", "AcademicSource", "StoredSchedule", "Session", "SessionStore", "ApiClient", "NavKey", "Route"})
SCHEDULE_UI_FIELDS = frozenset({"account", "password", "rawAccount", "rawPassword", "draft", "rawDraft", "inputValue", "value", "owner", "ownerId", "ownerRevision", "epoch", "generation", "lease", "attempt", "commit", "runtime", "session", "source", "credentials", "token", "onBound", "onBack"})
SCHEDULE_BINDING_HEADERS = {
    "ScheduleBindingPromptContent": "fun ScheduleBindingPromptContent(visible: Boolean, onEvent: (ScheduleBindingUiEvent) -> Unit)",
    "ScheduleBindingPageContent": "fun ScheduleBindingPageContent(display: ScheduleBindingDisplay, controls: ScheduleBindingControls, onEvent: (ScheduleBindingUiEvent) -> Unit, accountField: @Composable () -> Unit, passwordField: @Composable () -> Unit,)",
    "ScheduleBindingDisplay": 'class ScheduleBindingDisplay(val updatingPassword: Boolean = false, val hasSlot: Boolean = false, val storageFailure: Boolean = false, val testing: Boolean = false, val saving: Boolean = false, val notice: ScheduleBindingNotice = ScheduleBindingNotice.NOT_VERIFIED, val verifiedLabel: String = "", val runtimeMessage: ScheduleBindingMessage? = null,)',
    "ScheduleBindingControls": "class ScheduleBindingControls(val canVerify: Boolean = false, val canConfirm: Boolean = false, val canBack: Boolean = true,)",
    "SchedulePanel": "fun SchedulePanel(content: @Composable ColumnScope.() -> Unit)",
    "ScheduleProgress": "fun ScheduleProgress(message: String)",
}
# The four frozen binding files keep the full capability ban; the migrated pages/models are the
# only reviewed render inputs outside them (owned declarations and state/snapshot/runtime reads).
SCHEDULE_BINDING_UI_FILES = tuple(
    "feature/schedule/ui/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/" + name for name in
    ("ScheduleBindingContent.kt", "ScheduleBindingControls.kt", "ScheduleBindingDisplay.kt", "ScheduleBindingPrimitives.kt"))
SCHEDULE_PAGE_RENDER_CAPABILITIES = frozenset({"ScheduleState", "ScheduleSnapshot", "ScheduleRuntime", "ScheduleBindingDraft"})
# Remaining schedule UI batch: every migrated public page/model keeps its reviewed exact
# signature, token-for-token like SCHEDULE_BINDING_HEADERS; deviations fail closed.
SCHEDULE_PAGE_HEADERS = {
    "ScheduleCoursePalette": ("class ScheduleCoursePalette(val background: Color, val title: Color, val meta: Color,)",),
    "scheduleCoursePalette": ("fun scheduleCoursePalette(course: ScheduleCourse, running: Boolean = false): ScheduleCoursePalette",),
    "scheduleCourseRunning": ("fun scheduleCourseRunning(course: ScheduleCourse, day: Int, periods: List<SchedulePeriod>, nowMinutes: Int?,): Boolean",),
    "scheduleSchoolMinutes": ("fun scheduleSchoolMinutes(nowMillis: Long): Int",),
    "compactPeriods": ("fun compactPeriods(numbers: List<Int>): String",),
    "ScheduleWeekBoard": ("fun ScheduleWeekBoard(snapshot: ScheduleSnapshot, week: Int, periods: List<SchedulePeriod>, nowMillis: Long, hiddenDays: Set<Int> = emptySet(), onOpen: (List<ScheduleCourse>) -> Unit,)",),
    "scheduleVisibleDays": ("fun scheduleVisibleDays(hidden: Set<Int>): List<Int>",),
    "scheduleDisplayClock": ("fun scheduleDisplayClock(): Long",),
    "scheduleSyncTime": ("fun scheduleSyncTime(millis: Long): String",),
    "ScheduleSyncStamp": ("fun ScheduleSyncStamp(millis: Long)",),
    "coursePeriodTimes": ("fun coursePeriodTimes(course: ScheduleCourse, periods: List<SchedulePeriod>): String",),
    "scheduleVisibleTerms": ("fun scheduleVisibleTerms(terms: List<ScheduleTerm>, radius: Int = 1): List<ScheduleTerm>",),
    "ScheduleCourseCard": ("fun ScheduleCourseCard(course: ScheduleCourse, periods: List<SchedulePeriod>, onOpen: () -> Unit)",),
    "scheduleStatusItems": ("fun LazyListScope.scheduleStatusItems(state: ScheduleState, onUpdateCredentials: () -> Unit)",),
    "SchedulePeriodsPage": ("fun SchedulePeriodsPage(runtime: ScheduleRuntime, onBack: () -> Unit)",),
    "ScheduleBody": ("class ScheduleBody",),
    "scheduleBody": ("fun scheduleBody(state: ScheduleState): ScheduleBody",),
    "ScheduleBindingDraft": ('class ScheduleBindingDraft(val account: String = "", val password: String = "", val revision: Long = 0, val verifiedRevision: Long? = null, val verifiedAccount: String? = null,)',),
    "maskedAcademicAccount": ("fun maskedAcademicAccount(account: String): String",),
    "scheduleDayCourses": ("fun scheduleDayCourses(snapshot: ScheduleSnapshot, week: Int, day: Int): List<ScheduleCourse>",),
    "preferScheduleDayList": ("fun preferScheduleDayList(widthDp: Float, fontScale: Float): Boolean",),
    "ScheduleEmpty": ("class ScheduleEmpty",),
    "scheduleEmpty": ("fun scheduleEmpty(snapshot: ScheduleSnapshot, week: Int): ScheduleEmpty",),
    "scheduleCanConcludeDayEmpty": ("fun scheduleCanConcludeDayEmpty(snapshot: ScheduleSnapshot, week: Int, day: Int): Boolean",),
    "scheduleUnplacedCourses": ("fun scheduleUnplacedCourses(snapshot: ScheduleSnapshot): List<ScheduleCourse>",),
    "schedulePeriodIssueRows": ("fun schedulePeriodIssueRows(periods: List<SchedulePeriod>): Set<Int>",),
    "SchedulePeriodEdit": ("class SchedulePeriodEdit(val periods: List<SchedulePeriod>, val revision: Long = 0, val previewRevision: Long? = null, val baseline: List<SchedulePeriod>? = null,)",),
    "ScheduleTermWeekPage": ("fun ScheduleTermWeekPage(runtime: ScheduleRuntime, onBack: () -> Unit)",),
    "ScheduleDetailPage": ("fun ScheduleDetailPage(runtime: ScheduleRuntime, encoded: String, onBack: () -> Unit)",),
    "ScheduleWidgetPage": ("fun ScheduleWidgetPage(runtime: ScheduleRuntime, onBack: () -> Unit)",),
}
# The flanks batch: the runtime-owned selection/opener records and the design-system dialog.
SCHEDULE_RUNTIME_UI_HEADERS = {
    "ScheduleDetailSelection": ("class ScheduleDetailSelection(val termId: String, val courseIds: List<String>, val week: Int)",),
    "ScheduleNativeOpenTarget": ("class ScheduleNativeOpenTarget(val detail: ScheduleDetailSelection? = null)",),
    "scheduleNativeOpenTarget": ("fun scheduleNativeOpenTarget(state: ScheduleState, ownerRevision: String, request: ScheduleNativeOpenRequest, expectedSlot: String, nowMillis: Long,): ScheduleNativeOpenTarget?",),
}
SCHEDULE_DETAIL_SELECTION_HEADERS = {
    "encode": ("fun encode(): String",),
    "decode": ("fun decode(value: String): ScheduleDetailSelection?",),
}
DESIGN_SYSTEM_CONFIRM_DIALOG_PATH = "design-system/src/commonMain/kotlin/io/github/betterzslf/ui/ConfirmDialog.kt"
DESIGN_SYSTEM_CONFIRM_DIALOG_HEADERS = {
    "ConfirmDialog": ("fun ConfirmDialog(show: Boolean, title: String, summary: String, dismiss: () -> Unit, confirm: () -> Unit)",),
}
# The binding form draft is the one reviewed record that may carry account/password input state;
# its member surface is pinned exactly, and its redacted toString is frozen token-for-token.
SCHEDULE_BINDING_DRAFT_HEADERS = {
    "accountChanged": ("fun accountChanged(value: String)",),
    "passwordChanged": ("fun passwordChanged(value: String)",),
    "invalidate": ("fun invalidate()",),
    "acceptVerification": ("fun acceptVerification(startedRevision: Long, serverAccount: String?): ScheduleBindingDraft",),
    "canConfirm": ("fun canConfirm(serverAccount: String?, busy: Boolean): Boolean",),
    "toString": ('fun toString(): String = "ScheduleBindingDraft(redacted)"',),
}
RUN_DOMAIN_HEADERS = {
    "RunDisplayField": "class RunDisplayField(val label: String, val value: String)",
    "RunTaskItem": "class RunTaskItem(val trid: String?, val scheduleId: String?, val title: String, val startText: String?, val endText: String?, val isFinish: String?, val identityKey: String, val safeDisplayFields: List<RunDisplayField> = emptyList(), val warnings: List<String> = emptyList(), val errandId: String? = null, val errandFieldPresent: Boolean = false, val scheduleFieldsPresent: Boolean = false, val minimumDistanceMeters: Double? = null,)",
    "RunTaskDetail": "class RunTaskDetail(val task: RunTaskItem, val checkpoints: List<RunCheckpoint>, val walkPaths: List<List<GeoPoint>>, val minimumDistanceMeters: Double?, val warnings: List<String>, val checkpointNames: Map<String, String> = emptyMap(), val checkpointSourceCount: Int = checkpoints.size,)",
    "RunRecordItem": "class RunRecordItem(val trid: String?, val title: String, val distanceMeters: Double?, val elapsedMillis: Long?, val status: String?, val points: List<GeoPoint>, val safeDisplayFields: List<RunDisplayField>, val warnings: List<String>, val identityKey: String,)",
    "RunRows": "class RunRows<T>(val items: List<T>, val recognized: Boolean, val warnings: List<String>, val rawSize: Int = items.size,)",
    "RunReadValue": "class RunReadValue<T>(val value: T?, val recognized: Boolean, val warnings: List<String>)",
}
RUN_READ_HEADERS = {
    "boundOwnerId": ("val boundOwnerId: String",), "isCurrentSession": ("fun isCurrentSession(): Boolean",),
    "tasks": ('fun tasks(ownerId: String, offset: Int = 0, limit: Int = 20, isFinish: String = "0"): RunReadOutcome<RunRows<RunTaskItem>>',),
    "detail": ("fun detail(errandId: String, ownerId: String, errandScheduleId: String): RunReadOutcome<RunReadValue<RunTaskDetail>>",),
    "records": ("fun records(ownerId: String): RunReadOutcome<RunRows<RunRecordItem>>", "fun records(ownerId: String, errandScheduleId: String, offset: Int = 0, limit: Int = 10): RunReadOutcome<RunRows<RunRecordItem>>"),
    "record": ("fun record(trid: String): RunReadOutcome<RunReadValue<RunRecordItem>>",),
    "track": ("fun track(trid: String): RunReadOutcome<RunRows<GeoPoint>>",),
}
RUN_UI_CAPABILITIES = frozenset({"RunTaskController", "RunRecordController", "RunRuntime", "RunRepository", "RunReadSource", "RunTaskPageSource", "RunRecordPageSource",
                                 "ApiRunReadSource", "RunReadTransport", "RunTaskItem", "RunTaskDetail", "RunRecordItem", "RunTaskListState", "RunRecordListState", "RunPreviewSelection", "RunTaskSelection",
                                 "RunPreviewSource", "RunPreviewLease", "NavKey", "Route", "Session", "ApiClient", "ApiResult", "RunUploadQueue", "RunSessionCoordinator", "RunTracker", "NativeLocationTracker", "RecordedTrack"})
RUN_UI_FIELDS = frozenset({"trid", "errandId", "taskId", "scheduleId", "errandScheduleId", "ownerId", "userId", "serverId", "runtime", "controller", "queue", "writer", "source", "raw", "canonical", "rawJson", "session", "token", "password", "coordinates", "latitude", "longitude"})
# Campus login batch: the login UI renders only the reviewed safe display/controls and the nine
# zero-payload intents; controllers, the opaque ticket, sources, session and transport stay out.
AUTH_UI_CAPABILITIES = frozenset({"CampusLoginController", "CampusLoginSubmission", "CampusLoginSource", "CampusLoginOutcome",
                                  "CampusLoginIssue", "CampusLoginState", "CampusLoginPhase", "ApiCampusLoginSource",
                                  "Session", "SessionStore", "SessionSnapshot", "ApiClient", "ApiResult", "ApiSessionBinding", "HttpClient",
                                  "NavKey", "Route"})
AUTH_UI_FIELDS = frozenset({"username", "rawUsername", "account", "rawAccount", "credential", "attempt", "submission", "source", "controller"})
# Credential-shaped fields never appear in a public presentation record header; the reviewed
# submit(username, password) transit stays a function parameter, never a retained field.
AUTH_PRESENTATION_CREDENTIAL_FIELDS = frozenset({"username", "rawUsername", "password", "rawPassword", "credential", "token", "session", "account", "ticket", "attempt"})
# The domain exports exactly the reviewed finite login contract; no other declaration is public API.
AUTH_DOMAIN_EXPORTS = frozenset({"CampusLoginIssue", "CampusLoginOutcome", "CampusLoginSource",
                                 "campusLoginCredentialsIssue", "normalizeCampusLoginUsername"})
# The captured adapter keeps the domain read interface verbatim plus a redacted toString.
# Signatures only, like RUN_READ_HEADERS: expression bodies are implementation, not contract.
AUTH_SOURCE_HEADERS = {
    "claim": ("fun claim(): Boolean",),
    "isCurrent": ("fun isCurrent(): Boolean",),
    "submit": ("fun submit(username: String, password: String): CampusLoginOutcome",),
    "cancel": ("fun cancel()",),
    "consumeAcceptedContinuation": ("fun consumeAcceptedContinuation(): Boolean",),
    "toString": ("fun toString(): String",),
}
AUTH_SOURCE_COMPANION_HEADERS = {
    "capture": ("fun capture(api: ApiClient): CampusLoginSource",),
}
RAW_TYPES = frozenset({"Any", "Json", "JsonObject", "JsonArray", "JsonElement", "JsonPrimitive", "JsonNull"})
DEPENDENCY_CALLS = frozenset({"api", "implementation", "compileOnly", "runtimeOnly", "compileOnlyApi"})
DECLARATIONS = frozenset({"class", "interface", "object", "fun", "val", "var", "typealias"})
# 按已批准MOVE闭包指定符号；不误禁公共真实GPS、只读importedPath或通用水电目录。
PRO_SYMBOLS = frozenset({
    "BuildEdition", "EditionAccess", "EditionFeatures", "EditionFeatureFlags", "createEditionFeatures",
    "RunMapEditing", "DrawPoint", "editMode", "WaterBleController", "KableWaterBleTransport",
    "WaterHeaterScreen", "WaterController", "WaterProtocol", "BluetoothAccess", "BluetoothPhase",
    "rememberBluetoothPermissionRequester", "createBluetoothAccess", "createWaterEpilogueContext",
    "PickedFile", "rememberFilePicker", "IosBoundedFileReader", "IosFileReadPolicy",
    "RunRouteParser", "RunRouteValidation", "RunDetourPlanner", "RunTrackPreview",
    "RunImportController", "RunManualDraft", "RunImportPage", "RunManualDraftSession", "RunManualDraftSheet",
})
PRO_PACKAGE_PARTS = frozenset({"pro", "water", "waterui"})
RETIRED_PAGE_KINDS = frozenset({"water", "runimport", "runmanual", "runplan", "runsupplement"})
NAVIGATION_PATH = "shared/src/commonMain/kotlin/io/github/betterzslf/navigation/ProductNavigation.kt"
NETWORK_SOURCE = "core/network-session/src/commonMain/kotlin/io/github/betterzslf/"
# Exact legacy transport contracts, not a module-wide raw JSON escape hatch.
LEGACY_JSON_HEADERS = {
    ("core/net/ApiClient.kt", "ApiClient"): 'class ApiClient(httpClient: HttpClient, private val sessionStore: SessionStore, val config: ApiConfig = ApiConfig(), private val json: Json = Json { ignoreUnknownKeys = true },)',
    ("core/net/ApiClient.kt", "execute"): 'fun execute(endpoint: ApiEndpoint, query: JsonObject = JsonObject(emptyMap()), body: JsonObject? = null, expectedSession: ApiSessionBinding? = null,): ApiResult<JsonElement> = executeWithOptionalLoginGate(endpoint, query, body, expectedSession, loginAttempt = null)',
    ("core/net/ApiClient.kt", "upload"): 'fun upload(file: UploadFile, tableId: String, childType: String = "feedbackImage", tablename: String = "sch_feedback",): ApiResult<JsonElement>',
    ("core/model/Session.kt", "Session"): 'class Session(val token: String, val userInfo: JsonObject, val deptInfo: JsonObject, val mapToken: String? = null, val mapWebType: String? = null,)',
    ("core/storage/SessionStore.kt", "SessionStore"): 'class SessionStore(private val store: KeyValueStore, private val json: Json = Json { ignoreUnknownKeys = true },)',
    ("core/net/QueryParameters.kt", "queryParameters"): 'fun queryParameters(vararg values: Pair<String, String?>): JsonObject = buildJsonObject',
}
# The three frozen platform location byte-MOVEs (androidMain/iosMain are outside the
# commonMain-only MOVED_FILES closure): each file keeps exactly one physical owner.
LOCATION_PLATFORM_MOVES = (
    ("platform/location/src/androidMain/kotlin/io/github/betterzslf/platform/LocationTracker.android.kt",
     "shared/src/androidMain/kotlin/io/github/betterzslf/platform/LocationTracker.android.kt"),
    ("platform/location/src/iosMain/kotlin/io/github/betterzslf/platform/LocationTracker.ios.kt",
     "shared/src/iosMain/kotlin/io/github/betterzslf/platform/LocationTracker.ios.kt"),
    ("platform/location/src/iosMain/kotlin/io/github/betterzslf/platform/LocationPermission.ios.kt",
     "shared/src/iosMain/kotlin/io/github/betterzslf/platform/LocationPermission.ios.kt"),
)
# Exact reviewed legacy transport contracts outside core/network-session, like LEGACY_JSON_HEADERS:
# token-exact pins, never module-wide raw JSON escape hatches. The migrated write repository keeps
# its five verbatim-body endpoints; the batch payload keeps its reviewed wire encoding.
RUN_REPOSITORY_PATH = "feature/run/data/src/commonMain/kotlin/io/github/betterzslf/data/RunRepository.kt"
RUNTIME_LEGACY_JSON_HEADERS = {
    (RUN_REPOSITORY_PATH, "punch"): 'fun punch(parameters: JsonObject) = execute(ApiEndpoint.RUN_PUNCH, parameters)',
    (RUN_REPOSITORY_PATH, "addTrack"): 'fun addTrack(body: JsonObject) = execute(ApiEndpoint.RUN_ADD_TRACK, body = body)',
    (RUN_REPOSITORY_PATH, "syncSportData"): 'fun syncSportData(body: JsonObject) = execute(ApiEndpoint.RUN_SYNC, body = body)',
    (RUN_REPOSITORY_PATH, "reportLocation"): 'fun reportLocation(body: JsonObject) = execute(ApiEndpoint.RUN_REPORT, body = body)',
    (RUN_REPOSITORY_PATH, "quickSave"): 'fun quickSave(body: JsonObject) = execute(ApiEndpoint.RUN_QUICK_SAVE, body = body)',
    ("feature/run/runtime/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunBatcher.kt", "toJsonObject"):
        'fun toJsonObject(): JsonObject = buildJsonObject',
}
# The migrated profile/content decoder and source boundaries keep their reviewed exact raw
# transport headers, token-for-token; deviations lose the exemption and fail the raw scan.
PROFILE_CONTENT_LEGACY_JSON_HEADERS = {
    ("feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt", "fields"):
        "fun fields(data: JsonElement): ProfileFields?",
    ("feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt", "statistics"):
        "fun statistics(data: JsonElement): ProfileStatistics?",
    ("feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt", "photo"):
        "fun photo(data: JsonElement, fileUrl: (String) -> String?): ProfilePhotoMetadata?",
    ("feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentSource.kt", "document"):
        "fun document(name: String): ApiResult<JsonElement>",
    ("feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentModels.kt", "article"):
        'fun article(raw: JsonObject) = ContentItem(raw.text("id"), raw.text("title"), raw.text("description"), raw.text("content"), raw.text("creatTime"))',
    ("feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentModels.kt", "notice"):
        'fun notice(raw: JsonObject) = ContentItem(raw.text("id"), raw.text("title"), html = raw.text("content"), created = raw.text("createtime"), isRead = raw.flag("isRead"))',
    ("feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt", "userInfo"):
        "fun userInfo() = api.execute(ApiEndpoint.USER_INFO, expectedSession = binding)",
    ("feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt", "photo"):
        "fun photo() = api.execute(ApiEndpoint.USER_PHOTO, expectedSession = binding)",
    ("feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt", "statistics"):
        "fun statistics() = api.execute(ApiEndpoint.USER_STATISTICS, expectedSession = binding)",
    ("feature/content/data/src/commonMain/kotlin/io/github/betterzslf/data/ContentRepository.kt", "document"):
        'fun document(name: String) = api.execute(ApiEndpoint.DOCUMENT, queryParameters("articleName" to name))',
}
PROFILE_CONTENT_LEGACY_JSON_OWNERS = {
    ("feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt", "fields"): ("ProfileDataDecoder",),
    ("feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt", "statistics"): ("ProfileDataDecoder",),
    ("feature/profile/domain/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt", "photo"): ("ProfileDataDecoder",),
    ("feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentSource.kt", "document"): ("ContentSource",),
    ("feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentModels.kt", "article"): ("ContentItem", "Companion"),
    ("feature/content/domain/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentModels.kt", "notice"): ("ContentItem", "Companion"),
    ("feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt", "userInfo"): ("ProfileRepository",),
    ("feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt", "photo"): ("ProfileRepository",),
    ("feature/profile/data/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt", "statistics"): ("ProfileRepository",),
    ("feature/content/data/src/commonMain/kotlin/io/github/betterzslf/data/ContentRepository.kt", "document"): ("ContentRepository",),
}
# The reviewed profile owner/avatar byte records are pinned exactly (V2 closing disclosures);
# keys are (type-owner chain at the declaration site, declaration name).
PROFILE_DISCLOSED_HEADERS = {
    ((), "ProfileOwner"): ("class ProfileOwner private constructor(private val id: String, private val token: String, private val epoch: Long?)",),
    ((), "AvatarHeader"): ("class AvatarHeader(val width: Int, val height: Int, val format: AvatarFormat)",),
    ((), "AvatarHeaderCheck"): ("interface AvatarHeaderCheck",),
    (("ProfilePhotoMetadata",), "Resource"): ("class Resource public constructor(public val url: String) : ProfilePhotoMetadata",),
    ((), "AvatarBytes"): ("class AvatarBytes public constructor(public val encoded: ByteArray, val width: Int, val height: Int, val format: AvatarFormat)",),
    (("AvatarBytes",), "clear"): ("fun clear()",),
    (("AvatarBytes",), "toString"): ('fun toString(): String = "AvatarBytes(redacted)"',),
    (("ProfileOwner",), "matches"): ("fun matches(session: Session?, revision: Long? = null): Boolean",),
    (("ProfileOwner",), "toString"): ('fun toString(): String = "ProfileOwner(redacted)"',),
    (("AvatarImagePolicy",), "filesBaseUrl"): ("val filesBaseUrl: String",),
    (("AvatarImagePolicy",), "isAllowedUrl"): ("fun isAllowedUrl(url: String): Boolean",),
    (("AvatarImagePolicy",), "inspect"): ("fun inspect(bytes: ByteArray): AvatarHeaderCheck",),
}
# The migrated repository's complete public member contract; anything else fails closed.
# Signatures are compared up to the declaration's "="/"{" boundary like RUN_READ_HEADERS; the
# five transport bodies stay pinned by RUNTIME_LEGACY_JSON_HEADERS over the full header.
RUN_REPOSITORY_HEADERS = {
    "boundOwnerId": ("val boundOwnerId: String?",),
    "boundToCurrentSession": ("fun boundToCurrentSession(): RunRepository?",),
    "punch": ("fun punch(parameters: JsonObject)",),
    "addTrack": ("fun addTrack(body: JsonObject)",),
    "syncSportData": ("fun syncSportData(body: JsonObject)",),
    "reportLocation": ("fun reportLocation(body: JsonObject)",),
    "quickSave": ("fun quickSave(body: JsonObject)",),
    "toString": ('fun toString(): String = "RunRepository(bound=${binding != null})"',),
}



@dataclass(frozen=True)
class Lexeme:
    text: str
    start: int
    kind: str = "code"


def _lex(source: str, *, groovy: bool = False) -> tuple[list[Lexeme], list[Lexeme]]:
    """复用已验证 Kotlin lexer 定位字符串；模板表达式另行扫描依赖。"""
    literals = {} if groovy else {token.start: token for token in scan_source(source)}
    result, embedded = [], []
    i = 0
    while i < len(source):
        if source[i].isspace() or source[i] == "\ufeff":
            i += 1
        elif source.startswith("//", i):
            end = re.search(r"[\r\n]", source[i:])
            i = len(source) if end is None else i + end.start()
        elif source.startswith("/*", i):
            start, depth = i, 1
            i += 2
            while depth and i < len(source):
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            if depth:
                raise ValueError(f"Unterminated comment at offset {start}")
        elif i in literals:
            token = literals[i]
            result.append(Lexeme(token.decoded_literal if not token.holes else "", i, "string"))
            for hole in token.holes:
                if hole.kind == "expression":
                    nested, descendants = _lex(hole.expression_source)
                    embedded.extend(Lexeme(t.text, t.start + hole.expression_start, t.kind) for t in (*nested, *descendants))
            i = token.end
        elif groovy and source[i] in "\"'":
            start, quote = i, source[i]
            i += 1
            value = []
            while i < len(source) and source[i] != quote:
                if source[i] == "\\" and i + 1 < len(source):
                    i += 1
                value.append(source[i])
                i += 1
            if i == len(source):
                raise ValueError(f"Unterminated Gradle string at offset {start}")
            result.append(Lexeme("".join(value), start, "string"))
            i += 1
        elif source[i] == "`":
            end = source.find("`", i + 1)
            if end < 0:
                raise ValueError("Unterminated quoted identifier")
            result.append(Lexeme(source[i + 1:end], i))
            i = end + 1
        else:
            match = re.match(r"[\w$]+|->|::|[^\s]", source[i:])
            result.append(Lexeme(match.group(), i))
            i += len(match.group())
    return result, embedded


def _qualified(tokens: list[Lexeme], start: int) -> tuple[str, int]:
    parts, i = [], start
    while i < len(tokens):
        if tokens[i].kind == "string" or not re.fullmatch(r"[\w$]+|\*", tokens[i].text):
            break
        parts.append(tokens[i].text)
        i += 1
        if i >= len(tokens) or tokens[i].text != ".":
            break
        i += 1
    return ".".join(parts), i


def _package(tokens: list[Lexeme]) -> str:
    for i, token in enumerate(tokens):
        if token.text == "package":
            return _qualified(tokens, i + 1)[0]
    return ""


def _imports(tokens: list[Lexeme]) -> list[tuple[str, str, int]]:
    result = []
    for i, token in enumerate(tokens):
        if token.text == "import":
            name, end = _qualified(tokens, i + 1)
            alias = tokens[end + 1].text if end + 1 < len(tokens) and tokens[end].text == "as" else name.rsplit(".", 1)[-1]
            result.append((name, alias, token.start))
    return result


def _top_level(tokens: list[Lexeme]) -> set[str]:
    """仅提取跨文件 owner 名；重载函数同属一个 owner，不作签名解析。"""
    result, depth, parens = set(), 0, 0
    for i, token in enumerate(tokens):
        if token.kind != "code":
            continue
        if token.text == "(":
            parens += 1
        elif token.text == ")":
            parens -= 1
        elif token.text == "{":
            depth += 1
        elif token.text == "}":
            depth -= 1
        elif depth == 0 and parens == 0 and token.text in DECLARATIONS:
            if token.text == "fun":
                j = i + 1
                while j < len(tokens) and tokens[j].text not in ("(", "{", "=", ";"):
                    j += 1
                if j < len(tokens) and tokens[j].text == "(" and j > i + 1:
                    result.add(tokens[j - 1].text)
            elif i + 1 < len(tokens) and re.fullmatch(r"[A-Za-z_$][\w$]*", tokens[i + 1].text):
                # Anonymous `object : Port` is an expression, not a declaration named ':'.
                result.add(tokens[i + 1].text)
    return result


def _argument_bounds(tokens: list[Lexeme], opening: int) -> tuple[int, int]:
    """Conservative required/total arity; sufficient to separate local zero-arg extensions."""
    closing = _call_end(tokens, opening)
    parts, current, nested = [], [], []
    for token in tokens[opening + 1:closing - 1]:
        if token.text == "," and not nested:
            if current:
                parts.append(current)
            current = []
            continue
        current.append(token.text)
        if token.text in ("(", "[", "{", "<"):
            nested.append(token.text)
        elif token.text in (")", "]", "}", ">") and nested:
            nested.pop()
    if current:
        parts.append(current)
    required = sum("=" not in part and "vararg" not in part for part in parts)
    return required, 1_000_000 if any("vararg" in part for part in parts) else len(parts)


def _function_arities(tokens: list[Lexeme]) -> dict[str, set[tuple[int, int]]]:
    result, depth, parens = {}, 0, 0
    for index, token in enumerate(tokens):
        if token.text == "fun" and depth == 0 and parens == 0:
            opening = index + 1
            while opening < len(tokens) and tokens[opening].text not in ("(", "{", "=", ";"):
                opening += 1
            if opening < len(tokens) and tokens[opening].text == "(" and opening > index + 1:
                result.setdefault(tokens[opening - 1].text, set()).add(_argument_bounds(tokens, opening))
        depth += (token.text == "{") - (token.text == "}")
        parens += (token.text == "(") - (token.text == ")")
    return result


def _blocks(tokens: list[Lexeme]) -> dict[int, tuple[str, ...]]:
    """记录花括号 DSL 上下文；tests 的依赖不误当生产依赖。"""
    contexts, stack, boundary = {}, [], 0
    for i, token in enumerate(tokens):
        if token.text == "{":
            label = " ".join(t.text for t in tokens[max(boundary, i - 5):i] if t.kind == "code")
            stack.append(label)
            boundary = i + 1
        elif token.text == "}":
            if stack:
                stack.pop()
            boundary = i + 1
        contexts[i] = tuple(stack)
    return contexts


def _call_end(tokens: list[Lexeme], opening: int) -> int:
    depth = 0
    for i in range(opening, len(tokens)):
        if tokens[i].text == "(":
            depth += 1
        elif tokens[i].text == ")":
            depth -= 1
            if depth == 0:
                return i + 1
    return len(tokens)


def _project_calls(tokens: list[Lexeme]) -> list[tuple[str | None, int]]:
    result = []
    for i, token in enumerate(tokens):
        if token.kind == "code" and token.text == "project" and i + 1 < len(tokens) and tokens[i + 1].text == "(":
            args = tokens[i + 2:_call_end(tokens, i + 1) - 1]
            if len(args) >= 3 and [t.text for t in args[:2]] == ["path", "="]:
                args = args[2:]
            name = args[0].text if len(args) == 1 and args[0].kind == "string" and args[0].text.startswith(":") else None
            result.append((name, token.start))
    return result


@dataclass(frozen=True)
class GuardResult:
    files_scanned: int
    violations: tuple[dict, ...]
    errors: tuple[dict, ...]

    @property
    def ok(self) -> bool:
        return not self.violations and not self.errors

    def to_dict(self) -> dict:
        return {"ok": self.ok, "files_scanned": self.files_scanned,
                "violations": list(self.violations), "errors": list(self.errors)}


class _Guard:
    def __init__(self, root: Path):
        self.root = root
        self.violations, self.errors = [], []
        self.sources = {}  # path -> (owner, source, main tokens, template tokens)
        self.owners = {}  # FQ top-level declaration -> set[module]
        self.function_arities = {}  # FQ function -> reviewed source-owner/arity pairs
        self.api_edges = {module: set() for module in MODULES}
        self.files_scanned = 0

    def issue(self, code: str, path: str, message: str, source: str = "", offset: int | None = None):
        result = {"code": code, "path": path, "message": message}
        if offset is not None:
            result.update(line=source.count("\n", 0, offset) + 1,
                          column=offset - source.rfind("\n", 0, offset))
        self.violations.append(result)

    def read(self, relative: str, *, groovy: bool = False):
        try:
            path = self.root / relative
            if not path.resolve().is_relative_to(self.root):
                raise ValueError("Source resolves outside repository")
            with path.open(encoding="utf-8", newline="") as stream:
                source = stream.read()
            tokens, embedded = _lex(source, groovy=groovy)
            self.files_scanned += 1
            return source, tokens, embedded
        except (OSError, ValueError, LexicalError) as exc:
            self.errors.append({"code": "source_error", "path": relative, "message": str(exc)})
            return None

    def collect_sources(self):
        for owner, module in {**MODULES, ":shared": "shared"}.items():
            required = self.root / module / "src/commonMain/kotlin"
            if not required.is_dir() and owner != ":platform:storage":
                self.errors.append({"code": "scope_error", "path": required.relative_to(self.root).as_posix(),
                                    "message": "Missing required production Kotlin root"})
            for directory in sorted((self.root / module / "src").glob("*Main/kotlin")):
                for path in sorted(directory.rglob("*.kt")):
                    relative = path.relative_to(self.root).as_posix()
                    item = self.read(relative)
                    if item:
                        source, tokens, embedded = item
                        self.sources[relative] = (owner, source, tokens, embedded)
                        package = _package(tokens)
                        for name in _top_level(tokens):
                            self.owners.setdefault(package + "." + name, set()).add(owner)
                        for name, bounds in _function_arities(tokens).items():
                            self.function_arities.setdefault(package + "." + name, set()).update((owner, low, high) for low, high in bounds)

    def visible_modules(self, module: str) -> set[str]:
        visible = {module, *ALLOWED_PROJECTS[module]}
        pending = list(visible)
        while pending:
            current = pending.pop()
            for exported in self.api_edges.get(current, ()):
                if exported not in visible:
                    visible.add(exported)
                    pending.append(exported)
        return visible

    def source_owner_allowed(self, module: str, name: str, used: set[str] | None = None) -> bool:
        allowed = self.visible_modules(module)
        if name.endswith(".*"):
            prefix = name[:-1]
            candidates = [owners for declaration, owners in self.owners.items()
                          if declaration.startswith(prefix) and "." not in declaration[len(prefix):]
                          and (used is None or declaration[len(prefix):] in used)]
            # Legacy packages can mix domain/presentation/data owners. Wildcards grant
            # nothing by themselves: check the declarations actually used by this file.
            return all(owners <= allowed for owners in candidates)
        candidates = [owners for declaration, owners in self.owners.items()
                      if name == declaration or name.startswith(declaration + ".")]
        return bool(candidates) and all(owners <= allowed for owners in candidates)

    def source_rules(self):
        for path, (module, source, tokens, embedded) in self.sources.items():
            if module == ":shared":
                # 已定跨仓公开合同也不能借 shared compilation 泄漏raw业务载荷。
                if any(marker in path for marker in ("/composition/", "/navigation/")):
                    self.api_rules(path, source, tokens, _imports(tokens))
                continue
            imports = _imports(tokens)
            reference_tokens = tokens + embedded
            # Wildcards expose referenced declarations, not matching local/member field spellings.
            # Types stay conservative; lowercase functions require a call or callable reference.
            used = {token.text for i, token in enumerate(reference_tokens) if token.kind == "code" and (
                token.text[:1].isupper() or i + 1 < len(reference_tokens) and reference_tokens[i + 1].text == "("
                or i > 0 and reference_tokens[i - 1].text == "::")}
            for name, _, offset in imports:
                import_used = used
                if name.endswith(".*"):
                    current_package = _package(tokens) + "."
                    import_used = set(used)
                    for short_name in used:
                        local = {item for item in self.function_arities.get(current_package + short_name, ()) if item[0] == module}
                        external = {item for item in self.function_arities.get(name[:-1] + short_name, ()) if item[0] not in self.visible_modules(module)}
                        if not local or not external:
                            continue
                        calls = [(index, _argument_bounds(reference_tokens, index + 1)[1]) for index, part in enumerate(reference_tokens[:-1]) if part.text == short_name and reference_tokens[index + 1].text == "("]
                        referenced = any(part.text == short_name and index > 0 and reference_tokens[index - 1].text == "::" for index, part in enumerate(reference_tokens))
                        if calls and not referenced and all(any(low <= arity <= high for _, low, high in local) and not any(low <= arity <= high for _, low, high in external) for _, arity in calls):
                            import_used.discard(short_name)
                if name.startswith(PACKAGE + "."):
                    allowed = self.source_owner_allowed(module, name, import_used)
                    if module == ":feature:schedule:runtime" and name.startswith(PACKAGE + ".core."):
                        allowed = name in (PACKAGE + ".core.text.AppText", PACKAGE + ".core.storage.KeyValueStore")
                    if module == ":platform:schedule-native" and name.startswith(PACKAGE + ".core."):
                        allowed = name == PACKAGE + ".core.text.AppText" or name.startswith(PACKAGE + ".core.text.AppText.")
                    if module == ":platform:schedule-native" and name.startswith(PACKAGE + ".platform.") and name.rsplit(".", 1)[-1] in ("createPlatformServices", "AndroidPlatform", "PlatformServices"):
                        allowed = False
                elif module == ":core:text":
                    allowed = name.startswith("kotlin.")
                elif module in PURE_MODULES:
                    allowed = (name.startswith("kotlin.") or name.startswith("kotlinx.serialization.")
                               and ".json" not in name or name.startswith("kotlinx.coroutines.")
                               or name.startswith("kotlinx.datetime."))
                elif module in DATA_MODULES:
                    allowed = not name.startswith(("androidx.compose.", "org.jetbrains.compose.", "top.yukonga.miuix."))
                elif module == ":platform:updates":
                    allowed = name.startswith(("kotlin.", "kotlinx.coroutines.", "android.", "androidx.core.", "java."))
                elif module == ":platform:schedule-native":
                    allowed = name.startswith(("kotlin.", "kotlinx.coroutines.", "kotlinx.datetime.", "kotlinx.serialization.", "android.", "java.", "org.json."))
                elif module == ":feature:schedule:runtime":
                    allowed = name.startswith(("kotlin.", "kotlinx.coroutines.", "kotlinx.datetime.", "kotlinx.serialization."))
                elif module == ":feature:run:runtime":
                    # The write kernel keeps Kotlin/coroutines/serialization only; platform edges
                    # and the repository stay project-owned and owner-checked below.
                    allowed = name.startswith(("kotlin.", "kotlinx.coroutines.", "kotlinx.serialization."))
                elif module == ":platform:location":
                    # expect/actual platform capability: Android/iOS platform APIs plus the reviewed
                    # Compose permission-requester surface; no ktor, no product globals.
                    allowed = name.startswith(("kotlin.", "kotlinx.coroutines.", "kotlinx.cinterop.",
                                                "androidx.compose.", "androidx.activity.", "android.", "platform."))
                elif module in (":feature:profile:domain", ":feature:content:domain"):
                    # Vertical domains read the core session/transport contracts directly; no ktor,
                    # no coroutines beyond the reviewed serialization decoding surface.
                    allowed = name.startswith(("kotlin.", "kotlinx.serialization."))
                elif module in (":feature:profile:presentation", ":feature:content:presentation"):
                    # Presentations stay coroutine-flow projections without ktor or compose.
                    allowed = name.startswith(("kotlin.", "kotlinx.coroutines.", "kotlinx.serialization."))
                elif module == ":platform:storage":
                    allowed = name.startswith(("kotlin.", "android.", "java.", "javax.crypto."))
                else:
                    allowed = not (name.startswith("io.ktor.") or ".graph." in name or ".data." in name)
                if module in SENSITIVE_UI_MODULES and any(marker in name for marker in ("Controller", "Ticket", "RecoveryTarget", "CampusRedirectTarget", "CampusPortalState", "CampusAccountRecord")):
                    allowed = False
                if module == ":feature:run:ui" and any(part in RUN_UI_CAPABILITIES for part in name.split(".")):
                    allowed = False
                if module == ":feature:schedule:ui":
                    # Binding files ban every authority import; migrated pages admit only the four
                    # reviewed render inputs (state/snapshot/runtime/draft) and nothing else.
                    capability_parts = [part for part in name.split(".") if part in SCHEDULE_UI_CAPABILITIES]
                    if capability_parts and (path in SCHEDULE_BINDING_UI_FILES or any(part not in SCHEDULE_PAGE_RENDER_CAPABILITIES for part in capability_parts)):
                        allowed = False
                if module == ":feature:auth:ui" and any(part in AUTH_UI_CAPABILITIES for part in name.split(".")):
                    allowed = False
                if not allowed:
                    self.issue("forbidden_import", path, f"{module} must not import {name}", source, offset)
            reference_tokens = tokens + embedded
            same_package = _package(tokens) + "."
            forbidden_local = {name[len(same_package):] for name, owners in self.owners.items()
                               if name.startswith(same_package) and "." not in name[len(same_package):]
                               and not owners <= self.visible_modules(module)}
            for i, token in enumerate(reference_tokens):
                if token.kind != "code":
                    continue
                if (module == ":feature:schedule:ui" and token.text in SCHEDULE_UI_CAPABILITIES
                        and (path in SCHEDULE_BINDING_UI_FILES or token.text not in SCHEDULE_PAGE_RENDER_CAPABILITIES)):
                    self.issue("schedule_forbidden_capability", path, "Binding content must not capture runtime/state/credential authority", source, token.start)
                if module == ":feature:auth:ui" and token.text in AUTH_UI_CAPABILITIES:
                    self.issue("auth_forbidden_capability", path, "Login content must not capture controller/ticket/session/transport authority", source, token.start)
                if module in (":feature:run:domain", ":feature:run:presentation", ":feature:run:ui") and token.text in ({"ApiClient", "ApiResult", "Session", "SessionStore", "ApiSessionBinding", "RunRuntime", "RunUploadQueue", "RunSessionCoordinator", "RunTracker", "RunRepository", "HttpClient"} | (RUN_UI_CAPABILITIES if module == ":feature:run:ui" else frozenset())):
                    self.issue("run_forbidden_capability", path, f"Run read/render module must not capture {token.text}", source, token.start)
                if module in (":feature:run:domain", ":feature:run:presentation") and token.text in RAW_TYPES - {"Any"}:
                    self.issue("run_forbidden_capability", path, "Run pure layers do not decode or carry JSON, even via fully qualified expressions", source, token.start)
                if module == ":platform-ui:readonly-map" and token.text in ("editMode", "DrawPoint", "RunMapEditing"):
                    self.issue("readonly_map_edit_capability", path, f"Read-only map must not declare/reference {token.text}", source, token.start)
                unqualified_call = (i + 1 < len(reference_tokens) and reference_tokens[i + 1].text == "("
                                    and (i == 0 or reference_tokens[i - 1].text != "."))
                if token.text == "AppGraph" or token.text in forbidden_local and (token.text[:1].isupper() or unqualified_call):
                    self.issue("forbidden_reference", path, f"{module} references non-owned {token.text}", source, token.start)
                if (token.text == "io" and i + 1 < len(reference_tokens) and reference_tokens[i + 1].text == "."
                        and (i == 0 or reference_tokens[i - 1].text != "package")):
                    name, _ = _qualified(reference_tokens, i)
                    in_import = i > 0 and reference_tokens[i - 1].text == "import"
                    core_contracts = (PACKAGE + ".core.text.AppText", PACKAGE + ".core.storage.KeyValueStore") if module == ":feature:schedule:runtime" else (PACKAGE + ".core.text.AppText",)
                    restricted_core = module in (":feature:schedule:runtime", ":platform:schedule-native") and name.startswith(PACKAGE + ".core.") and not any(name == entry or name.startswith(entry + ".") for entry in core_contracts)
                    if name.startswith(PACKAGE + ".") and not in_import and (restricted_core or not self.source_owner_allowed(module, name, used)):
                        self.issue("forbidden_reference", path, f"{module} references {name}", source, token.start)
                    elif name.startswith("io.ktor.") and module not in DATA_MODULES:
                        self.issue("forbidden_reference", path, f"{module} references Ktor", source, token.start)
                if module in PURE_MODULES | DATA_MODULES | {":platform:updates", ":platform:schedule-native", ":feature:schedule:runtime"} and token.text in ("androidx", "org", "top"):
                    name, _ = _qualified(reference_tokens, i)
                    if name.startswith(("androidx.compose.", "org.jetbrains.compose.", "top.yukonga.miuix.")):
                        self.issue("forbidden_reference", path, f"{module} references UI implementation", source, token.start)
            self.api_rules(path, source, tokens, imports)
            if module == ":core:network-session":
                self.session_capability_rules(path, source, tokens)

    def session_capability_rules(self, path, source, tokens):
        """Pin the request capability seam without exposing legacy Session credentials anew."""
        spelling = [t.text for t in tokens]

        def contains(words):
            return any(spelling[index:index + len(words)] == words for index in range(len(spelling)))

        def require(words, message):
            if not contains(words):
                self.issue("session_capability_visibility", path, message)

        if path == NETWORK_SOURCE + "core/net/ApiSessionBinding.kt":
            require(["class", "ApiSessionBinding", "internal", "constructor", "("], "Binding constructor must remain internal")
            require(["private", "val", "authority", ":", "Any"], "Binding authority must remain private")
            require(["private", "val", "snapshot", ":", "SessionSnapshot"], "Binding snapshot must remain private")
            for name in ("matches", "ownerFor", "tokenFor"):
                require(["internal", "fun", name, "("], f"Binding {name} must remain internal")
            for forbidden in ("data", "open", "Serializable", "copy", "component1", "component2"):
                if forbidden in spelling:
                    self.issue("session_capability_visibility", path, f"Opaque binding must not expose {forbidden}")
            # Only the redacted Object method is public on a binding; no new accessor surface.
            for index, token in enumerate(tokens):
                if token.text == "fun" and index + 1 < len(tokens):
                    name = tokens[index + 1].text
                    if name not in ("matches", "ownerFor", "tokenFor", "toString"):
                        self.issue("session_capability_visibility", path, f"Unreviewed binding operation {name}")
        if path == NETWORK_SOURCE + "core/storage/SessionStore.kt":
            require(["internal", "class", "SessionSnapshot", "internal", "constructor", "("], "Snapshot type and constructor must remain internal")
            for name in ("captureSnapshot", "clearIfCurrent"):
                require(["internal", "fun", name, "("], f"SessionStore {name} must remain internal")
        if path == NETWORK_SOURCE + "core/net/ApiClient.kt":
            for name in ("captureSessionBinding", "boundOwnerId", "isBindingCurrent", "clearCurrentBinding"):
                require(["fun", name, "("], f"Reviewed finite operation {name} must remain explicit")
            # No new binding/snapshot-bearing API may be added under another name.
            allowed = {"ApiClient", "captureSessionBinding", "boundOwnerId", "isBindingCurrent", "clearCurrentBinding", "execute"}
            for index, token in enumerate(tokens):
                if token.text not in ("fun", "val", "var"):
                    continue
                name = self.declaration_name(tokens, index)
                if name in allowed or name.startswith("_"):
                    continue
                prefix = [t.text for t in tokens[max(0, index - 3):index]]
                if "internal" in prefix or "private" in prefix:
                    continue
                end = index + 1
                while end < len(tokens) and tokens[end].text not in ("{", "=", ";"):
                    end += 1
                if any(t.text in ("ApiSessionBinding", "SessionSnapshot") for t in tokens[index:end]):
                    self.issue("session_capability_visibility", path, f"Unreviewed binding/snapshot public operation {name}")

    @staticmethod
    def declaration_name(tokens, index):
        if tokens[index].text != "fun":
            return tokens[index + 1].text if index + 1 < len(tokens) else ""
        end = index + 1
        while end < len(tokens) and tokens[end].text not in ("(", "{", "=", ";"):
            end += 1
        return tokens[end - 1].text if end > index + 1 else ""

    @staticmethod
    def legacy_json_header(path, name, header, owners):
        if not path.startswith(NETWORK_SOURCE):
            return False
        suffix = path[len(NETWORK_SOURCE):]
        expected = LEGACY_JSON_HEADERS.get((suffix, name))
        expected_owner = ("ApiClient",) if name in ("execute", "upload") else ()
        if tuple(owners) != expected_owner or expected is None:
            return False
        expected_tokens, _ = _lex(expected)
        return [(t.text, t.kind) for t in header] == [(t.text, t.kind) for t in expected_tokens]

    @staticmethod
    def runtime_legacy_json_header(path, name, header, type_owners):
        """Exact run-batch reviewed transport headers; declaration owners are pinned per site."""
        expected = RUNTIME_LEGACY_JSON_HEADERS.get((path, name))
        if expected is None:
            return False
        expected_owner = ("RunUploadPayload",) if name == "toJsonObject" else ("RunRepository",)
        if tuple(type_owners) != expected_owner:
            return False
        expected_tokens, _ = _lex(expected)
        return [(t.text, t.kind) for t in header] == [(t.text, t.kind) for t in expected_tokens]

    @staticmethod
    def profile_content_legacy_json_header(path, name, header, type_owners):
        """Exact profile/content reviewed transport headers; owners are pinned per (path, name)."""
        expected = PROFILE_CONTENT_LEGACY_JSON_HEADERS.get((path, name))
        if expected is None:
            return False
        if tuple(type_owners) != PROFILE_CONTENT_LEGACY_JSON_OWNERS[(path, name)]:
            return False
        expected_tokens, _ = _lex(expected)
        return [(t.text, t.kind) for t in header] == [(t.text, t.kind) for t in expected_tokens]

    def api_rules(self, path, source, tokens, imports):
        """检查公开声明头/推断初始化；private/internal实现和函数体不作为合同。"""
        raw = set(RAW_TYPES)
        module = next((owner for owner, directory in MODULES.items() if path.startswith(directory + "/")), "")
        if module in (":feature:schedule:runtime", ":platform:schedule-native", ":platform:storage", ":core:storage"):
            raw.update(("ApiResult", "ApiClient", "ApiSessionBinding", "Session", "SessionStore", "SessionSnapshot", "HttpClient",
                        "StoredSchedule", "SchedulePersistence", "ScheduleRevocation", "Verification", "EncryptedStore", "ScheduleNativeReminderPlan"))
        if module in TYPED_FLOW_MODULES:
            raw.update(("ApiResult", "ApiClient", "ApiSessionBinding", "Session", "SessionStore", "SessionSnapshot", "HttpClient"))
        elif module in {":feature:accountrecovery:data", ":feature:campusnet:data", ":feature:run:data"}:
            raw.add("ApiResult")
        if module == ":feature:run:data":
            raw.update(("RunReadTransport", "RunDataDecoder", "RunTaskIdentityRegistry", "RawRunTaskItem", "RawRunRecordItem", "RunReadIdentityRegistry", "ApiSessionBinding", "Session", "SessionStore"))
        if module == ":feature:auth:data":
            # The login adapter owns no transport/store state: the reviewed passive capture
            # factory may name the core client, but no public session/binding outlet exists.
            raw.update(("ApiResult", "ApiSessionBinding", "Session", "SessionStore", "SessionSnapshot", "HttpClient"))
        sensitive = {"AccountRecoveryController", "CampusNetworkController", "CampusPortalController", "RecoveryResetTicket",
                     "RecoveryTarget", "CampusRedirectTarget", "CampusPortalState", "CampusAccountRecord", "CampusAccountsSnapshot"}
        if module == ":feature:run:ui":
            sensitive.update(RUN_UI_CAPABILITIES)
        if module == ":feature:schedule:ui":
            sensitive.update(SCHEDULE_UI_CAPABILITIES)
        if module == ":feature:auth:ui":
            sensitive.update(AUTH_UI_CAPABILITIES)
        opaque_types = set(SCHEDULE_OPAQUE_TYPES)
        opaque_types.update(alias for name, alias, _ in imports if name.rsplit(".", 1)[-1] in opaque_types)
        raw.update(alias for name, alias, _ in imports if name.rsplit(".", 1)[-1] in raw)
        sensitive.update(alias for name, alias, _ in imports if name.rsplit(".", 1)[-1] in sensitive)
        snapshot_types = {"SessionSnapshot", "captureSnapshot", "clearIfCurrent"}
        binding_types = {"ApiSessionBinding"}
        snapshot_types.update(alias for name, alias, _ in imports if name.rsplit(".", 1)[-1] == "SessionSnapshot")
        binding_types.update(alias for name, alias, _ in imports if name.rsplit(".", 1)[-1] == "ApiSessionBinding")
        # 本文件 alias 链也不能把 Json/Any 改个名字后公开。
        changed = True
        while changed:
            changed = False
            for i, token in enumerate(tokens):
                if token.text != "typealias" or i + 2 >= len(tokens):
                    continue
                j = i + 2
                while j < len(tokens) and "\n" not in source[tokens[j - 1].start:tokens[j].start] and tokens[j].text != ";":
                    j += 1
                if any(t.text in raw for t in tokens[i + 2:j]) and tokens[i + 1].text not in raw:
                    raw.add(tokens[i + 1].text)
                    changed = True
                if any(t.text in sensitive for t in tokens[i + 2:j]) and tokens[i + 1].text not in sensitive:
                    sensitive.add(tokens[i + 1].text)
                    changed = True
                if any(t.text in opaque_types for t in tokens[i + 2:j]) and tokens[i + 1].text not in opaque_types:
                    opaque_types.add(tokens[i + 1].text)
                    changed = True
        stack, type_owners, boundary, i = [], [], 0, 0
        while i < len(tokens):
            token = tokens[i]
            if token.text == "}":
                if stack:
                    kind, _ = stack.pop()
                    if kind == "type":
                        type_owners.pop()
                boundary = i + 1
            elif token.text == "{":
                stack.append(("body", False))
                boundary = i + 1
            elif module == ":feature:schedule:runtime" and token.text == "constructor" and type_owners and type_owners[0] in SCHEDULE_OPAQUE_TYPES and not any(kind == "body" for kind, _ in stack):
                if not any(t.text in ("internal", "private") for t in tokens[boundary:i]):
                    self.issue("schedule_opaque_visibility", path, "Opaque secondary constructors must not become public", source, token.start)
            elif token.kind == "code" and token.text in DECLARATIONS and not (i > 0 and tokens[i - 1].text == "::") and not any(kind == "body" for kind, _ in stack):
                modifiers = [t.text for t in tokens[boundary:i]]
                visible = not any(t in ("private", "internal") for t in modifiers) and all(v for _, v in stack)
                j, parens, brackets, defaults = i + 1, 0, 0, 0
                while j < len(tokens):
                    text = tokens[j].text
                    if not parens and not brackets and not defaults and (text in ("{", "=", ";", "}") or token.text in ("val", "var") and text in ("get", "set")):
                        break
                    declaration_modifier = text in ("data", "suspend", "override", "public", "internal", "private", "protected", "sealed", "enum", "open", "abstract", "expect", "actual", "const", "inline", "operator", "infix") and any(part.text in DECLARATIONS for part in tokens[j + 1:j + 4])
                    if j > i + 1 and not parens and not brackets and not defaults and "\n" in source[tokens[j - 1].start:tokens[j].start] and (text in DECLARATIONS or declaration_modifier):
                        break
                    if text == "{" and (parens or defaults):
                        defaults += 1
                    elif text == "}" and defaults:
                        defaults -= 1
                    parens += (text == "(") - (text == ")")
                    brackets += (text == "[") - (text == "]")
                    j += 1
                end = j
                # 推断返回值/属性及typealias的 RHS 也必须保持 typed。
                if j < len(tokens) and tokens[j].text == "=":
                    end = j + 1
                    while end < len(tokens) and "\n" not in source[tokens[end - 1].start:tokens[end].start] and tokens[end].text not in (";", "}", "{"):
                        end += 1
                header = tokens[i:end]
                name = self.declaration_name(tokens, i)
                if token.text == "object" and name == "{" and "companion" in modifiers:
                    name = "Companion"
                if visible and module == ":feature:run:data" and token.text in ("class", "object", "interface", "typealias") and name not in ("ApiRunReadSource", "RunRepository", "Companion"):
                    self.issue("run_read_api_visibility", path, "Data implementation classes/aliases must not become public", source, token.start)
                internal_constructor = token.text == "class" and any(
                    t.text == "constructor" and index > 0 and header[index - 1].text in ("internal", "private")
                    for index, t in enumerate(header))
                if module == ":feature:schedule:runtime" and name in SCHEDULE_OPAQUE_TYPES and token.text == "class":
                    if not internal_constructor or any(word in modifiers for word in ("data", "Serializable", "open", "sealed", "value")) or any(t.text in ("Serializable", "SerialName", "Parcelize") for t in tokens[boundary:i]):
                        self.issue("schedule_opaque_visibility", path, "Binding authorities are final opaque classes with nonpublic constructors, never data/serialized values", source, token.start)
                    constructor_parameters = []
                    opening = next((index for index in range(i + 1, j) if tokens[index].text == "("), j)
                    if opening < j:
                        constructor_parameters = tokens[opening + 1:_call_end(tokens, opening) - 1]
                    for index, part in enumerate(constructor_parameters):
                        if part.text in ("val", "var") and (index == 0 or constructor_parameters[index - 1].text not in ("internal", "private")):
                            self.issue("schedule_opaque_visibility", path, "Opaque constructor fields are not public getters", source, part.start)
                if module == ":feature:auth:presentation" and name == "CampusLoginSubmission" and token.text == "class":
                    if not internal_constructor or any(word in modifiers for word in ("data", "Serializable", "open", "sealed", "value")) or any(t.text in ("Serializable", "SerialName", "Parcelize") for t in tokens[boundary:i]):
                        self.issue("auth_submission_visibility", path, "The submit ticket is a final opaque class with a nonpublic constructor, never a data/serialized value", source, token.start)
                    constructor_parameters = []
                    opening = next((index for index in range(i + 1, j) if tokens[index].text == "("), j)
                    if opening < j:
                        constructor_parameters = tokens[opening + 1:_call_end(tokens, opening) - 1]
                    for index, part in enumerate(constructor_parameters):
                        if part.text in ("val", "var") and (index == 0 or constructor_parameters[index - 1].text not in ("internal", "private")):
                            self.issue("auth_submission_visibility", path, "Ticket constructor fields are not public getters", source, part.start)
                if module == ":feature:profile:domain" and token.text in ("class", "interface", "object", "fun", "val", "var"):
                    # The eleven disclosed owner/avatar symbols keep their reviewed exact shapes,
                    # including the private-constructor opaque owner; redactions are frozen.
                    disclosed = PROFILE_DISCLOSED_HEADERS.get((tuple(type_owners), name))
                    if disclosed is not None:
                        if name == "toString":
                            matched = any([(t.text, t.kind) for t in tokens[i:end]] == [(t.text, t.kind) for t in _lex(choice)[0]] for choice in disclosed)
                        else:
                            matched = any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in disclosed)
                        if not matched:
                            self.issue("profile_disclosed_api_visibility", path, "The disclosed profile owner/avatar symbols keep their reviewed exact signatures", source, token.start)
                signature_visible = visible and not internal_constructor
                if signature_visible:
                    legacy_json = (self.legacy_json_header(path, name, header, type_owners)
                                   or self.runtime_legacy_json_header(path, name, header, type_owners)
                                   or self.profile_content_legacy_json_header(path, name, header, type_owners))
                    if path.startswith(NETWORK_SOURCE):
                        if type_owners == ["ApiSessionBinding"] and (token.text != "fun" or name != "toString"):
                            self.issue("session_capability_visibility", path, f"Opaque binding may not expose public {name}", source, token.start)
                        opening = next((index for index in range(i + 1, j) if tokens[index].text == "("), j)
                        closing = _call_end(tokens, opening) if opening < j else j
                        explicit_type = token.text == "fun" and closing < j and tokens[closing].text == ":"
                        snapshot_header = tokens[i:j] if explicit_type else header
                        if any(t.text in snapshot_types for t in snapshot_header):
                            self.issue("session_capability_visibility", path, f"Public {name} leaks an internal session snapshot", source, token.start)
                        allowed_binding = (path == NETWORK_SOURCE + "core/net/ApiClient.kt"
                                           and type_owners == ["ApiClient"] and name in ("captureSessionBinding", "boundOwnerId", "isBindingCurrent", "clearCurrentBinding", "execute"))
                        binding_declaration = path == NETWORK_SOURCE + "core/net/ApiSessionBinding.kt" and token.text == "class" and name == "ApiSessionBinding"
                        if not binding_declaration and any(t.text in binding_types for t in header) and not allowed_binding:
                            self.issue("session_capability_visibility", path, f"Unreviewed public binding signature {name}", source, token.start)
                    if module == ":feature:run:domain":
                        top_level = set(RUN_DOMAIN_HEADERS) | {"RunReadIssue", "RunReadOutcome", "RunReadSource", "RunTaskPageSource", "RunRecordPageSource"}
                        if not type_owners and name not in top_level:
                            self.issue("run_read_api_visibility", path, "Run domain exports only the reviewed typed records and read ports", source, token.start)
                        expected = RUN_DOMAIN_HEADERS.get(name) if token.text == "class" and not type_owners else None
                        if expected and [t.text for t in tokens[i:j]] != [t.text for t in _lex(expected)[0]]:
                            self.issue("run_read_api_visibility", path, "Run DTO schema is finite: no raw payload/encoded alias field", source, token.start)
                        if type_owners == ["RunReadSource"]:
                            choices = RUN_READ_HEADERS.get(name, ())
                            if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in choices):
                                self.issue("run_read_api_visibility", path, "Run source exposes only the reviewed finite typed reads", source, token.start)
                    if module == ":feature:run:data":
                        if token.text in ("class", "object", "interface", "typealias") and name not in ("ApiRunReadSource", "Companion"):
                            self.issue("run_read_api_visibility", path, "Run data exposes only its finite typed capture factory", source, token.start)
                        if name == "ApiRunReadSource" and token.text == "class" and not internal_constructor:
                            self.issue("run_read_api_visibility", path, "Captured run data source construction must remain private", source, token.start)
                        if not type_owners and token.text in ("fun", "val", "var"):
                            self.issue("run_read_api_visibility", path, "Run data helpers and raw JSON decoding remain internal/private", source, token.start)
                        if type_owners == ["ApiRunReadSource"] and token.text in ("fun", "val", "var"):
                            choices = RUN_READ_HEADERS.get(name, ()) if token.text in ("fun", "val") else ()
                            actual = [t.text for t in tokens[i:j]]
                            normalized = [t.text for t in _lex("fun toString(): String")[0]]
                            allowed = name == "toString" and actual == normalized
                            for choice in choices:
                                expected_tokens = _lex(choice)[0]
                                # Implementations inherit defaults from the domain interface.
                                words, skipping, level = [], False, 0
                                for part in expected_tokens:
                                    if part.text == "=" and level == 1:
                                        skipping = True
                                    elif skipping and part.text in (",", ")"):
                                        skipping = False
                                    if not skipping:
                                        words.append(part.text)
                                    level += (part.text == "(") - (part.text == ")")
                                allowed |= actual == words
                            if not allowed:
                                self.issue("run_read_api_visibility", path, "Captured data source must keep only the domain read interface", source, token.start)
                        elif type_owners == ["ApiRunReadSource", "Companion"]:
                            expected = [t.text for t in _lex("fun capture(api: ApiClient): RunReadSource?")[0]]
                            if [t.text for t in tokens[i:j]] != expected:
                                self.issue("run_read_api_visibility", path, "Run capture is the only public transport-injection seam", source, token.start)
                        if type_owners == ["RunRepository"] and token.text in ("fun", "val", "var"):
                            choices = RUN_REPOSITORY_HEADERS.get(name, ())
                            if name == "toString":
                                matched = any([(t.text, t.kind) for t in tokens[i:end]] == [(t.text, t.kind) for t in _lex(choice)[0]] for choice in choices)
                            else:
                                matched = any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in choices)
                            if not matched:
                                self.issue("run_repository_api_visibility", path, "The legacy write repository exposes only its reviewed five-endpoint binding contract", source, token.start)
                    if module == ":feature:auth:domain" and not type_owners and name not in AUTH_DOMAIN_EXPORTS:
                        self.issue("auth_domain_api_visibility", path, "Auth domain exports only the reviewed finite login contract", source, token.start)
                    if module == ":feature:auth:data":
                        if token.text in ("class", "object", "interface", "typealias") and name not in ("ApiCampusLoginSource", "Companion"):
                            self.issue("auth_data_api_visibility", path, "Auth data exposes only its finite typed capture adapter", source, token.start)
                        if name == "ApiCampusLoginSource" and token.text == "class" and not internal_constructor:
                            self.issue("auth_data_api_visibility", path, "Captured campus login source construction must remain private", source, token.start)
                        if not type_owners and token.text in ("fun", "val", "var"):
                            self.issue("auth_data_api_visibility", path, "Login mapping helpers and raw transport stay internal/private", source, token.start)
                        if type_owners == ["ApiCampusLoginSource"] and token.text in ("fun", "val", "var"):
                            choices = AUTH_SOURCE_HEADERS.get(name, ())
                            if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in choices):
                                self.issue("auth_data_api_visibility", path, "Captured login source keeps only the domain interface and a redacted toString", source, token.start)
                        elif type_owners == ["ApiCampusLoginSource", "Companion"]:
                            choices = AUTH_SOURCE_COMPANION_HEADERS.get(name, ())
                            if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in choices):
                                self.issue("auth_data_api_visibility", path, "Capture is the only public transport-injection seam", source, token.start)
                    if module == ":feature:auth:presentation":
                        if type_owners == ["CampusLoginSubmission"] and token.text in ("fun", "val", "var"):
                            expected = 'fun toString(): String = "CampusLoginSubmission(redacted)"'
                            if name != "toString" or [(t.text, t.kind) for t in tokens[i:end]] != [(t.text, t.kind) for t in _lex(expected)[0]]:
                                self.issue("auth_submission_visibility", path, "The opaque ticket exposes only a redacted toString", source, token.start)
                        if signature_visible:
                            if token.text in ("val", "var") and any(word in name.lower() for word in ("password", "credential", "username", "token", "session", "account")):
                                self.issue("auth_credential_visibility", path, "Presentation never retains or exports credential-shaped state", source, token.start)
                            if token.text == "fun" and name.startswith(("get", "read", "peek", "export")) and any(word in name.lower() for word in ("password", "credential", "username", "token", "session", "account")):
                                self.issue("auth_credential_visibility", path, "Presentation never retains or exports credential-shaped state", source, token.start)
                            if token.text in ("class", "interface", "object") and any(item.text in AUTH_PRESENTATION_CREDENTIAL_FIELDS and index + 1 < len(header) and header[index + 1].text == ":" for index, item in enumerate(header)):
                                self.issue("auth_credential_visibility", path, "Presentation records never retain credential-shaped fields", source, token.start)
                    if module == ":feature:schedule:runtime" and type_owners and type_owners[0] in SCHEDULE_OPAQUE_TYPES:
                        expected_redaction = 'fun toString(): String = "' + type_owners[0] + '(<redacted>)"'
                        if token.text != "fun" or [(t.text, t.kind) for t in header] != [(t.text, t.kind) for t in _lex(expected_redaction)[0]]:
                            self.issue("schedule_opaque_visibility", path, "Opaque binding authorities expose only a redacted toString, no getters/copy/helpers", source, token.start)
                    if module == ":feature:schedule:runtime" and any(t.kind == "code" and t.text in opaque_types for t in tokens[i:j]) and type_owners != ["ScheduleRuntime"]:
                        self.issue("schedule_opaque_visibility", path, "Opaque authorities cross modules only through the reviewed Runtime operations", source, token.start)
                    if module == ":feature:schedule:runtime" and token.text == "typealias" and any(t.text in opaque_types for t in header):
                        self.issue("schedule_opaque_visibility", path, "Opaque authority aliases are not additional public contracts", source, token.start)
                    if module == ":feature:schedule:runtime" and not type_owners and token.text in ("fun", "val", "var") and any(t.text in opaque_types for t in header):
                        self.issue("schedule_opaque_visibility", path, "Binding authority access is confined to the finite Runtime operations", source, token.start)
                    if module == ":feature:schedule:runtime" and type_owners == ["ScheduleRuntime"] and token.text in ("fun", "val", "var"):
                        choices = RUNTIME_PUBLIC_HEADERS.get(name, ())
                        if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(expected)[0]] for expected in choices):
                            self.issue("schedule_capability_visibility", path, "Runtime public credential-operation seam must remain finite and typed", source, token.start)
                    if module in (":feature:schedule:runtime", ":platform:schedule-native", ":platform:storage"):
                        private_helpers = {"scheduleNativeRevisionValid", "scheduleNativePublicationValid", "scheduleNativeCanPublish", "scheduleNativeRedact", "scheduleNativeFutureReminders", "scheduleNativeDeliveryDue", "scheduleNativeDueArmedIds", "UnsupportedScheduleNativeBridge", "VendorLivePolicy"}
                        if (name in private_helpers and not (name == "UnsupportedScheduleNativeBridge" and token.text == "fun") or name.startswith("SCHEDULE_NATIVE_")):
                            self.issue("schedule_capability_visibility", path, "Native policy helpers/constants remain internal", source, token.start)
                        if module == ":feature:schedule:runtime" and name != "needsCredentials" and (token.text in ("val", "var") or token.text == "fun" and name.startswith(("get", "read", "peek", "export"))) and any(word in name.lower() for word in ("password", "credential", "cookie", "token", "stored", "persistence")):
                            self.issue("schedule_capability_visibility", path, "Runtime public state/getters must not expose retained credentials", source, token.start)
                    if module == ":platform:schedule-native" and path.endswith("/platform/ScheduleNativePolicy.kt"):
                        self.issue("schedule_capability_visibility", path, "Policy declarations are implementation-only, including alias exports", source, token.start)
                    if module == ":platform:storage" and not type_owners:
                        expected = _lex("fun newAndroidEncryptedStore(context: Context): KeyValueStore")[0]
                        if [t.text for t in tokens[i:j]] != [t.text for t in expected]:
                            self.issue("schedule_capability_visibility", path, "Encrypted storage has one typed Context-to-KeyValueStore factory", source, token.start)
                    if module == ":feature:schedule:ui":
                        expected = SCHEDULE_BINDING_HEADERS.get(name)
                        if expected and [t.text for t in tokens[i:j]] != [t.text for t in _lex(expected)[0]]:
                            self.issue("sensitive_ui_api", path, "Binding layout/display/slots keep exact finite safe signatures", source, token.start)
                        exported = set(SCHEDULE_BINDING_HEADERS) | set(SCHEDULE_PAGE_HEADERS) | {"ScheduleBindingNotice", "ScheduleBindingMessage", "ScheduleBindingUiEvent"}
                        if not type_owners and name not in exported:
                            self.issue("sensitive_ui_api", path, "Binding UI exports only its reviewed display/content/primitives", source, token.start)
                        if not type_owners and name in SCHEDULE_PAGE_HEADERS:
                            if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in SCHEDULE_PAGE_HEADERS[name]):
                                self.issue("schedule_page_api_visibility", path, "Migrated schedule pages/models keep their reviewed exact signatures", source, token.start)
                    if module == ":feature:schedule:runtime" and not type_owners and name in SCHEDULE_RUNTIME_UI_HEADERS:
                        if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in SCHEDULE_RUNTIME_UI_HEADERS[name]):
                            self.issue("schedule_runtime_ui_visibility", path, "The runtime-owned selection/opener records keep their reviewed signatures", source, token.start)
                    if (module == ":feature:schedule:runtime" and type_owners in (["ScheduleDetailSelection"], ["ScheduleDetailSelection", "Companion"])
                            and token.text in ("fun", "val", "var")):
                        if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in SCHEDULE_DETAIL_SELECTION_HEADERS.get(name, ())):
                            self.issue("schedule_runtime_ui_visibility", path, "The detail selection record exposes only its reviewed local encode/decode", source, token.start)
                    if module == ":design-system" and path == DESIGN_SYSTEM_CONFIRM_DIALOG_PATH and not type_owners:
                        if not any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in DESIGN_SYSTEM_CONFIRM_DIALOG_HEADERS.get(name, ())):
                            self.issue("design_system_api_visibility", path, "The shared confirm dialog keeps its reviewed exact signature", source, token.start)
                    schedule_page_pinned = (module == ":feature:schedule:ui" and not type_owners
                                            and any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in SCHEDULE_PAGE_HEADERS.get(name, ())))
                    if module == ":feature:schedule:ui" and type_owners == ["ScheduleBindingDraft"] and token.text in ("fun", "val", "var"):
                        draft_choices = SCHEDULE_BINDING_DRAFT_HEADERS.get(name, ())
                        schedule_page_pinned = (any([(t.text, t.kind) for t in tokens[i:end]] == [(t.text, t.kind) for t in _lex(choice)[0]] for choice in draft_choices)
                                                if name == "toString" else
                                                any([t.text for t in tokens[i:j]] == [t.text for t in _lex(choice)[0]] for choice in draft_choices))
                        if not schedule_page_pinned:
                            self.issue("schedule_page_api_visibility", path, "The binding form draft exposes only its reviewed member surface", source, token.start)
                    if module in SENSITIVE_UI_MODULES:
                        if any(item.kind == "code" and item.text in sensitive for item in header) and not schedule_page_pinned:
                            self.issue("sensitive_ui_api", path, f"Render contract {name} exposes a controller or sensitive capability", source, token.start)
                        fields = {"phone", "rawPhone", "smsCode", "code", "password", "newPassword", "confirmation", "accountId", "targetId", "nonce", "ticket", "portalUrl", "portalAddress", "rawUrl", "token", "session"}
                        if module == ":feature:run:ui":
                            fields.update(RUN_UI_FIELDS)
                        if module == ":feature:schedule:ui":
                            fields.update(SCHEDULE_UI_FIELDS)
                        if module == ":feature:auth:ui":
                            fields.update(AUTH_UI_FIELDS)
                        if any(item.text in fields and index + 1 < len(header) and header[index + 1].text == ":" for index, item in enumerate(header)) and not schedule_page_pinned:
                            self.issue("sensitive_ui_api", path, f"Render contract {name} exposes an unmasked input or authority field", source, token.start)
                    if module in {":feature:accountrecovery:data", ":feature:campusnet:data", ":feature:run:data", ":feature:auth:data",
                                  ":feature:profile:data", ":feature:content:data"}:
                        opening = next((index for index in range(i + 1, j) if tokens[index].text == "("), j)
                        closing = _call_end(tokens, opening) if opening < j else j
                        explicit_return = token.text == "fun" and closing < j and tokens[closing].text == ":"
                        inferred_wire = not explicit_return and token.text in ("fun", "val", "var") and any(item.text in ("execute", "upload", "JsonObject", "JsonElement", "buildJsonObject", "parseToJsonElement") for item in header)
                        if inferred_wire and not legacy_json:
                            self.issue("untyped_public_api", path, f"Public {name} must not infer a raw transport result", source, token.start)
                    signature_end = end
                    if module in (":feature:schedule:runtime", ":platform:schedule-native", ":platform:storage", ":core:storage") and token.text == "fun":
                        opening = next((index for index in range(i + 1, j) if tokens[index].text == "("), j)
                        closing = _call_end(tokens, opening) if opening < j else j
                        if closing < j and tokens[closing].text == ":":
                            signature_end = j
                    for item in tokens[i + 1:signature_end]:
                        if item.kind == "code" and item.text in raw and not (legacy_json and item.text != "Any"):
                            self.issue("untyped_public_api", path, f"Public contract exposes {item.text}; use a typed business value", source, item.start)
                body_opening = j if j < len(tokens) and tokens[j].text == "{" else end if end < len(tokens) and tokens[end].text == "{" else None
                if body_opening is None and token.text == "fun" and j < len(tokens) and tokens[j].text == "=":
                    # A multiline expression body (e.g. `= when (val result = api.execute...)`)
                    # owns its local declarations too; they are not new public API signatures.
                    parenthesis = bracket = 0
                    candidate = j + 1
                    while candidate < len(tokens):
                        text = tokens[candidate].text
                        if text == "{" and parenthesis == 0 and bracket == 0:
                            body_opening = candidate
                            break
                        if candidate > j + 1 and parenthesis == 0 and bracket == 0 and "\n" in source[tokens[candidate - 1].start:tokens[candidate].start] and (text in DECLARATIONS
                                or text in ("data", "suspend", "override", "public", "internal", "private", "protected", "sealed", "enum", "open", "abstract", "expect", "actual", "const", "inline", "operator", "infix", "companion") and any(part.text in DECLARATIONS for part in tokens[candidate + 1:candidate + 4])):
                            # A modifier-led declaration (suspend fun/companion object) ends the
                            # expression body; claiming its braces would swallow the declaration.
                            break
                        if text in ("}", ";") and parenthesis == 0 and bracket == 0:
                            break
                        parenthesis += (text == "(") - (text == ")")
                        bracket += (text == "[") - (text == "]")
                        candidate += 1
                if body_opening is not None:
                    kind = "type" if token.text in ("class", "interface", "object") else "body"
                    stack.append((kind, visible))
                    if kind == "type":
                        type_owners.append(name)
                    boundary = body_opening + 1
                    i = body_opening
                else:
                    boundary = end
                    i = max(i, end - 1)
            i += 1

    def run_owner_rules(self):
        """The read extraction never moves the five write endpoints or exports raw read methods."""
        writes = {"RUN_PUNCH", "RUN_ADD_TRACK", "RUN_SYNC", "RUN_REPORT", "RUN_QUICK_SAVE", "RunUploadQueue", "RunSessionCoordinator", "RunRuntime", "RunRepository"}
        # The write kernel moved to :feature:run:runtime and the legacy repository to :feature:run:data;
        # the read-only run layers keep the full write-capability ban.
        for path, (module, source, tokens, embedded) in self.sources.items():
            if module in (":feature:run:domain", ":feature:run:presentation", ":feature:run:ui"):
                if any(t.kind == "code" and t.text in writes for t in tokens + embedded):
                    self.issue("run_forbidden_capability", path, "Read-only run modules must not own product write/runtime operations")
            elif module == ":feature:run:data":
                # Outside the reviewed repository file run data stays read-only; inside it the
                # queue/coordinator/runtime kernel remains confined to :feature:run:runtime.
                repository_endpoints = {"RunRepository", "RUN_PUNCH", "RUN_ADD_TRACK", "RUN_SYNC", "RUN_REPORT", "RUN_QUICK_SAVE"}
                banned = writes - repository_endpoints if path == RUN_REPOSITORY_PATH else writes
                if any(t.kind == "code" and t.text in banned for t in tokens + embedded):
                    self.issue("run_forbidden_capability", path, "Run data owns only the reviewed legacy write repository, never the runtime kernel")
            if path == RUN_REPOSITORY_PATH:
                methods = {self.declaration_name(tokens, i) for i, t in enumerate(tokens) if t.text == "fun"}
                if methods & {"tasks", "detail", "records", "record", "track"}:
                    self.issue("legacy_moved_source", path, "The seven raw read entry points have a single internal data owner")
            if path == "shared/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunData.kt":
                self.issue("legacy_moved_source", path, "Decoder belongs only to typed run data, not the product root")
        # Freeze the existing read-only Pro seam; source comments/formatting are not a new API escape.
        freezes = {
            "feature/run/api/src/commonMain/kotlin/io/github/betterzslf/feature/run/api/RunPreviewSource.kt": "4d9e432b475521b8b6ba5347843ace680eb81e6e054b0ff14e288372dc65cec3",
            "feature/run/api/src/commonMain/kotlin/io/github/betterzslf/feature/run/api/RunPreviewContext.kt": "b11a3602b4af1f33591d5def74b51d651b27426b74dbd9bcca432abaf5546353",
        }
        for path, digest in freezes.items():
            target = self.root / path
            if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                self.issue("run_preview_api_changed", path, "Frozen Preview context/selection/source/lease contract changed")

    def location_owner_rules(self):
        """The three frozen platform location implementations keep exactly one physical owner."""
        for new, old in LOCATION_PLATFORM_MOVES:
            if new not in self.sources:
                self.errors.append({"code": "missing_moved_source", "path": new,
                                    "message": "Expected platform location implementation owner is absent"})
            if (self.root / old).exists():
                self.issue("legacy_moved_source", old, "The platform location implementation must not remain in shared")

    def schedule_owner_rules(self):
        """Pin the physical platform closure and finite factory seams, never execute capabilities."""
        prefix = "platform/schedule-native/src/androidMain/kotlin/io/github/betterzslf/"
        for suffix in SCHEDULE_ANDROID_FILES:
            if prefix + suffix not in self.sources:
                self.issue("missing_schedule_owner", prefix + suffix, "Expected schedule Android implementation owner")
            if (self.root / ("shared/src/androidMain/kotlin/io/github/betterzslf/" + suffix)).exists():
                self.issue("legacy_schedule_owner", suffix, "Schedule Android implementation must not remain in shared")
        for suffix in SCHEDULE_RESOURCES:
            new = "platform/schedule-native/src/androidMain/res/" + suffix
            if not (self.root / new).is_file():
                self.issue("missing_schedule_owner", new, "Expected schedule resource owner")
            for owner in ("shared/src/androidMain", "platform/android-host/src/main"):
                old = owner + "/res/" + suffix
                if (self.root / old).exists():
                    self.issue("legacy_schedule_owner", old, "Schedule resources have exactly one capability owner")
        kv = "core/network-session/src/commonMain/kotlin/io/github/betterzslf/core/storage/KeyValueStore.kt"
        if (self.root / kv).exists():
            self.issue("legacy_schedule_owner", kv, "KeyValueStore is owned by core:storage, not network")
        storage = "platform/storage/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidEncryptedStorage.kt"
        if storage not in self.sources:
            self.issue("missing_schedule_owner", storage, "Android encrypted storage factory must have one platform owner")
        manifests = ("platform/schedule-native/src/androidMain/AndroidManifest.xml", "platform/android-host/src/main/AndroidManifest.xml")
        android_name = "{http://schemas.android.com/apk/res/android}name"
        exported = "{http://schemas.android.com/apk/res/android}exported"
        permissions = {"android.permission.POST_PROMOTED_NOTIFICATIONS", "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.RECEIVE_BOOT_COMPLETED"}
        for index, path in enumerate(manifests):
            try:
                root = ET.parse(self.root / path).getroot()
            except (OSError, ET.ParseError) as exc:
                self.issue("schedule_manifest_contract", path, str(exc))
                continue
            actual_permissions = {item.get(android_name) for item in root.findall("uses-permission")}
            activities = [item for item in root.findall("application/activity") if item.get(android_name, "").startswith(PACKAGE + ".schedule.")]
            receivers = [item for item in root.findall("application/receiver") if item.get(android_name, "").startswith(PACKAGE + ".schedule.")]
            if index == 0:
                valid = (actual_permissions == permissions and len(activities) == 1 and activities[0].get(android_name) == PACKAGE + ".schedule.ScheduleNotificationPermissionActivity"
                         and len(receivers) == 6 and {item.get(android_name) for item in receivers} == SCHEDULE_RECEIVERS
                         and all(item.get(exported) == "false" for item in activities + receivers))
                if not valid:
                    self.issue("schedule_manifest_contract", path, "Native manifest owns exactly 1 activity, 6 non-exported receivers and 3 schedule permissions")
            elif actual_permissions & permissions or activities or receivers:
                self.issue("schedule_manifest_contract", path, "Schedule declarations must not be duplicated in android-host")
        for path, (module, source, tokens, _) in self.sources.items():
            if module not in (":feature:schedule:runtime", ":platform:schedule-native", ":platform:storage", ":core:storage"):
                continue
            used = {t.text for t in tokens if t.kind == "code"}
            if used & {"AppGraph", "AndroidPlatform", "createPlatformServices", "PlatformServices", "ApiClient", "SessionStore", "Session", "ApiSessionBinding", "SessionSnapshot", "HttpClient"}:
                self.issue("schedule_forbidden_capability", path, "Storage/native/runtime must not capture product globals or HTTP/session authority")
            if module == ":feature:schedule:runtime" and "createScheduleNativeBridge" in used:
                self.issue("schedule_forbidden_capability", path, "Runtime requires an explicitly injected native bridge")
            if module == ":core:storage" and any(name.startswith(PACKAGE + ".") or not name.startswith("kotlin.") for name, _, _ in _imports(tokens)):
                self.issue("schedule_forbidden_capability", path, "Pure storage contract uses Kotlin standard library only")

    def duplicate_rules(self):
        for name, owners in sorted(self.owners.items()):
            if len(owners) > 1 and owners & MODULES.keys():
                self.issue("duplicate_declaration", "shared/src", f"{name} has multiple production owners: {', '.join(sorted(owners))}")
        for module, suffixes in MOVED_FILES.items():
            for suffix in suffixes:
                new = f"{module}/src/commonMain/kotlin/io/github/betterzslf/{suffix}"
                if new not in self.sources:
                    self.errors.append({"code": "missing_moved_source", "path": new, "message": "Expected first-batch physical owner is absent"})
                old = f"shared/src/commonMain/kotlin/io/github/betterzslf/{suffix}"
                if (self.root / old).exists():
                    self.issue("legacy_moved_source", old, "Whole-file extraction must have a single physical owner")

    def public_rules(self):
        """检查公共生产闭包；不读取工作区私仓、缓存、test负向fixture或迁移route字符串。"""
        sources = dict(self.sources)
        for directory in ("androidApp/src/main/kotlin", "platform/android-host/src/main/kotlin"):
            for path in sorted((self.root / directory).rglob("*.kt")):
                relative = path.relative_to(self.root).as_posix()
                item = self.read(relative)
                if item:
                    source, tokens, embedded = item
                    sources[relative] = (":android-product", source, tokens, embedded)
        for path, (_, source, tokens, embedded) in sources.items():
            if any(part in ("proMain", "standardMain") for part in Path(path).parts):
                self.issue("edition_source_overlay", path, "Public production must not retain source-selected edition substitutes")
            package = _package(tokens)
            if package.startswith(PACKAGE + ".") and set(package[len(PACKAGE) + 1:].split(".")) & PRO_PACKAGE_PARTS:
                self.issue("private_production_package", path, f"Private implementation package remains public: {package}")
            for name, _, offset in _imports(tokens):
                if (name.startswith(("com.juul.kable.", "platform.CoreBluetooth."))
                        or name.startswith(PACKAGE + ".") and set(name[len(PACKAGE) + 1:].split(".")) & PRO_PACKAGE_PARTS):
                    self.issue("private_production_import", path, f"Public production imports private implementation {name}", source, offset)
            reference_tokens = tokens + embedded
            for i, token in enumerate(reference_tokens):
                if token.kind != "code":
                    continue
                if token.text in PRO_SYMBOLS:
                    self.issue("private_production_symbol", path, f"Public production must not own/reference {token.text}", source, token.start)
                if (token.text in ("Water", "ManualRoute") and i and reference_tokens[i - 1].text in ("object", ".")):
                    self.issue("private_text_catalog", path, f"Private {token.text} catalog belongs to its product", source, token.start)
            for i, token in enumerate(tokens):
                if token.kind == "string" and token.text in RETIRED_PAGE_KINDS:
                    # 只有精确导航迁移denylist允许旧私有key，不是全文件/字符串豁免。
                    prefix = [t.text for t in tokens[max(0, i - 4):i]]
                    route_registration = any(name in prefix for name in ("Page", "entry", "ContributedAction", "ContributedService"))
                    if route_registration:
                        self.issue("private_route_registration", path, "Retired private page keys must not register public feature entries", source, token.start)
            if path == NAVIGATION_PATH:
                self.navigation_migration_rules(path, source, tokens)
            if path.startswith("platform-ui/readonly-map/"):
                for token in tokens:
                    if token.kind == "string" and any(marker in token.text for marker in ("post('draw'", "function clicked(event)", "state.editMode")):
                        self.issue("readonly_map_draw_script", path, "Read-only map must not embed private drawing script", source, token.start)
        updater = "platform/updates/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidNativeUpdateOwner.kt"
        if updater not in self.sources:
            self.errors.append({"code": "missing_moved_source", "path": updater, "message": "Android updater must have one platform-owned implementation"})
        for obsolete in ("shared/src/androidMain/AndroidManifest.xml", "shared/src/androidMain/res/xml/better_zslf_update_paths.xml"):
            if (self.root / obsolete).exists():
                self.issue("legacy_moved_source", obsolete, "Updater manifest/XML are platform-owned, not shared compilation overlays")
        # 公共产品manifest/plist只检查存在的真实文件，不要求私有产品配置副本。
        for path in ("androidApp/src/pro/AndroidManifest.xml",):
            if (self.root / path).exists():
                self.issue("private_product_overlay", path, "Private product manifest must be owned outside the public product")
        for path in ("settings.gradle.kts", "build.gradle.kts", "androidApp/build.gradle.kts"):
            if not (self.root / path).exists():
                continue
            item = self.read(path)
            if not item:
                continue
            source, tokens, _ = item
            self.no_overlay(path, source, tokens)
            if path == "settings.gradle.kts":
                for i, token in enumerate(tokens):
                    if token.text == "includeBuild" and i + 2 < len(tokens):
                        if tokens[i + 2].kind != "string" or tokens[i + 2].text != "build-logic":
                            self.issue("private_included_build", path, "Public settings may include its build-logic only, not a discovered private build", source, token.start)

    def navigation_migration_rules(self, path, source, tokens):
        for i, token in enumerate(tokens):
            if token.text != "retiredPrivatePages":
                continue
            if i and tokens[i - 1].text == "val":
                if i + 3 >= len(tokens) or [t.text for t in tokens[i + 1:i + 4]] != ["=", "setOf", "("]:
                    self.issue("navigation_migration_contract", path, "Retired keys must remain an explicit closed denylist", source, token.start)
                    continue
                end = _call_end(tokens, i + 3)
                values = {t.text for t in tokens[i + 4:end - 1] if t.kind == "string"}
                if values != RETIRED_PAGE_KINDS:
                    self.issue("navigation_migration_contract", path, "Retired keys must match the reviewed migration-only set", source, token.start)
            else:
                previous = tokens[i - 1].text if i else ""
                if previous != "in" or i < 2 or tokens[i - 2].text != "!":
                    self.issue("navigation_migration_contract", path, "Retired private keys may only be used for negative admission", source, token.start)

    def no_overlay(self, path, source, tokens):
        for i, token in enumerate(tokens):
            if token.kind == "code" and token.text in ("srcDir", "srcDirs", "setSrcDirs", "proOnlyMainSources", "proOnlyTestSources", "isProEdition"):
                self.issue("source_overlay", path, "Public modules must compile their owned source roots, not source selection/overlay", source, token.start)
            if token.kind == "code" and token.text == "exclude" and i and tokens[i - 1].text == ".":
                # Android packaging excludes are resources, not Kotlin compilation inputs.
                if "kotlin" in [t.text for t in tokens[max(0, i - 3):i]]:
                    self.issue("source_overlay", path, "Kotlin source exclusions must not hide private implementation", source, token.start)
            if token.kind == "code" and token.text.lower() in ("kable", "corebluetooth"):
                self.issue("private_production_dependency", path, "Public build must not link BLE/private implementation", source, token.start)
            if token.kind == "string" and ("pro-ci" in token.text or "proMain" in token.text or "standardMain" in token.text):
                # Plain explanatory require messages are not build inputs.
                previous = [t.text for t in tokens[max(0, i - 3):i]]
                if any(name in previous for name in ("includeBuild", "srcDir", "file", "project", "srcFile")) or "/" in token.text and not " " in token.text:
                    self.issue("private_build_input", path, "Public build must not read a private/source-selected path", source, token.start)
            if token.kind == "string" and token.text.startswith(("com.juul.kable:", "com.juul.kable.")):
                self.issue("private_production_dependency", path, "Public build must not link Kable", source, token.start)

    def gradle_rules(self):
        settings = self.read("settings.gradle.kts")
        if settings:
            source, tokens, _ = settings
            included = set()
            for i, token in enumerate(tokens):
                if token.text == "include" and i + 1 < len(tokens) and tokens[i + 1].text == "(":
                    included.update(t.text for t in tokens[i + 2:_call_end(tokens, i + 1)] if t.kind == "string")
            for module in MODULES:
                if module not in included:
                    self.issue("missing_module_include", "settings.gradle.kts", f"Missing explicit include({module})")
        for module, directory in {**MODULES, ":shared": "shared"}.items():
            path = directory + "/build.gradle.kts"
            item = self.read(path)
            if item is None:
                continue
            source, tokens, _ = item
            self.no_overlay(path, source, tokens)
            contexts = _blocks(tokens)
            found = set()
            for i, token in enumerate(tokens):
                if token.text not in DEPENDENCY_CALLS or i + 1 >= len(tokens) or tokens[i + 1].text != "(":
                    continue
                context = contexts[i]
                if any(re.search(r"\b\w*Test\w*\b", label) for label in context):
                    continue
                end = _call_end(tokens, i + 1)
                args = tokens[i + 2:end - 1]
                projects = _project_calls(args)
                for dependency, offset in projects:
                    if module in MODULES and dependency in ALLOWED_PROJECTS[module] and token.text == "api" and any("commonMain" in label for label in context):
                        self.api_edges[module].add(dependency)
                    if (module, dependency) in ((":platform:schedule-native", ":platform:storage"), (":platform:storage", ":core:text")) and not any("androidMain" in label for label in context):
                        self.issue("forbidden_project_dependency", path, "Android capability wiring belongs only to androidMain", source, offset)
                    if dependency is None:
                        self.issue("unresolved_project_dependency", path, "Production project dependency must have an explicit stable path", source, offset)
                    elif module in MODULES and dependency not in ALLOWED_PROJECTS[module]:
                        self.issue("forbidden_project_dependency", path, f"{module} must not depend on {dependency}", source, offset)
                    elif (dependency in MODULES and token.text in ("api", "implementation")
                          and (any("commonMain" in label for label in context) or module == ":shared" and dependency == ":platform:storage" and any("androidMain" in label for label in context))):
                        found.add(dependency)
                spelling = ".".join(t.text for t in args)
                if module == ":platform:updates" and "androidx" in spelling:
                    android_only = any("androidMain" in label for label in context)
                    approved = spelling in ("libs...androidx...core", "libs...androidx...lifecycle...runtime", "libs...androidx...annotation")
                    if not android_only or not approved:
                        self.issue("forbidden_library_dependency", path, "Updater AndroidX libraries are reviewed Android-main adapters only", source, token.start)
                if module in PURE_MODULES and re.search(r"compose|miuix|ktor|androidx|sqldelight|room", spelling, re.I):
                    self.issue("forbidden_library_dependency", path, f"{module} must keep production dependencies pure: {spelling}", source, token.start)
                if module == ":core:text" and not projects:
                    self.issue("text_library_dependency", path, "core:text permits Kotlin standard library only", source, token.start)
                if module in (":platform:schedule-native", ":feature:schedule:runtime", ":platform:storage", ":feature:run:runtime") and re.search(r"compose|miuix|ktor|androidx|sqldelight|room", spelling, re.I):
                    self.issue("forbidden_library_dependency", path, "Schedule native/runtime must not import UI, HTTP or unrelated platform libraries", source, token.start)
                if module == ":platform:updates" and re.search(r"compose|miuix|ktor|serialization.*json|sqldelight|room", spelling, re.I):
                    self.issue("forbidden_library_dependency", path, "Platform updater must keep transport/UI out of its ownership boundary", source, token.start)
                if module in DATA_MODULES and re.search(r"compose|miuix|androidx|sqldelight|room", spelling, re.I):
                    self.issue("forbidden_library_dependency", path, f"{module} data must not take UI/runtime database libraries", source, token.start)
                if module == ":feature:schedule:ui" and not projects and not (spelling.startswith("compose...") or spelling in ("libs...compose...runtime", "libs...compose...foundation", "libs...compose...ui", "libs...miuix...ui", "libs...kotlinx...datetime")):
                    self.issue("forbidden_library_dependency", path, "Binding UI keeps only Compose/MIUIX presentation libraries", source, token.start)
                if module == ":feature:auth:ui" and not projects and spelling not in ("libs...compose...runtime", "libs...compose...foundation", "libs...compose...ui", "libs...miuix...ui", "libs...miuix...icons"):
                    self.issue("forbidden_library_dependency", path, "Login UI keeps only the reviewed Compose/MIUIX presentation libraries", source, token.start)
                if module == ":platform:location" and not projects and spelling not in ("libs...compose...runtime", "libs...coroutines...core", "libs...activity...compose", "libs...compose...ui"):
                    self.issue("forbidden_library_dependency", path, "Location capability keeps only the reviewed Compose/coroutines platform libraries", source, token.start)
                if module in (":feature:profile:domain", ":feature:content:domain") and not projects and spelling != "libs...serialization...json":
                    self.issue("forbidden_library_dependency", path, "Profile/content domains keep only the reviewed serialization library", source, token.start)
                if module == ":feature:profile:data" and not projects and spelling not in ("libs...ktor...core", "libs...serialization...json"):
                    self.issue("forbidden_library_dependency", path, "Profile data keeps only the reviewed transport/serialization libraries", source, token.start)
                if module == ":feature:content:data" and not projects:
                    self.issue("forbidden_library_dependency", path, "Content data owns no reviewed libraries beyond project dependencies", source, token.start)
                if module == ":feature:profile:presentation" and not projects and spelling != "libs...coroutines...core":
                    self.issue("forbidden_library_dependency", path, "Profile presentation keeps only the reviewed coroutines library", source, token.start)
                if module == ":feature:content:presentation" and not projects and spelling not in ("libs...coroutines...core", "libs...serialization...json"):
                    self.issue("forbidden_library_dependency", path, "Content presentation keeps only the reviewed coroutine/serialization libraries", source, token.start)
                if module == ":design-system" and re.search(r"ktor|serialization.*json", spelling, re.I):
                    self.issue("forbidden_library_dependency", path, "DS must not take transport/JSON business dependencies", source, token.start)
            if module == ":shared":
                for required in MODULES:
                    if required not in found:
                        self.issue("missing_shared_module_dependency", path, f"shared commonMain must use api/implementation(project(\"{required}\"))")
            # 库不能通过源路径/friend paths绕过编译边界；shared自己的edition选择暂保留。
            for token in tokens:
                if token.kind == "string":
                    normalized = token.text.replace("\\", "/")
                    if "friend-paths" in normalized:
                        self.issue("friend_path_bypass", path, "Production friend paths do not establish a public API", source, token.start)
                    if (module == ":shared" and any(root + "/" in normalized or normalized == root for root in MODULES.values())
                            or module in MODULES and "shared/src" in normalized):
                        self.issue("source_overlay", path, "Use module dependencies, not cross-module source inputs", source, token.start)
            if module in PURE_MODULES | DATA_MODULES | {":platform:updates", ":platform:schedule-native", ":feature:schedule:runtime"}:
                for name, offset in self.plugin_names(tokens):
                    if "compose" in name.lower():
                        self.issue("forbidden_plugin", path, f"{module} applies UI plugin {name}", source, offset)
        # 已采用的convention也属于真实配置输入；不能把依赖藏进默认插件。
        for name in ("betterzslf.kmp-library", "betterzslf.compose-library"):
            path = "build-logic/src/main/groovy/" + name + ".gradle"
            if not (self.root / path).exists():
                continue
            item = self.read(path, groovy=True)
            if not item:
                continue
            source, tokens, _ = item
            if name == "betterzslf.kmp-library":
                # Nested modules repeat api/domain/data/ui leaf names. A constant group
                # silently lets Gradle substitute an unrelated project before compilation.
                groups = [i for i, token in enumerate(tokens) if token.text == "group" and i + 1 < len(tokens) and tokens[i + 1].text == "="]
                for index in groups:
                    end = index + 2
                    while end < len(tokens) and (end == index + 2 or "\n" not in source[tokens[end - 1].start:tokens[end].start]):
                        end += 1
                    expression = tokens[index + 2:end]
                    if len(expression) == 1 and expression[0].kind == "string":
                        self.issue("module_coordinate_collision", path, "Constant component group collides for repeated nested module names", source, tokens[index].start)
                for plugin, offset in self.plugin_names(tokens):
                    if "compose" in plugin.lower():
                        self.issue("forbidden_convention_plugin", path, "Pure library convention must not apply Compose", source, offset)
                contexts = _blocks(tokens)
                for i, token in enumerate(tokens):
                    if token.text in DEPENDENCY_CALLS and not any("Test" in label for label in contexts[i]):
                        self.issue("convention_production_dependency", path, "Pure convention must not inject production libraries", source, token.start)

    @staticmethod
    def plugin_names(tokens):
        names = []
        for i, token in enumerate(tokens):
            if token.text == "id" and i + 1 < len(tokens):
                j = i + 2 if tokens[i + 1].text == "(" else i + 1
                if j < len(tokens) and tokens[j].kind == "string":
                    names.append((tokens[j].text, token.start))
        return names


def run_guard(root) -> GuardResult:
    guard = _Guard(Path(root).resolve())
    guard.collect_sources()
    guard.gradle_rules()  # Resolve reviewed api exports before declaration-owner checks.
    guard.source_rules()
    guard.duplicate_rules()
    guard.schedule_owner_rules()
    guard.run_owner_rules()
    guard.location_owner_rules()
    guard.public_rules()
    return GuardResult(guard.files_scanned, tuple(guard.violations), tuple(guard.errors))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", action="store_true", help="Print JSON to stdout; write no report files")
    args = parser.parse_args(argv)
    result = run_guard(args.root)
    if args.json:
        print(json.dumps(result.to_dict(), ensure_ascii=True, indent=2))
    else:
        for issue in (*result.errors, *result.violations):
            location = issue["path"]
            if "line" in issue:
                location += f":{issue['line']}:{issue['column']}"
            print(f"{location}: {issue['code']}: {issue['message']}")
        print(f"architecture guard: {'PASS' if result.ok else 'FAIL'}; files={result.files_scanned}; first-batch static checks only")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
