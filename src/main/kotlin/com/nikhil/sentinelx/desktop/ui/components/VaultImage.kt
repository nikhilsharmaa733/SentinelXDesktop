package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * Displays an image held in the encrypted vault.
 *
 * Decryption and decoding happen off the UI thread and the result is cached for the
 * composition's lifetime. The decoded bitmap lives only in memory — writing it to a
 * temp file for a normal image loader to pick up would defeat the point of sealing
 * the blobs in the first place.
 *
 * The vault stores WEBP (that is what the phone's ImageUtils writes). Skia decodes
 * it natively, so no extra image library is needed.
 */
@Composable
fun VaultImage(
    fileName: String?,
    loader: (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholderGlyph: String = "ᚦ"
) {
    var bitmap by remember(fileName) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(fileName) { mutableStateOf(false) }

    LaunchedEffect(fileName) {
        bitmap = null
        failed = false
        val name = fileName?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                loader(name)?.let { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
            }.getOrNull()
        }
        if (decoded == null) failed = true else bitmap = decoded
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceStone)
            .border(1.dp, GoldDark.copy(0.18f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )

            failed || fileName.isNullOrBlank() -> Text(
                placeholderGlyph,
                color = GoldDark.copy(0.35f),
                fontSize = 26.sp
            )

            else -> Text("…", color = TextMuted, fontSize = 14.sp)
        }
    }
}

/** Muted red used when an image the entities reference is absent from the vault. */
val MissingImageTint: Color = ExpenseRed.copy(0.5f)
