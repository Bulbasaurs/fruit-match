import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlin.math.abs

// ── Platform expect declarations ──────────────────────────────────────────────
data class HighScore(val name: String, val score: Int)

expect fun generateSeed(): Long
expect fun copyToClipboard(text: String)
expect fun loadScores(): List<HighScore>
expect fun persistScore(name: String, score: Int)

// Desktop: renders system emoji via Text. Web: loads PNG from Twemoji CDN.
@Composable
expect fun PieceCell(piece: String, modifier: Modifier = Modifier)

// ── Pure Kotlin Base64-URL (no padding, no platform deps) ─────────────────────
private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private val B64_REV   = IntArray(128) { -1 }.also { B64.forEachIndexed { i, c -> it[c.code] = i } }

private fun b64Encode(bytes: ByteArray): String = buildString {
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i+1 < bytes.size) bytes[i+1].toInt() and 0xFF else 0
        val b2 = if (i+2 < bytes.size) bytes[i+2].toInt() and 0xFF else 0
        append(B64[b0 shr 2])
        append(B64[((b0 and 0x3) shl 4) or (b1 shr 4)])
        if (i+1 < bytes.size) append(B64[((b1 and 0xF) shl 2) or (b2 shr 6)])
        if (i+2 < bytes.size) append(B64[b2 and 0x3F])
        i += 3
    }
}

private fun b64Decode(s: String): ByteArray {
    val out = ArrayList<Byte>(s.length * 3 / 4)
    var i = 0
    while (i < s.length) {
        val c0 = s[i].code.let { if (it < 128) B64_REV[it] else -1 }; if (c0 < 0) break
        val c1 = if (i+1 < s.length) s[i+1].code.let { if (it < 128) B64_REV[it] else 0 } else 0
        val c2 = if (i+2 < s.length) s[i+2].code.let { if (it < 128) B64_REV[it] else -1 } else -1
        val c3 = if (i+3 < s.length) s[i+3].code.let { if (it < 128) B64_REV[it] else -1 } else -1
        out.add(((c0 shl 2) or (c1 shr 4)).toByte())
        if (c2 >= 0) out.add(((c1 shl 4) or (c2 shr 2)).toByte())
        if (c3 >= 0) out.add(((c2 shl 2) or c3).toByte())
        i += 4
    }
    return out.toByteArray()
}

// ── Seeded RNG (XorShift64, pure Kotlin) ─────────────────────────────────────
class SeededRandom(val seed: Long) {
    private var state = if (seed == 0L) 1L else seed
    private fun nextLong(): Long { var x = state; x = x xor (x shl 13); x = x xor (x shr 7); x = x xor (x shl 17); state = x; return x }
    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]
}

// ── Move encoding ─────────────────────────────────────────────────────────────
data class Move(val src: Int, val dir: Int)  // dir: 0=right 1=left 2=down 3=up

fun dirFromSrcDst(src: Int, dst: Int) = when (dst - src) { 1 -> 0; -1 -> 1; GRID -> 2; else -> 3 }
fun dstFromMove(m: Move) = m.src + when (m.dir) { 0 -> 1; 1 -> -1; 2 -> GRID; else -> -GRID }

fun encodeChallenge(seed: Long, moves: List<Move>): String {
    val seedStr = seed.toString(36).uppercase().padStart(7, '0')
    val bytes   = ByteArray(moves.size) { i -> ((moves[i].src shl 2) or moves[i].dir).toByte() }
    return "$seedStr:${b64Encode(bytes)}"
}

fun decodeChallenge(code: String): Pair<Long, List<Move>>? = runCatching {
    val clean = code.trim(); val colon = clean.indexOf(':'); if (colon < 0) return null
    val seed  = clean.substring(0, colon).toLong(36)
    val bytes = b64Decode(clean.substring(colon + 1))
    val moves = bytes.map { b -> val v = b.toInt() and 0xFF; Move(v ushr 2, v and 0x3) }
    seed to moves
}.getOrNull()

// ── Constants ─────────────────────────────────────────────────────────────────
const val GRID            = 8
const val CELL_DP         = 50
const val CHALLENGE_MOVES = 30

val PIECES  = listOf("🍓", "🍊", "🍌", "🍏", "🫐", "🍇")
const val BOMB    = "💣"
const val RAINBOW = "🌈"

fun isSpecial(p: String) = p == BOMB || p == RAINBOW

fun piecesForMoves(movesMade: Int) = when {
    movesMade < 10 -> PIECES.take(4)
    movesMade < 20 -> PIECES.take(5)
    else           -> PIECES
}

// ── Game modes ────────────────────────────────────────────────────────────────
enum class GameMode { ENDLESS, CHALLENGE, STORY }
data class FruitGoal(val fruit: String, val required: Int)
data class StoryLevel(val number: Int, val title: String, val goals: List<FruitGoal>, val moveLimit: Int, val piecesCount: Int)

val STORY_LEVELS = listOf(
    StoryLevel(1, "Fresh Start",  listOf(FruitGoal("🍓", 8)),                                                    20, 4),
    StoryLevel(2, "Citrus Grove", listOf(FruitGoal("🍊", 8),  FruitGoal("🍌", 6)),                              22, 4),
    StoryLevel(3, "Mixed Bag",    listOf(FruitGoal("🍏", 10), FruitGoal("🍓", 8)),                               22, 5),
    StoryLevel(4, "Berry Good",   listOf(FruitGoal("🫐", 12), FruitGoal("🍊", 10)),                             20, 5),
    StoryLevel(5, "Full Basket",  listOf(FruitGoal("🍇", 12), FruitGoal("🍓", 10), FruitGoal("🍌", 8)),         20, 6),
    StoryLevel(6, "Grand Finale", listOf(FruitGoal("🍇", 15), FruitGoal("🫐", 12), FruitGoal("🍓", 10), FruitGoal("🍊", 8)), 18, 6),
)

// ── Grid helpers ──────────────────────────────────────────────────────────────
fun idx(r: Int, c: Int) = r * GRID + c

fun buildGrid(pieces: List<String>, rng: SeededRandom): List<String> {
    val g = MutableList(GRID * GRID) { rng.pick(pieces) }
    repeat(20) {
        for (r in 0 until GRID) for (c in 0 until GRID) {
            val h = c >= 2 && g[idx(r,c)] == g[idx(r,c-1)] && g[idx(r,c)] == g[idx(r,c-2)]
            val v = r >= 2 && g[idx(r,c)] == g[idx(r-1,c)] && g[idx(r,c)] == g[idx(r-2,c)]
            if (h || v) { val others = pieces.filter { it != g[idx(r,c)] }; g[idx(r,c)] = rng.pick(others.ifEmpty { pieces }) }
        }
    }
    return g
}

