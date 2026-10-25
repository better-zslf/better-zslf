package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** No Context/DownloadManager/permission/installer work until an explicit operation. */
actual fun createNativeAppUpdater(): NativeAppUpdater = AndroidAppUpdater()

private object UpdateProcess {
    val token: String by lazy { UUID.randomUUID().toString() }
}

/** Pure policy, intentionally stricter than a generic HTTPS downloader. */
internal object AndroidUpdatePolicy {
    const val MAX_BYTES = 250L * 1024 * 1024
    private val version = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
    private val sha = Regex("[0-9a-fA-F]{64}")
    fun valid(spec: ApkUpdateSpec): Boolean = spec.versionName.length in 1..128 && version.matches(spec.versionName) &&
        spec.sizeBytes in 1..MAX_BYTES && sha.matches(spec.sha256) && spec.downloadUrl ==
        "https://github.com/better-zslf/better-zslf/releases/download/v${spec.versionName}/better-zslf-${spec.versionName}.apk"
}

private class UpdateFailure(val fixedMessage: String) : Exception()

private class AndroidAppUpdater : NativeAppUpdater {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AppUpdateState())
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    override val supportsApkInstall: Boolean = true
    private val context: Context get() = AndroidPlatform.context
    private val manager: DownloadManager get() = context.getSystemService(DownloadManager::class.java)
        ?: throw UpdateFailure(AppText.Updates.DOWNLOAD_SERVICE_UNAVAILABLE_RETRY_LATER)
    private val ownerId by lazy { UUID.randomUUID().toString() }
    private var closed = false
    private var generation = 0L
    private var active: Transfer? = null
    private val owned = mutableSetOf<Transfer>()

    private class Transfer(val spec: ApkUpdateSpec, val generation: Long, val ownerId: String) {
        val requestId = UUID.randomUUID().toString()
        var job: Job? = null
        var downloadId: Long? = null
        var externalDirectory: File? = null
        var source: File? = null
        var verified: File? = null
        var granted: Uri? = null
        val cleanupLock = Any()
    }

    override fun download(spec: ApkUpdateSpec) {
        val job = synchronized(lock) {
            if (closed || mutableState.value.phase in setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING,
                    AppUpdatePhase.READY_TO_INSTALL, AppUpdatePhase.INSTALL_PERMISSION_REQUIRED, AppUpdatePhase.INSTALLER_OPENED)) return
            if (!AndroidUpdatePolicy.valid(spec)) {
                mutableState.value = AppUpdateState(AppUpdatePhase.FAILED, message = AppText.Updates.INVALID_UPDATE_PACKAGE_INFORMATION_RECHECK_RELEASE)
                return
            }
            val transfer = Transfer(spec, ++generation, ownerId)
            active = transfer; owned += transfer
            mutableState.value = AppUpdateState(AppUpdatePhase.DOWNLOADING, totalBytes = spec.sizeBytes, message = AppText.Updates.DOWNLOADING_PUBLIC_RELEASE_PACKAGE)
            scope.launch(start = CoroutineStart.LAZY) { downloadOwned(transfer) }.also { transfer.job = it }
        }
        job.start()
    }

    override fun cancel() {
        val transfer = synchronized(lock) {
            if (closed) return
            generation++
            active.also {
                active = null
                mutableState.value = AppUpdateState(AppUpdatePhase.CANCELLED, message = AppText.Updates.UPDATE_PREPARATION_CANCELLED_SYSTEM_INSTALL_CONFIRMATION_REMAINS)
            }
        }
        transfer?.let(::disposeAsync)
    }

    override fun close() {
        val transfers = synchronized(lock) {
            if (closed) return
            closed = true; generation++; active = null
            mutableState.value = AppUpdateState(AppUpdatePhase.CANCELLED, message = AppText.Updates.UPDATE_DOWNLOADER_CLOSED)
            owned.toList()
        }
        transfers.forEach(::disposeAsync)
        scope.cancel()
    }

    private fun disposeAsync(transfer: Transfer) {
        transfer.job?.cancel()
        // Detached only for finite owner cleanup: cancelling the UI scope must not skip its own remove.
        scope.launch(NonCancellable) {
            transfer.job?.join()
            cleanup(transfer)
        }
    }

    private fun ensureOwner(transfer: Transfer) {
        synchronized(lock) {
            if (closed || active !== transfer || transfer.generation != generation) throw CancellationException()
        }
    }

    private fun publish(transfer: Transfer, phase: AppUpdatePhase, message: String, bytes: Long = 0) {
        synchronized(lock) {
            if (!closed && active === transfer && transfer.generation == generation) {
                mutableState.value = AppUpdateState(phase, bytes, transfer.spec.sizeBytes, message)
            }
        }
    }

    private suspend fun downloadOwned(transfer: Transfer) {
        var keepVerified = false
        try {
            ensureOwner(transfer)
            reconcileDeadProcessRecords()
            currentCoroutineContext().ensureActive(); ensureOwner(transfer)
            val root = externalRoot()
            val directory = File(root, "incoming-${transfer.requestId}")
            Files.createDirectory(directory.toPath())
            transfer.externalDirectory = directory
            val source = File(directory, "download.apk.part")
            transfer.source = source
            writeRecord(transfer) // Plan before enqueue; ID=0 means uncertain, NEVER a recovered completion.
            @Suppress("DEPRECATION")
            val request = DownloadManager.Request(Uri.parse(transfer.spec.downloadUrl))
                .addRequestHeader("User-Agent", "better-zslf-updater")
                .setDestinationUri(Uri.fromFile(source))
                .setMimeType("application/vnd.android.package-archive")
                .setTitle(AppText.Updates.UPDATE_DOWNLOAD_NOTIFICATION_TITLE)
                .setDescription(AppText.Updates.UPDATE_DOWNLOAD_NOTIFICATION_DESCRIPTION)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setVisibleInDownloadsUi(false)
            currentCoroutineContext().ensureActive(); ensureOwner(transfer)
            transfer.downloadId = manager.enqueue(request)
            writeRecord(transfer)
            currentCoroutineContext().ensureActive(); ensureOwner(transfer)
            awaitDownload(transfer)
            publish(transfer, AppUpdatePhase.VERIFYING, AppText.Updates.VERIFYING_INSTALL_PACKAGE, transfer.spec.sizeBytes)
            copyVerifiedSnapshot(transfer)
            verifyArchive(transfer)
            check(transfer.verified?.setWritable(false, false) == true)
            // Remove the externally mutable download; only the private sealed snapshot can be installed.
            removeDownload(transfer)
            deleteExternal(transfer)
            writeRecord(transfer)
            currentCoroutineContext().ensureActive(); ensureOwner(transfer)
            keepVerified = true
            publish(transfer, AppUpdatePhase.READY_TO_INSTALL, AppText.Updates.PACKAGE_VERIFIED_SYSTEM_INSTALL_CONFIRMATION_REQUIRED, transfer.spec.sizeBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: UpdateFailure) {
            publish(transfer, AppUpdatePhase.FAILED, failure.fixedMessage)
        } catch (_: Exception) {
            publish(transfer, AppUpdatePhase.FAILED, AppText.Updates.UPDATE_DOWNLOAD_OR_VERIFICATION_FAILED_RETRY)
        } finally {
            if (!keepVerified) withContext(NonCancellable) { cleanup(transfer) }
        }
    }

    private suspend fun awaitDownload(transfer: Transfer) {
        val id = requireNotNull(transfer.downloadId)
        val deadline = SystemClock.elapsedRealtime() + 15 * 60 * 1000L
        while (true) {
            currentCoroutineContext().ensureActive(); ensureOwner(transfer)
            if (SystemClock.elapsedRealtime() > deadline) throw UpdateFailure(AppText.Updates.UPDATE_DOWNLOAD_WAIT_TIMEOUT_RETRY)
            val status = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) throw UpdateFailure(AppText.Updates.CURRENT_UPDATE_DOWNLOAD_STOPPED_RETRY)
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (bytes > transfer.spec.sizeBytes || total > 0 && total != transfer.spec.sizeBytes) throw UpdateFailure(AppText.Updates.PACKAGE_SIZE_RELEASE_INFORMATION_MISMATCH)
                publish(transfer, AppUpdatePhase.DOWNLOADING, AppText.Updates.DOWNLOADING_PUBLIC_RELEASE_PACKAGE, bytes.coerceAtLeast(0))
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            } ?: throw UpdateFailure(AppText.Updates.DOWNLOAD_SERVICE_UNAVAILABLE_RETRY_LATER)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return
                DownloadManager.STATUS_FAILED -> throw UpdateFailure(AppText.Updates.UPDATE_DOWNLOAD_FAILED_RETRY)
            }
            delay(1000)
        }
    }

    private suspend fun copyVerifiedSnapshot(transfer: Transfer) {
        ensureOwner(transfer)
        val source = requireNotNull(transfer.source)
        val directory = requireNotNull(transfer.externalDirectory)
        if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(source.toPath()) || source.canonicalFile.parentFile != directory.canonicalFile) throw UpdateFailure(AppText.Updates.CURRENT_INSTALL_PACKAGE_FILE_INVALID)
        val destination = File(cacheRoot(), "verified-${transfer.requestId}.apk")
        Files.createFile(destination.toPath())
        transfer.verified = destination
        writeRecord(transfer)
        FileOutputStream(destination).use { output ->
            digestFile(source, transfer) { bytes, count -> output.write(bytes, 0, count) }
            output.fd.sync()
        }
    }

    /** Open one NOFOLLOW regular-file FD, bound the stream, hash exactly the bytes copied/read. */
    private suspend fun digestFile(file: File, transfer: Transfer, consume: ((ByteArray, Int) -> Unit)? = null) {
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        val ownedDescriptor = try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) throw UpdateFailure(AppText.Updates.CURRENT_INSTALL_PACKAGE_FILE_INVALID)
            ParcelFileDescriptor.dup(descriptor)
        } finally { Os.close(descriptor) }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        ParcelFileDescriptor.AutoCloseInputStream(ownedDescriptor).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive(); ensureOwner(transfer)
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > transfer.spec.sizeBytes || count > AndroidUpdatePolicy.MAX_BYTES) throw UpdateFailure(AppText.Updates.PACKAGE_SIZE_EXCEEDS_RELEASE_INFORMATION)
                digest.update(buffer, 0, read)
                consume?.invoke(buffer, read)
            }
        }
        val actual = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        if (count != transfer.spec.sizeBytes || !actual.equals(transfer.spec.sha256, ignoreCase = true)) throw UpdateFailure(AppText.Updates.PACKAGE_CHECKSUM_RELEASE_INFORMATION_MISMATCH_REDOWNLOAD)
    }

    private fun verifyArchive(transfer: Transfer) {
        ensureOwner(transfer)
        val file = requireNotNull(transfer.verified)
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != cacheRoot().canonicalFile || file.name != "verified-${transfer.requestId}.apk") throw UpdateFailure(AppText.Updates.CURRENT_INSTALL_PACKAGE_FILE_INVALID)
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val incoming = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw UpdateFailure(AppText.Updates.INVALID_INSTALL_PACKAGE_FORMAT)
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (incoming.packageName != context.packageName || incoming.versionName != transfer.spec.versionName || incoming.longVersionCode <= installed.longVersionCode) throw UpdateFailure(AppText.Updates.PACKAGE_APP_OR_VERSION_UPDATE_MISMATCH)
        fun certificates(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners?.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }?.toSet().orEmpty()
        val current = certificates(installed)
        if (current.isEmpty() || current != certificates(incoming)) throw UpdateFailure(AppText.Updates.PACKAGE_SIGNATURE_CURRENT_APP_MISMATCH_STOP_INSTALLATION)
        // This is a certificate identity precheck. Android's installer also verifies the APK signature.
    }

    override fun install() {
        val job = synchronized(lock) {
            if (closed || mutableState.value.phase !in setOf(AppUpdatePhase.READY_TO_INSTALL, AppUpdatePhase.INSTALL_PERMISSION_REQUIRED, AppUpdatePhase.INSTALLER_OPENED)) return
            val transfer = active ?: return
            if (transfer.job?.isActive == true) return
            mutableState.value = AppUpdateState(AppUpdatePhase.VERIFYING, transfer.spec.sizeBytes, transfer.spec.sizeBytes, AppText.Updates.REVERIFYING_INSTALL_PACKAGE)
            scope.launch(start = CoroutineStart.LAZY) { installOwned(transfer) }.also { transfer.job = it }
        }
        job.start()
    }

    private suspend fun installOwned(transfer: Transfer) {
        var keep = false
        try {
            ensureOwner(transfer)
            val file = requireNotNull(transfer.verified)
            if (file.canonicalFile.parentFile != cacheRoot().canonicalFile || Files.isSymbolicLink(file.toPath())) throw UpdateFailure(AppText.Updates.CURRENT_INSTALL_PACKAGE_FILE_INVALID)
            digestFile(file, transfer)
            verifyArchive(transfer) // Read installed package/version/current certificate again, not cached READY state.
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    ensureOwner(transfer) // Atomic with cancel/close and the native launch request.
                    val process = ActivityManager.RunningAppProcessInfo()
                    ActivityManager.getMyMemoryState(process)
                    if (process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        publish(transfer, AppUpdatePhase.READY_TO_INSTALL, AppText.Updates.RETURN_TO_FOREGROUND_AND_RECONFIRM_INSTALLATION, transfer.spec.sizeBytes)
                    } else if (!context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        publish(transfer, AppUpdatePhase.INSTALL_PERMISSION_REQUIRED, AppText.Updates.INSTALL_SOURCE_SETTINGS_REQUESTED_EXPLICIT_INSTALL_RECONFIRMATION_REQUIRED, transfer.spec.sizeBytes)
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                        transfer.granted = uri
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.clipData = ClipData.newRawUri("better-zslf update", uri)
                        context.startActivity(intent)
                        publish(transfer, AppUpdatePhase.INSTALLER_OPENED, AppText.Updates.SYSTEM_INSTALL_CONFIRMATION_REQUESTED_INSTALL_UNCONFIRMED, transfer.spec.sizeBytes)
                    }
                    keep = true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: UpdateFailure) {
            publish(transfer, AppUpdatePhase.FAILED, failure.fixedMessage)
        } catch (_: Exception) {
            publish(transfer, AppUpdatePhase.FAILED, AppText.Updates.SYSTEM_INSTALLER_UNAVAILABLE_RETRY)
        } finally {
            if (!keep) withContext(NonCancellable) { cleanup(transfer) }
        }
    }

    private fun externalRoot(): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw UpdateFailure(AppText.Updates.APP_DOWNLOAD_DIRECTORY_UNAVAILABLE)
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) throw UpdateFailure(AppText.Updates.APP_DOWNLOAD_DIRECTORY_UNAVAILABLE)
        return root.canonicalFile
    }

    private fun cacheRoot(): File {
        val root = File(context.cacheDir, "updates")
        if (!root.exists() && !root.mkdir()) throw UpdateFailure(AppText.Updates.APP_UPDATE_CACHE_UNAVAILABLE)
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath()) || root.canonicalFile.parentFile != context.cacheDir.canonicalFile) throw UpdateFailure(AppText.Updates.APP_UPDATE_CACHE_UNAVAILABLE)
        return root.canonicalFile
    }

    private fun removeDownload(transfer: Transfer) {
        transfer.downloadId?.let { id ->
            manager.remove(id) // This ID was returned by this transfer's enqueue, never a global query.
            transfer.downloadId = null
        }
    }

    private fun deleteExternal(transfer: Transfer) {
        val directory = transfer.externalDirectory ?: return
        val root = externalRoot()
        if (directory.name != "incoming-${transfer.requestId}" || Files.isSymbolicLink(directory.toPath()) ||
            directory.canonicalFile != File(root, "incoming-${transfer.requestId}").absoluteFile) throw UpdateFailure(AppText.Updates.CURRENT_UPDATE_TEMPORARY_DIRECTORY_INVALID)
        // No recursive deletion, enumeration, or filenames obtained from the HTTP response.
        val source = File(directory, "download.apk.part")
        if (source.exists() && !source.delete()) throw UpdateFailure(AppText.Updates.CURRENT_UPDATE_TEMPORARY_FILE_CLEANUP_INCOMPLETE)
        if (directory.exists() && !directory.delete()) throw UpdateFailure(AppText.Updates.CURRENT_UPDATE_TEMPORARY_DIRECTORY_CLEANUP_INCOMPLETE)
    }

    private fun cleanup(transfer: Transfer) = synchronized(transfer.cleanupLock) {
        var complete = true
        transfer.granted?.let { uri ->
            runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); transfer.granted = null }.onFailure { complete = false }
        }
        runCatching { removeDownload(transfer) }.onFailure { complete = false }
        runCatching { deleteExternal(transfer) }.onFailure { complete = false }
        transfer.verified?.let { file ->
            runCatching {
                if (file.name == "verified-${transfer.requestId}.apk" && file.parentFile?.canonicalFile == cacheRoot().canonicalFile && file.exists() && !file.delete()) complete = false
            }.onFailure { complete = false }
        }
        if (complete && runCatching { preferences().edit().remove("request.${transfer.requestId}").commit() }.getOrDefault(false)) {
            synchronized(lock) { owned -= transfer }
        }
    }

    private fun preferences() = context.getSharedPreferences("better_zslf_update_ownership_v1", Context.MODE_PRIVATE)

    private fun writeRecord(transfer: Transfer) {
        val value = listOf(UpdateProcess.token, Process.myPid().toString(), transfer.ownerId,
            (transfer.downloadId ?: 0).toString(), "incoming-${transfer.requestId}", transfer.verified?.name.orEmpty()).joinToString("|")
        if (!preferences().edit().putString("request.${transfer.requestId}", value).commit()) throw UpdateFailure(AppText.Updates.CURRENT_DOWNLOAD_STATE_SAVE_FAILED_RETRY)
    }

    /** Only on an explicit new download. No restored READY, no installer, no query of all DM records. */
    private fun reconcileDeadProcessRecords() {
        val preferences = preferences()
        val processes = context.getSystemService(ActivityManager::class.java)?.runningAppProcesses ?: return
        val uuid = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        for ((key, raw) in preferences.all) {
            if (!key.startsWith("request.")) continue
            val requestId = key.removePrefix("request.")
            if (!uuid.matches(requestId)) continue
            val fields = (raw as? String)?.split('|') ?: continue
            if (fields.size != 6 || !uuid.matches(fields[0]) || !uuid.matches(fields[2]) || fields[0] == UpdateProcess.token) continue
            val pid = fields[1].toIntOrNull()?.takeIf { it > 0 } ?: continue
            // A reused current PID is distinguished by the random process token, not PID alone.
            if (pid != Process.myPid() && processes.any { it.uid == Process.myUid() && it.pid == pid }) continue
            if (fields[4] != "incoming-$requestId" || fields[5].isNotEmpty() && fields[5] != "verified-$requestId.apk") continue
            val id = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: continue
            runCatching {
                val directory = File(externalRoot(), fields[4])
                if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile.parentFile != externalRoot()) return@runCatching
                val source = File(directory, "download.apk.part")
                if (id > 0) {
                    val matches = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        if (!cursor.moveToFirst()) true else {
                            val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: return@use false
                            val uri = Uri.parse(local)
                            uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.path?.let { File(it).canonicalFile == source.canonicalFile } == true
                        }
                    } ?: false
                    if (!matches) return@runCatching
                    manager.remove(id) // Proven dead-process private ownership record + exact destination match.
                }
                // id=0 with no private snapshot is the non-atomic enqueue/ledger gap.
                // Delete only the planned paths, keep that uncertainty record, never invent an ID.
                if (source.exists() && !source.delete()) return@runCatching
                if (directory.exists() && !directory.delete()) return@runCatching
                if (fields[5].isNotEmpty()) {
                    val file = File(cacheRoot(), fields[5])
                    if (file.exists() && !file.delete()) return@runCatching
                }
                if (id > 0 || fields[5].isNotEmpty()) preferences.edit().remove(key).commit()
            }
        }
    }
}
