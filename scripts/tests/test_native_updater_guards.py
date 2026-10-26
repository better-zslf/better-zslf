"""Owned-source/pure-policy checks only; not Android runtime or compiled Kotlin proof.

No downloads, ADB, installation, permission changes, Gradle, Git or third-party modules.
"""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PLATFORM = "kotlin/io/github/betterzslf/platform/"
ANDROID = (ROOT / "shared/src/androidMain" / PLATFORM / "AppUpdater.android.kt").read_text(encoding="utf-8")
COMMON = (ROOT / "shared/src/commonMain" / PLATFORM / "AppUpdater.kt").read_text(encoding="utf-8")
IOS = (ROOT / "shared/src/iosMain" / PLATFORM / "AppUpdater.ios.kt").read_text(encoding="utf-8")
JVM = (ROOT / "shared/src/jvmMain" / PLATFORM / "AppUpdater.jvm.kt").read_text(encoding="utf-8")
A = "{http://schemas.android.com/apk/res/android}"


def section(start, end):
    return ANDROID.split(start, 1)[1].split(end, 1)[0]


POLICY = (ROOT / "shared/src/commonMain/kotlin/io/github/betterzslf/feature/update/GithubReleaseModels.kt").read_text(encoding="utf-8")
EDITION_SOURCES = {edition: (ROOT / f"shared/src/{edition}Main/kotlin/io/github/betterzslf/edition/BuildEdition.kt").read_text(encoding="utf-8") for edition in ("standard", "pro")}


def kotlin_regex(name):
    raw = re.search(r'private val ' + name + r' = Regex\("((?:\\.|[^"\\])*)"\)', POLICY).group(1)
    return re.compile(json.loads('"' + raw + '"'))


def edition_constant(edition, name):
    return re.search(r'const val ' + name + r'(?:: String)? = "([^"\n]*)"', EDITION_SOURCES[edition]).group(1)


VERSION = kotlin_regex("pattern")
SHA = kotlin_regex("shaPattern")
ASSET_TEMPLATE = re.search(r'const val ASSET_PREFIX = "([^"\n]+)"', POLICY).group(1)
URL_TEMPLATE = ASSET_TEMPLATE.replace("${BuildEdition.updateRepository}", edition_constant("standard", "updateRepository")) + "v${spec.versionName}/better-zslf-${spec.versionName}.apk"


def extracted_policy(version="1.2.3", size=1, sha="a" * 64, url=None, edition="standard"):
    """Actual common regex/template plus actual selected edition constants; not Kotlin runtime proof."""
    prefix = ASSET_TEMPLATE.replace("${BuildEdition.updateRepository}", edition_constant(edition, "updateRepository"))
    expected = prefix + f"v{version}/better-zslf-{version}.apk"
    suffix = edition_constant(edition, "versionNameSuffix")
    numeric = version.removesuffix(suffix) if suffix else version
    return 1 <= len(version) <= 128 and VERSION.fullmatch(numeric) is not None and 1 <= size <= 250 * 1024 * 1024 and SHA.fullmatch(sha) is not None and (expected if url is None else url) == expected


