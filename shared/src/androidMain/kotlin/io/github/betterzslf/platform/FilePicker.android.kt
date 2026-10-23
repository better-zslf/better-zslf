package io.github.betterzslf.platform

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberFilePicker(onResult: (FilePickResult) -> Unit): UserRequestLauncher {
    val context = LocalContext.current.applicationContext
    val resultCallback by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) resultCallback(FilePickResult.Cancelled) else scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    } ?: "attachment"
                    val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(MAX_PICKED_FILE_BYTES + 1) }
                    when {
                        bytes == null -> FilePickResult.Failed("文件读取失败，请重新选择。")
                        bytes.size > MAX_PICKED_FILE_BYTES -> FilePickResult.Failed("文件超过 8 MiB 限制，请选择较小文件。")
                        else -> FilePickResult.Selected(PickedFile(name, resolver.getType(uri) ?: "application/octet-stream", bytes))
                    }
                } catch (_: Exception) {
                    FilePickResult.Failed("文件读取失败，请重新选择。")
                }
            }
            resultCallback(result)
        }
    }
    return remember(picker) {
        UserRequestLauncher {
            runCatching { picker.launch(arrayOf("*/*")) }.onFailure {
                resultCallback(FilePickResult.Failed("文件选择器暂不可用。"))
            }
        }
    }
}

@Composable
actual fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): UserRequestLauncher {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        callback(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    return remember(permissions, context) {
        UserRequestLauncher {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) callback(true)
            else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
