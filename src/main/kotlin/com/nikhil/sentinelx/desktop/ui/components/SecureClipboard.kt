package com.nikhil.sentinelx.desktop.ui.components

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.Timer
import java.util.TimerTask

/**
 * Clipboard writes for secret material — the desktop counterpart of the Android
 * app's `util/SecureClipboard.kt`.
 *
 * A password left on the clipboard is readable by every other application on the
 * machine, indefinitely, and on Windows it may also sync to other devices through
 * Cloud Clipboard. So the value is cleared after a timeout.
 *
 * The clear only happens if the clipboard *still holds our value*. Wiping whatever
 * the user copied in the meantime would be worse than the problem being solved.
 *
 * ⚠️ Desktop cannot match the phone here. Android lets an app mark a clip sensitive
 * (`ClipDescription.EXTRA_IS_SENSITIVE`) so the OS hides it from previews and
 * clipboard history. There is no equivalent on Windows or Linux — the timeout is
 * the whole defence. Say so in the README rather than implying parity.
 */
object SecureClipboard {

    private const val CLEAR_DELAY_MS = 30_000L

    private val timer = Timer("sentinel-clipboard", true)
    private var pending: TimerTask? = null

    @Synchronized
    fun copySensitive(label: String, value: String) {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return
        clipboard.setContents(StringSelection(value), null)

        // Supersede any in-flight clear, so copying twice in a row doesn't wipe the
        // second value on the first one's schedule.
        pending?.cancel()
        pending = object : TimerTask() {
            override fun run() {
                runCatching {
                    val current = clipboard.getData(DataFlavor.stringFlavor) as? String
                    if (current == value) clipboard.setContents(StringSelection(""), null)
                }
            }
        }
        timer.schedule(pending, CLEAR_DELAY_MS)
    }

    /** Clears immediately — used when the vault locks. */
    @Synchronized
    fun clearNow(value: String? = null) {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return
        pending?.cancel()
        pending = null
        runCatching {
            val current = clipboard.getData(DataFlavor.stringFlavor) as? String
            if (value == null || current == value) clipboard.setContents(StringSelection(""), null)
        }
    }
}