class NativeUpdaterSourceTests(unittest.TestCase):
    def test_policy_extracted_from_actual_source_keeps_all_bounds(self):
        self.assertIn("fun valid(spec: ApkUpdateSpec): Boolean = GithubReleasePolicy.isInstallSpec(spec)", ANDROID)
        for text in ("normalized.length > 128", "version.versionName != spec.versionName", "shaPattern.matches(spec.sha256)", "spec.sizeBytes !in 1..MAX_APK_BYTES", "250L * 1024 * 1024"):
            self.assertIn(text, POLICY)
        self.assertTrue(extracted_policy())
        self.assertTrue(extracted_policy(size=250 * 1024 * 1024, sha="F" * 64))
        for size in (0, -1, 250 * 1024 * 1024 + 1, 2**63 - 1):
            self.assertFalse(extracted_policy(size=size))
        for sha in ("", "a" * 63, "a" * 65, "g" * 64, "a" * 63 + "\n"):
            self.assertFalse(extracted_policy(sha=sha))

    def test_stable_versions_are_bounded_without_number_parsing_overflow(self):
        for version in ("", "v1.2.3", "1.2", "01.2.3", "1.02.3", "1.2.3-beta", "1.2.3+build", "1.2.3/../x", "9" * 129 + ".0.0"):
            self.assertFalse(extracted_policy(version=version))
        self.assertTrue(extracted_policy(version="9" * 100 + ".0.0"))

    def test_exact_github_url_rejects_credentials_ports_redirect_params_and_traversal(self):
        valid = URL_TEMPLATE.replace("${spec.versionName}", "1.2.3")
        for url in (valid.replace("https:", "http:"), valid.replace("github.com", "evil.example"), valid.replace("github.com", "user:pass@github.com"),
                    valid.replace("github.com", "github.com:443"), valid + "?token=x", valid + "#fragment", valid + "\n",
                    valid.replace("better-zslf-1.2.3.apk", "other.apk"), valid.replace("/v1.2.3/", "/v1.2.4/"),
                    valid.replace("/v1.2.3/", "/%2e%2e/"), valid.replace("/v1.2.3/", "/../"), "file:///tmp/x.apk", "content://update"):
            self.assertFalse(extracted_policy(url=url))

    def test_factory_and_unsupported_platforms_are_fresh_and_inert(self):
        self.assertIn("createNativeAppUpdater(): NativeAppUpdater = AndroidAppUpdater()", ANDROID)
        initial = ANDROID.split("override fun download", 1)[0]
        self.assertNotIn("manager.enqueue", initial)
        self.assertNotIn("startActivity(", initial)
        self.assertIn("private val context: Context get()", initial)
        for source in (IOS, JVM):
            self.assertIn("createNativeAppUpdater(): NativeAppUpdater = object", source)
            self.assertIn("supportsApkInstall: Boolean = false", source)
            self.assertIn("AppUpdatePhase.UNAVAILABLE", source)
            for method in ("download(spec: ApkUpdateSpec)", "install()", "cancel()", "close()"):
                self.assertIn("override fun " + method + " = Unit", source)
            for action in ("HttpClient", "startActivity", "NSURL", "java.io", "DownloadManager"):
                self.assertNotIn(action, source)

    def test_download_has_fixed_ua_and_never_auth_cookies_or_completion_notification(self):
        body = section("private suspend fun downloadOwned", "private suspend fun awaitDownload")
        self.assertEqual(re.findall(r'addRequestHeader\("([^"]+)"', body), ["User-Agent"])
        self.assertIn('addRequestHeader("User-Agent", "better-zslf-updater")', body)
        self.assertNotIn("VISIBILITY_VISIBLE_NOTIFY_COMPLETED", ANDROID)
        self.assertNotIn("CookieManager", ANDROID)
        self.assertNotIn("Build.MODEL", ANDROID)
        self.assertNotIn("HttpClient", ANDROID)

    def test_every_dm_query_and_remove_is_id_filtered(self):
        calls = re.findall(r'manager\.query\(([^\n]+)', ANDROID)
        self.assertEqual(len(calls), 2)
        self.assertTrue(all("DownloadManager.Query().setFilterById(id)" in call for call in calls))
        removals = re.findall(r'manager\.remove\(([^)]+)\)', ANDROID)
        self.assertEqual(removals, ["id", "id"])
        self.assertIn("transfer.downloadId = manager.enqueue(request)", ANDROID)
        self.assertNotIn("deleteRecursively", ANDROID)
        self.assertNotIn("listFiles(", ANDROID)

    def test_generation_guards_cancel_close_and_reject_late_publication(self):
        self.assertIn("closed || active !== transfer || transfer.generation != generation", ANDROID)
        self.assertIn("!closed && active === transfer && transfer.generation == generation", ANDROID)
        self.assertIn("closed = true; generation++; active = null", ANDROID)
        self.assertIn("transfer.job?.cancel()", ANDROID)
        self.assertIn("transfer.job?.join()", ANDROID)
        self.assertIn("scope.launch(NonCancellable)", ANDROID)
        self.assertIn("synchronized(transfer.cleanupLock)", ANDROID)

    def test_snapshot_is_private_bounded_streamed_same_fd_and_sealed(self):
        for guard in ("OsConstants.O_NOFOLLOW", "OsConstants.S_ISREG", "ParcelFileDescriptor.dup(descriptor)", "AutoCloseInputStream(ownedDescriptor)",
                      "ByteArray(64 * 1024)", "count > transfer.spec.sizeBytes", "count != transfer.spec.sizeBytes", "ignoreCase = true", "output.fd.sync()",
                      'File(context.cacheDir, "updates")', '"verified-${transfer.requestId}.apk"', "setWritable(false, false)", "Files.createFile"):
            self.assertIn(guard, ANDROID)
        self.assertNotIn("readBytes()", ANDROID)
        body = section("private suspend fun copyVerifiedSnapshot", "/** Open one NOFOLLOW")
        self.assertIn("digestFile(source, transfer) { bytes, count -> output.write(bytes, 0, count) }", body)
        self.assertIn("Files.isSymbolicLink(directory.toPath())", ANDROID)

    def test_package_version_and_exact_current_certificates_are_required(self):
        body = section("private fun verifyArchive", "override fun install")
        for text in ("GET_SIGNING_CERTIFICATES", "incoming.packageName != context.packageName", "incoming.versionName != transfer.spec.versionName",
                     "incoming.longVersionCode <= installed.longVersionCode", "apkContentsSigners", "current.isEmpty() || current != certificates(incoming)"):
            self.assertIn(text, body)
        self.assertNotIn("signingCertificateHistory", body)

    def test_install_rehashes_before_every_os_action_and_stays_user_confirmed(self):
        body = section("private suspend fun installOwned", "private fun externalRoot")
        self.assertLess(body.index("digestFile(file, transfer)"), body.index("verifyArchive(transfer)"))
        self.assertLess(body.index("verifyArchive(transfer)"), body.index("context.startActivity"))
        for text in ("Dispatchers.Main.immediate", "IMPORTANCE_FOREGROUND", "synchronized(lock)", "canRequestPackageInstalls()", "Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES",
                     'Uri.parse("package:${context.packageName}")', "FileProvider.getUriForFile", "FLAG_GRANT_READ_URI_PERMISSION", "clipData"):
            self.assertIn(text, body)
        self.assertIn("publish(transfer, AppUpdatePhase.INSTALLER_OPENED, AppText.Updates.SYSTEM_INSTALL_CONFIRMATION_REQUESTED_INSTALL_UNCONFIRMED, transfer.spec.sizeBytes)", body)
        catalog = (ROOT / "shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt").read_text(encoding="utf-8")
        updates = catalog.split("    object Updates {", 1)[1].split("\n    }", 1)[0]
        self.assertIn('const val SYSTEM_INSTALL_CONFIRMATION_REQUESTED_INSTALL_UNCONFIRMED: String = "已向系统请求打开安装确认；尚未确认安装。"', updates)
        for text in ("PackageInstaller.Session", "EXTRA_NOT_UNKNOWN_SOURCE", "EXTRA_ALLOW_REPLACE", "FLAG_GRANT_WRITE_URI_PERMISSION", "su -c"):
            self.assertNotIn(text, ANDROID)
        self.assertEqual(ANDROID.count("context.startActivity("), 2)

    def test_private_journal_is_not_read_on_create_or_resumed_as_success(self):
        download = section("private suspend fun downloadOwned", "private suspend fun awaitDownload")
        self.assertLess(download.index("writeRecord(transfer)"), download.index("manager.enqueue(request)"))
        recover = ANDROID.split("private fun reconcileDeadProcessRecords()", 1)[1]
        for text in ("fields[0] == UpdateProcess.token", "uuid.matches(fields[2])", '"incoming-$requestId"', '"verified-$requestId.apk"',
                     "it.uid == Process.myUid() && it.pid == pid", "DownloadManager.COLUMN_LOCAL_URI", "if (!matches) return@runCatching", "if (id > 0 || fields[5].isNotEmpty())"):
            self.assertIn(text, recover)
        self.assertNotIn("AppUpdatePhase.READY_TO_INSTALL", recover)
        self.assertNotIn("startActivity", recover)
        self.assertEqual(ANDROID.count("reconcileDeadProcessRecords()"), 2)  # declaration + explicit download only.
        self.assertNotIn("restore", COMMON.lower())

    def test_manifest_file_provider_is_narrow_and_keeps_main_permissions(self):
        manifest = ET.parse(ROOT / "shared/src/androidMain/AndroidManifest.xml").getroot()
        self.assertEqual([e.get(A + "name") for e in manifest.findall("uses-permission")], ["android.permission.REQUEST_INSTALL_PACKAGES"])
        app = manifest.find("application")
        self.assertEqual(app.attrib, {})
        provider = app.find("provider")
        self.assertEqual(provider.get(A + "name"), "androidx.core.content.FileProvider")
        self.assertEqual(provider.get(A + "authorities"), "${applicationId}.updates")
        self.assertEqual(provider.get(A + "exported"), "false")
        self.assertEqual(provider.get(A + "grantUriPermissions"), "true")
        paths = ET.parse(ROOT / "shared/src/androidMain/res/xml/better_zslf_update_paths.xml").getroot()
        self.assertEqual([(p.tag, p.get("path")) for p in paths], [("cache-path", "updates/")])
        main = ET.parse(ROOT / "androidApp/src/main/AndroidManifest.xml").getroot()
        permissions = {e.get(A + "name") for e in main.findall("uses-permission")}
        self.assertTrue({"android.permission.FOREGROUND_SERVICE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"} <= permissions)
        self.assertFalse(any("BLUETOOTH" in permission for permission in permissions))
        pro = ET.parse(ROOT / "androidApp/src/pro/AndroidManifest.xml").getroot()
        pro_permissions = {e.get(A + "name") for e in pro.findall("uses-permission")}
        self.assertTrue(permissions <= pro_permissions)
        self.assertTrue({"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"} <= pro_permissions)

    def test_pro_repository_suffix_and_package_are_isolated(self):
        self.assertTrue(extracted_policy(version="1.2.3-pro", edition="pro"))
        self.assertFalse(extracted_policy(version="1.2.3-pro"))
        normal_url = URL_TEMPLATE.replace("${spec.versionName}", "1.2.3-pro")
        self.assertFalse(extracted_policy(version="1.2.3-pro", edition="pro", url=normal_url))
        self.assertEqual(edition_constant("standard", "androidApplicationId"), "io.github.betterzslf")
        self.assertEqual(edition_constant("pro", "androidApplicationId"), "io.github.betterzslf.pro")
        self.assertGreaterEqual(ANDROID.count("context.packageName != BuildEdition.androidApplicationId"), 2)

    def test_errors_and_diagnostics_do_not_dump_paths_urls_or_exception_messages(self):
        self.assertIn('toString(): String = "ApkUpdateSpec(redacted)"', COMMON)
        for token in ("printStackTrace", "exception.message", "error.message", "Log.", "println("):
            self.assertNotIn(token, ANDROID)
        self.assertNotIn("transfer.spec.downloadUrl", ANDROID.split("private fun writeRecord", 1)[1])
        self.assertIn("UpdateFailure(val fixedMessage: String) : Exception()", ANDROID)


if __name__ == "__main__":
    unittest.main(verbosity=2)
