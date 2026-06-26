@file:Suppress("OPT_IN_USAGE")

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
