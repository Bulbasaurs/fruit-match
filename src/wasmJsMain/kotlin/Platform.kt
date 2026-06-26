@file:Suppress("OPT_IN_USAGE", "UNCHECKED_CAST")

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.js.Promise
import kotlinx.coroutines.await
import org.jetbrains.skia.Image as SkiaImage

// ── JS interop shims ──────────────────────────────────────────────────────────

@JsFun("() => Date.now()")
private external fun dateNowMs(): Double

actual fun generateSeed(): Long = dateNowMs().toLong().let { if (it == 0L) 1L else it }

@JsFun("(t) => { try { navigator.clipboard.writeText(t) } catch(e) {} }")
private external fun jsWriteClipboard(t: String)

actual fun copyToClipboard(text: String) = jsWriteClipboard(text)

@JsFun("(k) => localStorage.getItem(k) || ''")
private external fun lsGet(k: String): String

@JsFun("(k, v) => localStorage.setItem(k, v)")
private external fun lsSet(k: String, v: String)

private const val LS_KEY = "fruitMatch_scores"

actual fun loadScores(): List<HighScore> = runCatching {
    lsGet(LS_KEY).lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val i = line.lastIndexOf(',')
        if (i < 0) null else HighScore(line.substring(0, i), line.substring(i + 1).toIntOrNull() ?: 0)
    }.sortedByDescending { it.score }.take(5)
}.getOrDefault(emptyList())

actual fun persistScore(name: String, score: Int): Unit = runCatching {
    val top5 = (loadScores() + HighScore(name.take(12), score)).sortedByDescending { it.score }.take(5)
    lsSet(LS_KEY, top5.joinToString("\n") { "${it.name},${it.score}" })
}.let {}

// ── Twemoji image loading ──────────────────────────────────────────────────────

// Pure Kotlin surrogate-pair → code point (String.codePointAt is JVM-only)
private fun String.firstCodePoint(): Int {
    val hi = this[0].code
    if (hi < 0xD800 || hi > 0xDBFF || length < 2) return hi
    return 0x10000 + ((hi - 0xD800) shl 10) + (this[1].code - 0xDC00)
}

private fun twemojiUrl(emoji: String) =
    "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/${emoji.firstCodePoint().toString(16)}.png"

// Fetch PNG bytes from a URL using browser fetch(), return as JS Int8Array
@JsFun("(url) => fetch(url, {mode:'cors'}).then(function(r){return r.arrayBuffer();}).then(function(b){return new Int8Array(b);})")
private external fun jsFetchInt8Array(url: String): Promise<JsAny>

@JsFun("(a) => a.length") private external fun jsLen(a: JsAny): Int
@JsFun("(a, i) => a[i]") private external fun jsGet(a: JsAny, i: Int): Int

private val emojiCache = mutableMapOf<String, ImageBitmap?>()

private suspend fun loadEmojiBitmap(emoji: String): ImageBitmap? {
    emojiCache[emoji]?.let { return it }
    val bitmap = runCatching {
        val jsArr: JsAny = jsFetchInt8Array(twemojiUrl(emoji)).await()
        val bytes = ByteArray(jsLen(jsArr)) { i -> jsGet(jsArr, i).toByte() }
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
    emojiCache[emoji] = bitmap
    return bitmap
}

@Composable
actual fun PieceCell(piece: String, modifier: Modifier) {
    var bitmap by remember(piece) { mutableStateOf(emojiCache[piece]) }
    LaunchedEffect(piece) {
        if (!emojiCache.containsKey(piece)) bitmap = loadEmojiBitmap(piece)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!, contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit)
        }
        // blank while loading — cell colour already shows, image pops in
    }
}