fun makeGrid(pieces: List<String>, rng: SeededRandom): List<String> {
    var g = buildGrid(pieces, rng); while (!anyValidMove(g)) g = buildGrid(pieces, rng); return g
}

fun findMatches(grid: List<String?>): Set<Int> {
    val out = mutableSetOf<Int>()
    for (r in 0 until GRID) { var c = 0; while (c < GRID) { val p = grid[idx(r,c)]; if (p == null || isSpecial(p)) { c++; continue }; var n = 1; while (c+n < GRID && grid[idx(r,c+n)] == p) n++; if (n >= 3) (c until c+n).forEach { out += idx(r, it) }; c += n } }
    for (c in 0 until GRID) { var r = 0; while (r < GRID) { val p = grid[idx(r,c)]; if (p == null || isSpecial(p)) { r++; continue }; var n = 1; while (r+n < GRID && grid[idx(r+n,c)] == p) n++; if (n >= 3) (r until r+n).forEach { out += idx(it, c) }; r += n } }
    return out
}

data class MatchRun(val cells: List<Int>)

fun findMatchRuns(grid: List<String?>): List<MatchRun> {
    val runs = mutableListOf<MatchRun>()
    for (r in 0 until GRID) { var c = 0; while (c < GRID) { val p = grid[idx(r,c)]; if (p == null || isSpecial(p)) { c++; continue }; var n = 1; while (c+n < GRID && grid[idx(r,c+n)] == p) n++; if (n >= 3) runs.add(MatchRun((c until c+n).map { idx(r, it) })); c += n } }
    for (c in 0 until GRID) { var r = 0; while (r < GRID) { val p = grid[idx(r,c)]; if (p == null || isSpecial(p)) { r++; continue }; var n = 1; while (r+n < GRID && grid[idx(r+n,c)] == p) n++; if (n >= 3) runs.add(MatchRun((r until r+n).map { idx(it, c) })); r += n } }
    return runs
}

fun processRuns(runs: List<MatchRun>, preferredCell: Int?): Triple<Set<Int>, Map<Int, String>, Set<Int>> {
    val toClear = mutableSetOf<Int>(); val toPlace = mutableMapOf<Int, String>()
    runs.sortedByDescending { it.cells.size }.forEach { run ->
        val special = when { run.cells.size >= 5 -> RAINBOW; run.cells.size == 4 -> BOMB; else -> null }
        if (special != null) {
            val placeAt = run.cells.firstOrNull { it == preferredCell && !toClear.contains(it) } ?: run.cells.getOrNull(run.cells.size / 2) ?: run.cells.first()
            if (!toPlace.containsKey(placeAt) || (special == RAINBOW && toPlace[placeAt] == BOMB)) toPlace[placeAt] = special
            run.cells.filter { it != placeAt && !toPlace.containsKey(it) }.forEach { toClear.add(it) }
        } else { run.cells.filter { !toPlace.containsKey(it) }.forEach { toClear.add(it) } }
    }
    toClear.removeAll(toPlace.keys)
    return Triple(toClear, toPlace, toClear + toPlace.keys)
}

// BFS bomb chain: starting from one bomb, expands to any bomb within the blast radius,
// repeating until no new bombs are found. Returns all cells to clear.
fun bombChainExplosion(startPos: Int, grid: List<String>): Set<Int> {
    val pending = ArrayDeque(listOf(startPos))
    val visited = mutableSetOf<Int>()
    val cleared = mutableSetOf<Int>()
    while (pending.isNotEmpty()) {
        val pos = pending.removeFirst()
        if (pos in visited) continue
        visited.add(pos)
        val br = pos / GRID; val bc = pos % GRID
        for (dr in -1..1) for (dc in -1..1) {
            val nr = br + dr; val nc = bc + dc
            if (nr in 0 until GRID && nc in 0 until GRID) {
                val cell = idx(nr, nc)
                cleared.add(cell)
                if (grid[cell] == BOMB && cell !in visited) pending.add(cell)
            }
        }
    }
    return cleared
}

fun sparklePositions(runs: List<MatchRun>): List<Pair<Float, Float>> {
    val out = mutableListOf<Pair<Float, Float>>()
    runs.filter { it.cells.size >= 4 }.forEach { run -> out.add(run.cells.map { it / GRID }.average().toFloat() to run.cells.map { it % GRID }.average().toFloat()) }
    val count = mutableMapOf<Int, Int>(); runs.forEach { run -> run.cells.forEach { count[it] = (count[it] ?: 0) + 1 } }
    count.filter { it.value >= 2 }.keys.forEach { cell -> out.add((cell / GRID).toFloat() to (cell % GRID).toFloat()) }
    return out.distinctBy { (r, c) -> r.toInt() * GRID + c.toInt() }
}

fun gravityFillWithDistances(grid: List<String?>, pieces: List<String>, rng: SeededRandom): Pair<List<String>, Map<Int, Int>> {
    val out = arrayOfNulls<String>(GRID * GRID); val fallRows = mutableMapOf<Int, Int>()
    for (c in 0 until GRID) {
        val existing = (0 until GRID).mapNotNull { r -> grid[idx(r, c)]?.let { r to it } }
        val newCount = GRID - existing.size
        for (i in 0 until newCount) { out[idx(i, c)] = rng.pick(pieces); fallRows[idx(i, c)] = newCount - i }
        existing.forEachIndexed { i, (oldRow, piece) -> val newRow = newCount + i; out[idx(newRow, c)] = piece; val fell = newRow - oldRow; if (fell > 0) fallRows[idx(newRow, c)] = fell }
    }
    return Pair(out.map { it!! }, fallRows)
}

fun anyValidMove(grid: List<String>): Boolean {
    if (grid.any { isSpecial(it) }) return true
    for (r in 0 until GRID) for (c in 0 until GRID) {
        if (c+1 < GRID) { val g = grid.toMutableList(); val t = g[idx(r,c)]; g[idx(r,c)] = g[idx(r,c+1)]; g[idx(r,c+1)] = t; if (findMatches(g).isNotEmpty()) return true }
        if (r+1 < GRID) { val g = grid.toMutableList(); val t = g[idx(r,c)]; g[idx(r,c)] = g[idx(r+1,c)]; g[idx(r+1,c)] = t; if (findMatches(g).isNotEmpty()) return true }
    }
    return false
}

