package com.nikhil.sentinelx.desktop.ui.components

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Picks image files from disk — the desktop replacement for the phone's camera and
 * laser cropper, neither of which has a sensible equivalent here.
 *
 * Bytes are returned with the original extension preserved rather than re-encoded.
 * The phone reads vault images through Coil/BitmapFactory, which handle PNG, JPEG
 * and WEBP alike, so converting would cost quality and time for no benefit.
 */
object ImagePicker {

    private val allowed = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    /** Guards against loading something enormous into memory by accident. */
    private const val MAX_BYTES = 40L * 1024 * 1024

    data class Picked(val bytes: ByteArray, val extension: String, val name: String) {
        // ByteArray in a data class needs these, or equality compares references.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Picked && name == other.name && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
    }

    fun pick(multiple: Boolean = false): List<Picked> {
        val dialog = FileDialog(null as Frame?, "Select image", FileDialog.LOAD).apply {
            isMultipleMode = multiple
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in allowed }
        }
        dialog.isVisible = true

        val chosen = if (multiple) dialog.files?.toList().orEmpty()
        else listOfNotNull(dialog.directory?.let { d -> dialog.file?.let { f -> File(d, f) } })

        return chosen.mapNotNull { file -> read(file) }
    }

    private fun read(file: File): Picked? {
        if (!file.isFile) return null
        val extension = file.extension.lowercase().takeIf { it in allowed } ?: return null
        if (file.length() > MAX_BYTES) {
            System.err.println("Skipped ${file.name}: larger than 40 MB")
            return null
        }
        return runCatching { Picked(file.readBytes(), extension, file.name) }.getOrNull()
    }
}
