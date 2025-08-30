// app/Main.kt
package app

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import java.nio.file.*
import java.time.Instant

private const val CYAN = "\u001B[36m"
private const val MAGENTA = "\u001B[35m"
private const val RESET = "\u001B[0m"

fun main(args: Array<String>) {
    val root = Root().subcommands(ScanCmd(), CheckCmd(), UploadCmd())

    val interactive = System.console() != null
    val wantsHelp = args.any { it == "-h" || it == "--help" || it == "--version" }
    val colorsEnabled = interactive && System.getenv("NO_COLOR") == null && System.getenv("CI") == null

    if (interactive && !wantsHelp) {
        if (FirstRun.isFirstRun()) {
            Banner.print(colorsEnabled)
            FirstRun.markRan()
        } else if (args.isEmpty()) {
            Banner.print(colorsEnabled)
        }
    }

    root.main(args)
}

// --- Banner loader/renderer ---
object Banner {
    fun print(colors: Boolean) {
        val text = object {}.javaClass.getResource("/banner.txt")!!.readText()
        val lines = text.lines()
        val art = lines.dropLast(1).joinToString("\n")
        val tagline = lines.lastOrNull().orEmpty()

        if (colors) {
            println(CYAN + art + RESET)
            println(MAGENTA + tagline + RESET)
        } else {
            println(art)
            println(tagline)
        }
    }
}

// --- First-run marker in an OS-appropriate state dir ---
object FirstRun {
    private fun stateDir(): Path {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> Paths.get(System.getenv("LocalAppData") ?: System.getenv("AppData") ?: home, "knitscope")
            os.contains("mac") -> Paths.get(home, "Library", "Application Support", "knitscope")
            else -> Paths.get(System.getenv("XDG_STATE_HOME") ?: "$home/.local/state", "knitscope")
        }
    }
    private val marker: Path = stateDir().resolve("first-run")

    fun isFirstRun(): Boolean = try { !Files.exists(marker) } catch (_: Exception) { true }

    fun markRan() {
        try {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, "first run at ${Instant.now()}")
        } catch (_: Exception) { /* best-effort */ }
    }
}