// ── Pure replay engine ────────────────────────────────────────────────────────
fun simulateMove(grid: List<String>, src: Int, dst: Int, rng: SeededRandom, movesMade: Int): Pair<List<String>, Int>? {
    val srcPiece = grid[src]; val dstPiece = grid[dst]
    val srcSpecial = isSpecial(srcPiece); val dstSpecial = isSpecial(dstPiece)
    val swapped = grid.toMutableList(); val tmp = swapped[src]; swapped[src] = swapped[dst]; swapped[dst] = tmp
    if (!srcSpecial && !dstSpecial && findMatches(swapped).isEmpty()) return null
    val pieces = piecesForMoves(movesMade); var cur: List<String> = swapped; var pts = 0
    if (srcSpecial || dstSpecial) {
        val ss = mutableSetOf<Int>()
        if (srcPiece == BOMB || dstPiece == BOMB) { ss.addAll(bombChainExplosion(if (srcPiece == BOMB) dst else src, cur)) }
        if (srcPiece == RAINBOW || dstPiece == RAINBOW) { val tf = if (srcPiece == RAINBOW) dstPiece else srcPiece; if (!isSpecial(tf)) cur.forEachIndexed { i, p -> if (p == tf) ss.add(i) }; ss.add(if (srcPiece == RAINBOW) dst else src) }
        if (ss.isNotEmpty()) { pts += ss.size * 10; val wg = cur.map<String, String?> { it }.toMutableList(); ss.forEach { i -> wg[i] = null }; cur = gravityFillWithDistances(wg, pieces, rng).first }
    }
    var fmc: Int? = if (!srcSpecial && !dstSpecial) dst else null
    while (true) {
        val runs = findMatchRuns(cur); if (runs.isEmpty()) break
        val (toClear, toPlace, allAffected) = processRuns(runs, fmc); fmc = null; pts += allAffected.size * 10
        val wg = cur.map<String, String?> { it }.toMutableList(); toClear.forEach { i -> wg[i] = null }; toPlace.forEach { (i, p) -> wg[i] = p }
        cur = gravityFillWithDistances(wg, pieces, rng).first
    }
    return cur to pts
}

fun replayChallenge(seed: Long, moves: List<Move>): Int {
    val rng = SeededRandom(seed); var grid = makeGrid(piecesForMoves(0), rng); var score = 0
    moves.forEachIndexed { i, move ->
        val dst = dstFromMove(move); if (dst < 0 || dst >= GRID * GRID) return@forEachIndexed
        val (newGrid, pts) = simulateMove(grid, move.src, dst, rng, i + 1) ?: return@forEachIndexed
        grid = newGrid; score += pts
    }
    return score
}

// ── Y2K Baby Palette ──────────────────────────────────────────────────────────
val BG           = Color(0xFFFFD6EC)
val GRID_BG      = Color(0xFFFFD6EC)
val CELL_IDLE    = Color(0xFFD4EDFF)
val CELL_SEL     = Color(0xFFFF7BAC)
val CELL_HIT     = Color(0xFFFF1F7A)
val CELL_BDR     = Color(0xFF9DD8FF)
val CELL_BDR_SEL = Color(0xFFFFFFFF)
val TOOLBAR_BG   = Color(0xFFBAE8FF)
val HOT_PINK     = Color(0xFFFF2475)
val DEEP_PINK    = Color(0xFF8B0045)
val BABY_BLUE    = Color(0xFF60CDFF)
val LAVENDER     = Color(0xFFCC99FF)
val STAR_YELLOW  = Color(0xFFFFE84D)
val PIXEL_FONT   = FontFamily.Monospace

val BG_DOTS = listOf(0.06f to 0.08f, 0.88f to 0.12f, 0.18f to 0.92f, 0.82f to 0.88f, 0.5f to 0.04f, 0.04f to 0.5f, 0.96f to 0.5f, 0.5f to 0.96f, 0.3f to 0.3f, 0.7f to 0.3f, 0.3f to 0.7f, 0.7f to 0.7f, 0.15f to 0.18f, 0.85f to 0.75f, 0.42f to 0.55f)

fun Modifier.y2kDots() = drawBehind {
    BG_DOTS.forEach { (rx, ry) ->
        drawCircle(HOT_PINK.copy(alpha = 0.08f), 7.dp.toPx(), Offset(size.width * rx, size.height * ry))
        drawCircle(BABY_BLUE.copy(alpha = 0.10f), 4.5f.dp.toPx(), Offset(size.width * rx + 12.dp.toPx(), size.height * ry + 10.dp.toPx()))
    }
}

// ── Overlays ──────────────────────────────────────────────────────────────────
data class SparkleEntry(val id: Int, val centerRow: Float, val centerCol: Float)
private val BURST_DIRS = listOf(0f to -1f, 0.707f to -0.707f, 1f to 0f, 0.707f to 0.707f, 0f to 1f, -0.707f to 0.707f, -1f to 0f, -0.707f to -0.707f)

@Composable
fun SparkleOverlay(entry: SparkleEntry, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(entry.id) { progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing)); onDone() }
    val p = progress.value; val alpha = (1f - p * 1.4f).coerceIn(0f, 1f); val dist = p * 32f
    val gridDp = GRID * CELL_DP
    val xBase = -gridDp / 2f + (entry.centerCol + 0.5f) * CELL_DP
    val yBase = -gridDp / 2f + (entry.centerRow + 0.5f) * CELL_DP
    Box(Modifier.fillMaxSize()) {
        BURST_DIRS.forEach { (dx, dy) ->
            Text("★", fontSize = 13.sp, color = STAR_YELLOW, modifier = Modifier.align(Alignment.Center)
                .offset(x = (xBase + dx * dist).dp, y = (yBase + dy * dist).dp)
                .graphicsLayer(alpha = alpha, scaleX = 1f + p * 0.5f, scaleY = 1f + p * 0.5f))
        }
    }
}

data class PopEntry(val id: Int, val label: String, val centerRow: Float, val centerCol: Float)

@Composable
fun FloatingPop(pop: PopEntry, onDone: () -> Unit) {
    val alpha = remember { Animatable(0f) }; val yShift = remember { Animatable(0f) }
    LaunchedEffect(pop.id) { launch { alpha.animateTo(1f, tween(100)); delay(500); alpha.animateTo(0f, tween(400)) }; yShift.animateTo(-60f, tween(1000, easing = FastOutSlowInEasing)); onDone() }
    val gridDp = GRID * CELL_DP
    Box(Modifier.fillMaxSize()) {
        Text("★ ${pop.label} ★", color = HOT_PINK, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT,
            modifier = Modifier.align(Alignment.Center)
                .offset(x = (-gridDp / 2f + (pop.centerCol + 0.5f) * CELL_DP).dp, y = (-gridDp / 2f + (pop.centerRow + 0.5f) * CELL_DP + yShift.value).dp)
                .graphicsLayer(alpha = alpha.value))
    }
}

