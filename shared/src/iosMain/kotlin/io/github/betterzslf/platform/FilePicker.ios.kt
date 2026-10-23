@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject
import kotlin.coroutines.CoroutineContext

@Composable
actual fun rememberFilePicker(onResult: (FilePickResult) -> Unit): UserRequestLauncher {
    val callback = rememberUpdatedState(onResult)
    val controller = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val launcher = remember(controller, scope) {
        IosFilePicker(controller, scope) { callback.value(it) }
    }
    DisposableEffect(launcher) { onDispose { launcher.dispose() } }
    return launcher
}

private class IosFilePicker(
    private val presenter: UIViewController,
    private val scope: CoroutineScope,
    private var onResult: ((FilePickResult) -> Unit)?,
) : NSObject(), UserRequestLauncher, UIDocumentPickerDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
    private var picker: UIDocumentPickerViewController? = null
    private var readJob: Job? = null
    private var coordinator: NSFileCoordinator? = null
    private var disposed = false

    override fun launch() {
        scope.launch(Dispatchers.Main.immediate) {
            if (disposed || picker != null || readJob?.isActive == true) return@launch
            if (presenter.viewIfLoaded?.window == null || presenter.presentedViewController != null ||
                UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive
            ) {
                onResult?.invoke(FilePickResult.Failed("请返回当前页面后重新选择文件"))
                return@launch
            }
            val next = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeData),
                asCopy = false,
            ).apply {
                allowsMultipleSelection = false
                shouldShowFileExtensions = true
                delegate = this@IosFilePicker
            }
            picker = next
            next.presentationController?.delegate = this@IosFilePicker
            presenter.presentViewController(next, animated = true) {
                if (!disposed && picker == next) next.presentationController?.delegate = this@IosFilePicker
            }
        }
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        if (disposed || picker != controller) return
        releasePicker(controller)
        val url = didPickDocumentsAtURLs.singleOrNull() as? NSURL
        if (url == null || !url.fileURL) {
            onResult?.invoke(FilePickResult.Failed("所选文件暂不可读，请重新选择"))
            return
        }
        val operation = NSFileCoordinator(filePresenter = null)
        coordinator = operation
        readJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = try {
                    withContext(Dispatchers.Default) { readPickedFile(url, operation, currentCoroutineContext()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: PickedFileTooLarge) {
                    FilePickResult.Failed("文件超过 8 MiB 限制")
                } catch (_: Exception) {
                    // Native errors may include file paths/provider account information. Never expose them.
                    FilePickResult.Failed("读取文件失败，请重新选择或下载到本机后重试")
                }
                ensureActive()
                coordinator = null
                readJob = null
                if (!disposed) onResult?.invoke(result)
            } finally {
                if (coordinator == operation) {
                    coordinator = null
                    readJob = null
                }
            }
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        if (disposed || picker != controller) return
        releasePicker(controller)
        onResult?.invoke(FilePickResult.Cancelled)
    }

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        val current = picker ?: return
        if (disposed || presentationController.presentedViewController != current) return
        releasePicker(current)
        onResult?.invoke(FilePickResult.Cancelled)
    }

    private fun releasePicker(current: UIDocumentPickerViewController) {
        current.delegate = null
        current.presentationController?.delegate = null
        picker = null
        if (current.presentingViewController != null) current.dismissViewControllerAnimated(true, completion = null)
    }

    fun dispose() {
        disposed = true
        onResult = null
        readJob?.cancel()
        readJob = null
        // Cancels pending provider coordination; an in-flight stream unwinds at its next bounded read.
        coordinator?.cancel()
        coordinator = null
        picker?.let(::releasePicker)
    }
}

private fun readPickedFile(
    url: NSURL,
    coordinator: NSFileCoordinator,
    context: CoroutineContext,
): FilePickResult.Selected {
    context.ensureActive()
    val hasScope = url.startAccessingSecurityScopedResource()
    try {
        context.ensureActive()
        // Coordinate external/provider documents; do not copy them in full or return their URL/path.
        var content: ByteArray? = null
        var failure: Exception? = null
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>().apply { value = null }
            coordinator.coordinateReadingItemAtURL(
                url,
                options = NSFileCoordinatorReadingWithoutChanges,
                error = error.ptr,
            ) { readableUrl ->
                try {
                    context.ensureActive()
                    val metadata = readableUrl.resourceValuesForKeys(listOf(NSURLFileSizeKey), error = null)
                    val knownSize = (metadata?.get(NSURLFileSizeKey) as? NSNumber)?.longLongValue
                    if (knownSize != null && knownSize > MAX_PICKED_FILE_BYTES) throw PickedFileTooLarge()
                    val input = NSInputStream.inputStreamWithURL(readableUrl) ?: throw PickedFileReadFailure()
                    try {
                        input.open()
                        content = readBoundedPickedBytes(
                            limit = MAX_PICKED_FILE_BYTES,
                            knownSize = knownSize,
                            checkCancelled = { context.ensureActive() },
                        ) { buffer, offset, length ->
                            buffer.usePinned {
                                val count = input.read(it.addressOf(offset).reinterpret(), maxLength = length.convert())
                                if (count < 0 || count > length.toLong()) throw PickedFileReadFailure()
                                count.toInt()
                            }
                        }
                    } finally {
                        input.close()
                    }
                } catch (error: Exception) {
                    // Never throw through an Objective-C callback boundary, including cancellation.
                    failure = error
                }
            }
            failure?.let { throw it }
            if (error.value != null) throw PickedFileReadFailure()
        }
        context.ensureActive()
        val bytes = content ?: throw PickedFileReadFailure()
        val mediaType = url.pathExtension?.let { UTType.typeWithFilenameExtension(it)?.preferredMIMEType }
            ?: "application/octet-stream"
        return FilePickResult.Selected(PickedFile(url.lastPathComponent ?: "attachment", mediaType, bytes))
    } finally {
        if (hasScope) url.stopAccessingSecurityScopedResource()
    }
}
