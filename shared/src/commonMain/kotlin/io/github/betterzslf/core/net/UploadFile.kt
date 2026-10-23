package io.github.betterzslf.core.net

/** Bytes come from a user-selected platform file; no paths or files are opened by core. */
class UploadFile(
    val name: String,
    val bytes: ByteArray,
    val contentType: String = "application/octet-stream",
) {
    override fun toString(): String = "UploadFile(size=${bytes.size})"
}