// ── Dialog ────────────────────────────────────────────────────────────────────
@Composable
fun PixelDialog(
    title: String, titleColor: Color = HOT_PINK, containerColor: Color = Color(0xFFFFE4F3),
    content: @Composable ColumnScope.() -> Unit,
    confirmText: String, onConfirm: () -> Unit,
    dismissText: String? = null, onDismiss: (() -> Unit)? = null,
    extraButton: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(14.dp), color = containerColor, border = BorderStroke(2.dp, HOT_PINK.copy(alpha = 0.4f)), modifier = Modifier.widthIn(min = 260.dp, max = 380.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontFamily = PIXEL_FONT, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp)); content(); Spacer(Modifier.height(14.dp))
                extraButton?.invoke(); if (extraButton != null) Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (dismissText != null && onDismiss != null) { TextButton(onClick = onDismiss) { Text(dismissText, fontFamily = PIXEL_FONT, color = DEEP_PINK) }; Spacer(Modifier.width(6.dp)) }
                    Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = HOT_PINK), shape = RoundedCornerShape(8.dp)) { Text(confirmText, fontFamily = PIXEL_FONT, fontWeight = FontWeight.Black, color = Color.White) }
                }
            }
        }
    }
}

// ── Toolbar helpers ───────────────────────────────────────────────────────────
@Composable fun ToolDivider() = Box(Modifier.width(1.dp).height(40.dp).padding(horizontal = 6.dp).background(HOT_PINK.copy(alpha = 0.25f)))

@Composable
fun StatBox(label: String, value: String, valueColor: Color = DEEP_PINK) {
    Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = HOT_PINK, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = PIXEL_FONT, letterSpacing = 1.sp)
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT)
    }
}

// ── Animated bubble background (mode select) ─────────────────────────────────
private data class BubbleSpec(val xFrac: Float, val yFrac: Float, val radiusDp: Float, val isPink: Boolean)
private data class PopSpark(val xFrac: Float, val yFrac: Float, val progress: Animatable<Float, AnimationVector1D>)
private val BUBBLES = listOf(
    BubbleSpec(0.07f, 0.18f, 22f, true),  BubbleSpec(0.23f, 0.73f, 16f, false),
    BubbleSpec(0.48f, 0.06f, 20f, true),  BubbleSpec(0.72f, 0.32f, 14f, false),
    BubbleSpec(0.90f, 0.65f, 24f, true),  BubbleSpec(0.15f, 0.50f, 18f, false),
    BubbleSpec(0.60f, 0.88f, 15f, true),  BubbleSpec(0.38f, 0.40f, 19f, false),
    BubbleSpec(0.83f, 0.12f, 17f, true),  BubbleSpec(0.05f, 0.80f, 21f, false),
)
private val EASE_IN_OUT_SINE = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

@Composable
fun AnimatedBubbleBackground(modifier: Modifier = Modifier) {
    val yOff  = remember { BUBBLES.map { Animatable(0f) } }
    val alpha = remember { BUBBLES.map { Animatable(1f) } }
    val scale = remember { BUBBLES.map { Animatable(1f) } }
    val alive = remember { mutableStateListOf<Boolean>().also { l -> repeat(BUBBLES.size) { l.add(true) } } }
    val sparks = remember { mutableStateListOf<PopSpark>() }
    val scope  = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Float each bubble at its own pace
        BUBBLES.indices.forEach { i ->
            launch {
                delay(i * 150L)
                val period = 2200 + i * 280   // 2.2 – 4.7 s per half-cycle → gentle drift
                val amp    = 8f + (i % 4) * 6f
                while (true) {
                    yOff[i].animateTo( amp, tween(period, easing = EASE_IN_OUT_SINE))
                    yOff[i].animateTo(-amp, tween(period, easing = EASE_IN_OUT_SINE))
                }
            }
        }
        // Pop scheduler: randomly pop a bubble every 1-3 seconds
        delay(2000)
        while (true) {
            delay(kotlin.random.Random.nextLong(2000, 4500))
            val candidates = BUBBLES.indices.filter { alive[it] }
            if (candidates.isEmpty()) continue
            val i = candidates.random()
            alive[i] = false
            launch {
                scale[i].animateTo(1.45f, tween(200))          // scale-up
                alpha[i].animateTo(0f, tween(280))              // fade out

                val b = BUBBLES[i]                              // spawn sparkle burst
                val sp = PopSpark(b.xFrac, b.yFrac, Animatable(0f))
                sparks.add(sp)
                launch { sp.progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)); sparks.remove(sp) }

                delay(1300)                                    // respawn
                scale[i].snapTo(0.05f); alpha[i].snapTo(0f); yOff[i].snapTo(0f)
                launch { scale[i].animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 140f)) }
                alpha[i].animateTo(1f, tween(450))
                alive[i] = true
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        BUBBLES.forEachIndexed { i, b ->
            val cx  = size.width  * b.xFrac
            val cy  = size.height * b.yFrac + yOff[i].value.dp.toPx()
            val r   = b.radiusDp.dp.toPx() * scale[i].value
            val a   = alpha[i].value
            val col = if (b.isPink) HOT_PINK else BABY_BLUE
            drawCircle(col.copy(alpha = 0.14f * a), r, Offset(cx, cy))                        // fill
            drawCircle(col.copy(alpha = 0.22f * a), r, Offset(cx, cy), style = Stroke(1.5f.dp.toPx())) // ring
            drawCircle(Color.White.copy(alpha = 0.48f * a), r * 0.22f, Offset(cx - r * 0.28f, cy - r * 0.30f)) // shine
        }
        sparks.toList().forEach { sp ->
            val p   = sp.progress.value
            val cx  = size.width  * sp.xFrac
            val cy  = size.height * sp.yFrac
            val a   = (1f - p * 1.5f).coerceIn(0f, 1f)
            val d   = p * 30f.dp.toPx()
            BURST_DIRS.forEach { (dx, dy) ->
                drawCircle(Color.White.copy(alpha = a), (2.5f + p * 2f).dp.toPx(), Offset(cx + dx * d, cy + dy * d))
            }
        }
    }
}

