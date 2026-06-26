import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
actual fun PieceCell(piece: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(piece, fontSize = if (isSpecial(piece)) 22.sp else 26.sp, textAlign = TextAlign.Center)
    }
}

actual fun generateSeed(): Long = System.nanoTime().let { if (it == 0L) 1L else it }

actual fun copyToClipboard(text: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .setContents(java.awt.datatransfer.StringSelection(text), null)
}

private val SCORES_FILE = File(System.getProperty("user.home"), ".match3_scores.txt")

actual fun loadScores(): List<HighScore> = runCatching {
    SCORES_FILE.takeIf { it.exists() }?.readLines()
        ?.mapNotNull { line ->
            val i = line.lastIndexOf(',')
            if (i < 0) null else HighScore(line.substring(0, i), line.substring(i + 1).toIntOrNull() ?: 0)
        }
        ?.sortedByDescending { it.score }?.take(5) ?: emptyList()
}.getOrDefault(emptyList())

actual fun persistScore(name: String, score: Int): Unit = runCatching {
    val top5 = (loadScores() + HighScore(name.take(12), score)).sortedByDescending { it.score }.take(5)
    SCORES_FILE.writeText(top5.joinToString("\n") { "${it.name},${it.score}" })
}.let {}