// ── Mode select ───────────────────────────────────────────────────────────────
@Composable
fun ModeSelectScreen(onSelect: (GameMode) -> Unit) {
    Box(Modifier.fillMaxSize().background(BG)) {
        Box(Modifier.fillMaxSize().y2kDots())
        AnimatedBubbleBackground()
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("★ FRUIT MATCH ★", color = HOT_PINK, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text("choose ur mode  ♡", color = DEEP_PINK.copy(alpha = 0.65f), fontSize = 12.sp, fontFamily = PIXEL_FONT)
            Spacer(Modifier.height(32.dp))
            ModeCard("🎮", "ENDLESS",   "match n match til no moves remain\nvery zen, very chill, very demure",                                   Color(0xFFFFE4F3), HOT_PINK,  GameMode.ENDLESS,   onSelect)
            Spacer(Modifier.height(12.dp))
            ModeCard("🏆", "CHALLENGE", "$CHALLENGE_MOVES moves • challenge urself, ur friends, ur cat, the president, etc", Color(0xFFDFF3FF), BABY_BLUE, GameMode.CHALLENGE, onSelect)
            Spacer(Modifier.height(12.dp))
            ModeCard("📖", "STORY",     "${STORY_LEVELS.size} levels • achieve ur fruit dreams\ngoals get tougher each lvl tho",    Color(0xFFF0E4FF), LAVENDER,  GameMode.STORY,     onSelect)
            Spacer(Modifier.height(16.dp))
            Text("💣 match 4 to make the bomb  •  🌈 match 5 to taste the rainbow", color = DEEP_PINK.copy(alpha = 0.45f), fontSize = 9.sp, fontFamily = PIXEL_FONT)
            Spacer(Modifier.height(20.dp))
            Text("♡ ★ ♡ ★ ♡ ★ ♡ ★ ♡", color = HOT_PINK.copy(alpha = 0.3f), fontSize = 12.sp, fontFamily = PIXEL_FONT, letterSpacing = 3.sp)
        }
    }
}

@Composable
fun ModeCard(icon: String, title: String, description: String, cardBg: Color, borderColor: Color, mode: GameMode, onSelect: (GameMode) -> Unit) {
    Surface(onClick = { onSelect(mode) }, shape = RoundedCornerShape(10.dp), color = cardBg, border = BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)), modifier = Modifier.width(310.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 28.sp); Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = DEEP_PINK, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT, letterSpacing = 1.sp)
                Spacer(Modifier.height(2.dp))
                Text(description, color = DEEP_PINK.copy(alpha = 0.55f), fontSize = 10.sp, fontFamily = PIXEL_FONT)
            }
        }
    }
}

// ── Challenge sub-menu ────────────────────────────────────────────────────────
@Composable
fun ChallengeMenuScreen(onNewChallenge: (Long) -> Unit, onAcceptChallenge: (Long, Int) -> Unit, onBack: () -> Unit) {
    var acceptMode by remember { mutableStateOf(false) }
    var codeInput  by remember { mutableStateOf("") }
    var errorMsg   by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(BG).y2kDots()) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("🏆 CHALLENGE MODE", color = BABY_BLUE, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text("same seed = same board = fair fight ♡", color = DEEP_PINK.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = PIXEL_FONT)
            Spacer(Modifier.height(28.dp))
            if (!acceptMode) {
                Surface(onClick = { onNewChallenge(generateSeed()) }, shape = RoundedCornerShape(10.dp), color = Color(0xFFDFF3FF), border = BorderStroke(2.dp, BABY_BLUE.copy(alpha = 0.6f)), modifier = Modifier.width(310.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🆕", fontSize = 28.sp); Spacer(Modifier.width(12.dp))
                        Column { Text("NEW CHALLENGE", color = DEEP_PINK, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT); Spacer(Modifier.height(2.dp)); Text("play a fresh board\nget a code to challenge friends!", color = DEEP_PINK.copy(alpha = 0.55f), fontSize = 10.sp, fontFamily = PIXEL_FONT) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(onClick = { acceptMode = true }, shape = RoundedCornerShape(10.dp), color = Color(0xFFFFE4F3), border = BorderStroke(2.dp, HOT_PINK.copy(alpha = 0.6f)), modifier = Modifier.width(310.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📋", fontSize = 28.sp); Spacer(Modifier.width(12.dp))
                        Column { Text("ACCEPT CHALLENGE", color = DEEP_PINK, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT); Spacer(Modifier.height(2.dp)); Text("paste a friend's code\nplay their exact board!", color = DEEP_PINK.copy(alpha = 0.55f), fontSize = 10.sp, fontFamily = PIXEL_FONT) }
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFFE4F3), border = BorderStroke(2.dp, HOT_PINK.copy(alpha = 0.4f)), modifier = Modifier.width(310.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("PASTE UR FRIEND'S CODE:", color = HOT_PINK, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = PIXEL_FONT, letterSpacing = 1.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = codeInput, onValueChange = { codeInput = it; errorMsg = "" }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = PIXEL_FONT, fontSize = 13.sp), placeholder = { Text("e.g. 0X3F9KL:SGVsbG8...", fontFamily = PIXEL_FONT, fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
                        if (errorMsg.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(errorMsg, color = HOT_PINK, fontSize = 10.sp, fontFamily = PIXEL_FONT) }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { acceptMode = false; codeInput = ""; errorMsg = "" }) { Text("cancel", fontFamily = PIXEL_FONT, color = DEEP_PINK) }
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = {
                                val parsed = decodeChallenge(codeInput)
                                if (parsed == null) { errorMsg = "invalid code! check ur input ♡" }
                                else { val (seed, moves) = parsed; onAcceptChallenge(seed, replayChallenge(seed, moves)) }
                            }, colors = ButtonDefaults.buttonColors(containerColor = HOT_PINK), shape = RoundedCornerShape(8.dp)) { Text("start! →", fontFamily = PIXEL_FONT, fontWeight = FontWeight.Black, color = Color.White) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBack) { Text("← back", fontFamily = PIXEL_FONT, color = DEEP_PINK.copy(alpha = 0.7f)) }
        }
    }
}

// ── Game phase ────────────────────────────────────────────────────────────────
enum class Phase { IDLE, ANIMATING, GAME_OVER, LEVEL_COMPLETE, STORY_COMPLETE }

// ── Navigation ────────────────────────────────────────────────────────────────
sealed class AppScreen {
    object ModeSelect : AppScreen()
    object ChallengeMenu : AppScreen()
    data class Game(val mode: GameMode, val seed: Long, val opponentScore: Int? = null) : AppScreen()
}

// ── Root composable (entry point for all platforms) ───────────────────────────
@Composable
fun FruitMatchApp() {
    MaterialTheme {
        var screen by remember { mutableStateOf<AppScreen>(AppScreen.ModeSelect) }
        when (val s = screen) {
            is AppScreen.ModeSelect    -> ModeSelectScreen { mode ->
                screen = if (mode == GameMode.CHALLENGE) AppScreen.ChallengeMenu else AppScreen.Game(mode, generateSeed())
            }
            is AppScreen.ChallengeMenu -> ChallengeMenuScreen(
                onNewChallenge    = { seed -> screen = AppScreen.Game(GameMode.CHALLENGE, seed) },
                onAcceptChallenge = { seed, opp -> screen = AppScreen.Game(GameMode.CHALLENGE, seed, opp) },
                onBack            = { screen = AppScreen.ModeSelect }
            )
            is AppScreen.Game -> Match3App(mode = s.mode, seed = s.seed, opponentScore = s.opponentScore, onMenu = { screen = AppScreen.ModeSelect })
        }
    }
}

// ── Game screen ───────────────────────────────────────────────────────────────
@Composable
fun Match3App(mode: GameMode, seed: Long, opponentScore: Int? = null, onMenu: () -> Unit) {
    val density = LocalDensity.current.density
    val cellPx  = CELL_DP * density

    var rng by remember { mutableStateOf(SeededRandom(seed)) }
    val recordedMoves = remember { mutableListOf<Move>() }
    var codeCopied by remember { mutableStateOf(false) }
    var storyLevelIdx  by remember { mutableStateOf(0) }
    var storyProgress  by remember { mutableStateOf(mapOf<String, Int>()) }

    fun currentPieces(movesMade: Int): List<String> = when (mode) {
        GameMode.STORY -> PIECES.take(STORY_LEVELS[storyLevelIdx].piecesCount)
        else           -> piecesForMoves(movesMade)
    }

    var grid       by remember { mutableStateOf(makeGrid(currentPieces(0), rng)) }
    var dragSrc    by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var lit        by remember { mutableStateOf(emptySet<Int>()) }
    var score      by remember { mutableStateOf(0) }
    var phase      by remember { mutableStateOf(Phase.IDLE) }
    var highScores by remember { mutableStateOf(loadScores()) }
    var showDialog by remember { mutableStateOf(false) }
    var nameInput  by remember { mutableStateOf("") }
    var movesMade      by remember { mutableStateOf(0) }
    var maxCombo       by remember { mutableStateOf(1) }
    var lastLevelBonus by remember { mutableStateOf(0) }

    val yAnim         = remember { Array(GRID * GRID) { Animatable(0f) } }
    val popups        = remember { mutableStateListOf<PopEntry>() }
    var nextPopId     by remember { mutableStateOf(0) }
    val sparkles      = remember { mutableStateListOf<SparkleEntry>() }
    var nextSparkleId by remember { mutableStateOf(0) }
    val scope         = rememberCoroutineScope()
    var cascadeJob    by remember { mutableStateOf<Job?>(null) }

    val moveLimit: Int? = when (mode) {
        GameMode.ENDLESS   -> null
        GameMode.CHALLENGE -> CHALLENGE_MOVES
        GameMode.STORY     -> STORY_LEVELS[storyLevelIdx].moveLimit
    }
    val movesLeft = moveLimit?.let { it - movesMade }

    fun resetBoard(capturedRng: SeededRandom, pieces: List<String>, preserveScore: Boolean = false) {
        dragSrc = null; dragOffset = Offset.Zero; lit = emptySet()
        if (!preserveScore) score = 0
        movesMade = 0; maxCombo = 1; phase = Phase.IDLE; showDialog = false; nameInput = ""
        recordedMoves.clear(); codeCopied = false
        grid = makeGrid(pieces, capturedRng)
    }

    fun startLevel(idx: Int) {
        cascadeJob?.cancel(); storyLevelIdx = idx; storyProgress = emptyMap()
        val lr = SeededRandom(generateSeed())
        scope.launch { yAnim.forEach { it.snapTo(0f) }; resetBoard(lr, PIECES.take(STORY_LEVELS[idx].piecesCount), preserveScore = true) }
    }

    fun newGame() {
        cascadeJob?.cancel()
        val nr = SeededRandom(generateSeed()); rng = nr
        scope.launch {
            yAnim.forEach { it.snapTo(0f) }
            if (mode == GameMode.STORY) { storyLevelIdx = 0; storyProgress = emptyMap() }
            resetBoard(nr, currentPieces(0))
        }
    }

    suspend fun animateFalls(fallDist: Map<Int, Int>) = coroutineScope {
        fallDist.forEach { (ci, rows) -> launch { yAnim[ci].snapTo(-rows * cellPx); yAnim[ci].animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) } }
    }

    fun countGoal(cells: Collection<Int>, cur: List<String>, gf: Set<String>) {
        if (mode != GameMode.STORY || gf.isEmpty()) return
        val u = storyProgress.toMutableMap(); cells.forEach { i -> val f = cur.getOrNull(i); if (f != null && !isSpecial(f) && f in gf) u[f] = (u[f] ?: 0) + 1 }; storyProgress = u
    }

    fun attemptSwap(src: Int, dst: Int) {
        if (phase != Phase.IDLE) return
        val srcPiece = grid[src]; val dstPiece = grid[dst]
        val srcSp = isSpecial(srcPiece); val dstSp = isSpecial(dstPiece)
        val swapped = grid.toMutableList(); val tmp = swapped[src]; swapped[src] = swapped[dst]; swapped[dst] = tmp
        if (!srcSp && !dstSp && findMatches(swapped).isEmpty()) return
        recordedMoves.add(Move(src, dirFromSrcDst(src, dst)))
        movesMade++; val pieces = currentPieces(movesMade); val cr = rng
        phase = Phase.ANIMATING; grid = swapped
        cascadeJob = scope.launch {
            var cur: List<String> = swapped; var pts = 0; var cc = 0
            val gf = if (mode == GameMode.STORY) STORY_LEVELS[storyLevelIdx].goals.map { it.fruit }.toSet() else emptySet()
            if (srcSp || dstSp) {
                val ss = mutableSetOf<Int>()
                if (srcPiece == BOMB || dstPiece == BOMB) { ss.addAll(bombChainExplosion(if (srcPiece == BOMB) dst else src, cur)) }
                if (srcPiece == RAINBOW || dstPiece == RAINBOW) { val tf = if (srcPiece == RAINBOW) dstPiece else srcPiece; if (!isSpecial(tf)) cur.forEachIndexed { i, p -> if (p == tf) ss.add(i) }; ss.add(if (srcPiece == RAINBOW) dst else src) }
                if (ss.isNotEmpty()) {
                    pts += ss.size * 10; countGoal(ss, cur, gf)
                    val ar = ss.map { it/GRID }.average().toFloat(); val ac = ss.map { it%GRID }.average().toFloat(); sparkles.add(SparkleEntry(nextSparkleId++, ar, ac))
                    lit = ss; delay(380); val wg = cur.map<String,String?> { it }.toMutableList(); ss.forEach { i -> wg[i] = null }; lit = emptySet(); delay(60)
                    val (ng, fd) = gravityFillWithDistances(wg, pieces, cr); grid = ng; cur = ng; animateFalls(fd)
                }
            }
            var fmc: Int? = if (!srcSp && !dstSp) dst else null
            while (true) {
                val runs = findMatchRuns(cur); if (runs.isEmpty()) break
                val (toClear, toPlace, allAffected) = processRuns(runs, fmc); fmc = null
                cc++; pts += allAffected.size * 10; if (cc > maxCombo) maxCombo = cc
                countGoal(toClear, cur, gf)
                sparklePositions(runs).forEach { (r, c) -> sparkles.add(SparkleEntry(nextSparkleId++, r, c)) }
                if (cc >= 2) { val ar = allAffected.map { it/GRID }.average().toFloat(); val ac = allAffected.map { it%GRID }.average().toFloat(); popups.add(PopEntry(nextPopId++, "${cc}x", ar, ac)) }
                lit = allAffected; delay(380)
                val wg = cur.map<String,String?> { it }.toMutableList(); toClear.forEach { i -> wg[i] = null }; toPlace.forEach { (i, p) -> wg[i] = p }
                lit = emptySet(); delay(60); val (ng, fd) = gravityFillWithDistances(wg, pieces, cr); grid = ng; cur = ng; animateFalls(fd)
            }
            score += pts
            if (mode == GameMode.STORY) {
                val level = STORY_LEVELS[storyLevelIdx]
                if (level.goals.all { g -> (storyProgress[g.fruit] ?: 0) >= g.required }) {
                    val remaining = level.moveLimit - movesMade; val bonus = remaining * 50; lastLevelBonus = bonus; score += bonus
                    phase = if (storyLevelIdx + 1 >= STORY_LEVELS.size) Phase.STORY_COMPLETE else Phase.LEVEL_COMPLETE
                    showDialog = true; return@launch
                }
            }
            val outOfMoves = moveLimit != null && movesMade >= moveLimit
            if (outOfMoves || !anyValidMove(cur)) { showDialog = true; phase = Phase.GAME_OVER } else phase = Phase.IDLE
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(BG)) {
            // Toolbar
            Row(Modifier.fillMaxWidth().background(TOOLBAR_BG).border(BorderStroke(2.dp, HOT_PINK.copy(alpha = 0.25f))).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatBox("SCORE", "$score")
                when (mode) {
                    GameMode.STORY -> {
                        ToolDivider(); val level = STORY_LEVELS[storyLevelIdx]
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text("LV ${level.number}: ${level.title.uppercase()}", color = HOT_PINK, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = PIXEL_FONT, letterSpacing = 0.5.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                level.goals.forEach { g -> val got = (storyProgress[g.fruit] ?: 0).coerceAtMost(g.required); val done = got >= g.required; Text("${g.fruit} $got/${g.required}", color = if (done) Color(0xFF007B00) else DEEP_PINK, fontSize = 11.sp, fontFamily = PIXEL_FONT, fontWeight = if (done) FontWeight.Black else FontWeight.Normal) }
                            }
                        }
                        ToolDivider(); val ml = movesLeft ?: 0; StatBox("MOVES", "$ml", if (ml <= 5) HOT_PINK else DEEP_PINK)
                    }
                    else -> {
                        if (mode == GameMode.CHALLENGE) { ToolDivider(); val ml = movesLeft ?: 0; StatBox("MOVES", "$ml", if (ml <= 5) HOT_PINK else DEEP_PINK) }
                        if (opponentScore != null) {
                            ToolDivider(); val ahead = score - opponentScore; val ac = when { ahead > 0 -> Color(0xFF007B00); ahead < 0 -> HOT_PINK; else -> DEEP_PINK }
                            Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("FRIEND", color = HOT_PINK, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = PIXEL_FONT, letterSpacing = 1.sp)
                                Text("$opponentScore", color = DEEP_PINK, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = PIXEL_FONT)
                                val sign = if (ahead >= 0) "+" else ""; Text("${sign}${ahead}", color = ac, fontSize = 8.sp, fontFamily = PIXEL_FONT, fontWeight = FontWeight.Bold)
                            }
                        }
                        ToolDivider(); StatBox("BEST", "${maxCombo}x", if (maxCombo >= 3) Color(0xFFB8007A) else DEEP_PINK)
                        if (opponentScore == null) {
                            ToolDivider()
                            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                Text("★ TOP SCORES", color = HOT_PINK, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = PIXEL_FONT, letterSpacing = 0.5.sp)
                                if (highScores.isEmpty()) Text("no scores yet ♡", color = DEEP_PINK.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = PIXEL_FONT)
                                else highScores.forEachIndexed { i, hs -> Text("${i+1}. ${hs.name}  ${hs.score}", color = DEEP_PINK, fontSize = 9.sp, fontFamily = PIXEL_FONT, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        } else { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Button(onClick = ::newGame, colors = ButtonDefaults.buttonColors(containerColor = HOT_PINK), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(6.dp)) { Text("NEW", fontSize = 10.sp, fontFamily = PIXEL_FONT, fontWeight = FontWeight.Black, color = Color.White) }
                Spacer(Modifier.width(3.dp))
                TextButton(onClick = onMenu, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) { Text("MENU", fontSize = 10.sp, fontFamily = PIXEL_FONT, fontWeight = FontWeight.Bold, color = DEEP_PINK.copy(alpha = 0.7f)) }
            }

            // Grid
            Box(Modifier.fillMaxSize().background(BG).y2kDots(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(GRID_BG, RoundedCornerShape(8.dp)).border(2.dp, BABY_BLUE.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(6.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        for (r in 0 until GRID) {
                            Row {
                                for (c in 0 until GRID) {
                                    val i = idx(r, c); val inLit = i in lit
                                    val bgColor     by animateColorAsState(when { inLit -> CELL_HIT; dragSrc == i -> CELL_SEL; else -> CELL_IDLE }, tween(120), label = "bg$i")
                                    val borderColor by animateColorAsState(when { inLit -> CELL_BDR_SEL; dragSrc == i -> CELL_BDR_SEL; else -> CELL_BDR }, tween(120), label = "bd$i")
                                    val cellAlpha   by animateFloatAsState(if (inLit) 0f else 1f, tween(300, easing = FastOutSlowInEasing), label = "a$i")
                                    val cellScale   by animateFloatAsState(if (inLit) 0.6f else 1f, spring(0.4f, 500f), label = "s$i")
                                    val yOffset = yAnim[i].value
                                    Box(modifier = Modifier.size(CELL_DP.dp).padding(2.dp)
                                        .graphicsLayer(translationY = yOffset, alpha = cellAlpha, scaleX = cellScale, scaleY = cellScale)
                                        .clip(RoundedCornerShape(4.dp)).background(bgColor)
                                        .border(2.dp, borderColor, RoundedCornerShape(4.dp))
                                        .pointerInput(i) {
                                            detectDragGestures(
                                                onDragStart = { _ -> if (phase == Phase.IDLE) { dragSrc = i; dragOffset = Offset.Zero } },
                                                onDrag = { _, delta -> dragOffset = Offset(dragOffset.x + delta.x, dragOffset.y + delta.y) },
                                                onDragEnd = {
                                                    val s = dragSrc
                                                    if (s != null && phase == Phase.IDLE) {
                                                        val row = s/GRID; val col = s%GRID; val dx = dragOffset.x; val dy = dragOffset.y
                                                        val target = when { dx > 20f && abs(dx) > abs(dy) && col+1 < GRID -> idx(row,col+1); dx < -20f && abs(dx) > abs(dy) && col-1 >= 0 -> idx(row,col-1); dy > 20f && abs(dy) > abs(dx) && row+1 < GRID -> idx(row+1,col); dy < -20f && abs(dy) > abs(dx) && row-1 >= 0 -> idx(row-1,col); else -> null }
                                                        if (target != null) attemptSwap(s, target)
                                                    }
                                                    dragSrc = null; dragOffset = Offset.Zero
                                                },
                                                onDragCancel = { dragSrc = null; dragOffset = Offset.Zero }
                                            )
                                        }, contentAlignment = Alignment.Center
                                    ) { PieceCell(grid[i], Modifier.fillMaxSize()) }
                                }
                            }
                        }
                    }
                }
                sparkles.toList().forEach { sp -> key(sp.id) { SparkleOverlay(sp) { sparkles.remove(sp) } } }
                popups.toList().forEach  { pop -> key(pop.id) { FloatingPop(pop) { popups.remove(pop) } } }
            }
        }

        // Dialog overlay
        if (showDialog) {
            when (phase) {
                Phase.LEVEL_COMPLETE -> {
                    val nextLevel = STORY_LEVELS[storyLevelIdx + 1]
                    PixelDialog(title = "★ LEVEL ${storyLevelIdx + 1} CLEAR! ★",
                        content = {
                            if (lastLevelBonus > 0) { Text("speed bonus: +$lastLevelBonus pts ★", fontFamily = PIXEL_FONT, color = HOT_PINK, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("(${lastLevelBonus / 50} moves remaining)", fontFamily = PIXEL_FONT, color = DEEP_PINK.copy(alpha = 0.6f), fontSize = 10.sp); Spacer(Modifier.height(6.dp)) }
                            Text("score: $score\nnext: lv ${nextLevel.number} — ${nextLevel.title}", fontFamily = PIXEL_FONT, color = DEEP_PINK, fontSize = 12.sp)
                        },
                        confirmText = "next level →", onConfirm = { startLevel(storyLevelIdx + 1) }, dismissText = "menu", onDismiss = onMenu)
                }
                Phase.STORY_COMPLETE -> {
                    PixelDialog(title = "★ STORY COMPLETE ★",
                        content = {
                            if (lastLevelBonus > 0) { Text("final speed bonus: +$lastLevelBonus pts ★", fontFamily = PIXEL_FONT, color = HOT_PINK, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)) }
                            Text("u cleared all ${STORY_LEVELS.size} levels!! ♡\nfinal score: $score", fontFamily = PIXEL_FONT, color = DEEP_PINK, fontSize = 12.sp)
                        },
                        confirmText = "back to menu ♡", onConfirm = onMenu)
                }
                Phase.GAME_OVER -> {
                    val outOfMoves = moveLimit != null && movesMade >= moveLimit
                    val titleText  = if (outOfMoves) "OUT OF MOVES!" else "NO MORE MOVES!"
                    if (mode == GameMode.STORY) {
                        val level = STORY_LEVELS[storyLevelIdx]
                        PixelDialog(title = "★ $titleText", containerColor = Color(0xFFDFF3FF), titleColor = BABY_BLUE,
                            content = { Text("lv ${level.number}: ${level.title}", fontFamily = PIXEL_FONT, color = DEEP_PINK, fontWeight = FontWeight.Bold, fontSize = 12.sp); Spacer(Modifier.height(6.dp)); level.goals.forEach { g -> Text("${g.fruit}  ${(storyProgress[g.fruit] ?: 0).coerceAtMost(g.required)} / ${g.required}", fontFamily = PIXEL_FONT, color = DEEP_PINK, fontSize = 12.sp) } },
                            confirmText = "retry ↩", onConfirm = { startLevel(storyLevelIdx) }, dismissText = "menu", onDismiss = onMenu)
                    } else if (mode == GameMode.CHALLENGE && opponentScore != null) {
                        val youWin = score > opponentScore; val tied = score == opponentScore
                        PixelDialog(title = "★ $titleText",
                            content = {
                                Text(when { youWin -> "u win!! 🎉"; tied -> "it's a tie!! 🤝"; else -> "so close!! ♡" }, fontFamily = PIXEL_FONT, color = if (youWin) Color(0xFF007B00) else HOT_PINK, fontSize = 13.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.height(8.dp))
                                Text("you:    $score", fontFamily = PIXEL_FONT, color = DEEP_PINK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("friend: $opponentScore", fontFamily = PIXEL_FONT, color = DEEP_PINK.copy(alpha = 0.7f), fontSize = 13.sp)
                            },
                            confirmText = "play again ♡", onConfirm = { newGame() }, dismissText = "menu", onDismiss = onMenu)
                    } else {
                        val challengeCode = if (mode == GameMode.CHALLENGE) remember(rng.seed, recordedMoves.size) { encodeChallenge(rng.seed, recordedMoves.toList()) } else ""
                        PixelDialog(title = "★ $titleText",
                            content = {
                                Text("score: $score  •  best combo: ${maxCombo}x", fontFamily = PIXEL_FONT, color = DEEP_PINK, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                if (challengeCode.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp)); Text("ur challenge code:", fontFamily = PIXEL_FONT, color = HOT_PINK, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp); Spacer(Modifier.height(4.dp))
                                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFDFF3FF), border = BorderStroke(1.dp, BABY_BLUE.copy(alpha = 0.5f))) { Text(challengeCode, fontFamily = PIXEL_FONT, color = DEEP_PINK, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 2) }
                                }
                                Spacer(Modifier.height(10.dp)); Text("ur name:", fontFamily = PIXEL_FONT, color = DEEP_PINK, fontSize = 11.sp); Spacer(Modifier.height(4.dp))
                                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it.take(12) }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = PIXEL_FONT, fontSize = 13.sp), placeholder = { Text("player", fontFamily = PIXEL_FONT) })
                            },
                            confirmText = "save + play again ♡",
                            onConfirm = { persistScore(nameInput.trim().ifEmpty { "player" }, score); highScores = loadScores(); newGame() },
                            dismissText = "menu", onDismiss = onMenu,
                            extraButton = if (challengeCode.isNotEmpty()) ({
                                Button(onClick = { copyToClipboard(challengeCode); codeCopied = true; scope.launch { delay(2000); codeCopied = false } },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (codeCopied) Color(0xFF007B00) else BABY_BLUE),
                                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                                ) { Text(if (codeCopied) "copied! ♡" else "copy challenge code ★", fontFamily = PIXEL_FONT, fontWeight = FontWeight.Black, color = Color.White) }
                            }) else null
                        )
                    }
                }
                else -> {}
            }
        }
    }
}
