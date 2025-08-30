package app

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.core.Context
import core.Algorithms
import core.EdgeKind
import core.GraphJson
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import scan.AsmScanner
import java.io.File

val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

class ScanCmd : CliktCommand(name = "scan") {
    override fun help(context: Context) = "Scan compiled classes into knit-graph.json"

    private val out by option("--out").file().default(File("knit-graph.json"))
    private val classes by option("--classes").file(mustExist = true).multiple(required = true)
    private val module by option("--module").default("")

    override fun run() {
        val scanner = AsmScanner()
        val graphs = classes.map { f ->
            val p = f.toPath()
            when {
                f.isDirectory -> scanner.scanClassDir(p, module.ifBlank { null })
                f.isFile && f.name.endsWith(".jar", ignoreCase = true) -> scanner.scanJar(p, module.ifBlank { null })
                else -> error("Unsupported --classes path: ${f.absolutePath} (must be a directory or a .jar)")
            }
        }
        val merged = mergeGraphs(graphs)
        out.writeText(JSON.encodeToString(merged))
        echo("Wrote ${out.absolutePath} (types=${merged.types.size}, providers=${merged.providers.size}, consumers=${merged.consumers.size}, edges=${merged.edges.size})")
    }

    private fun mergeGraphs(graphs: List<GraphJson>): GraphJson {
        val types = linkedMapOf<String, core.TypeNode>()
        val providers = mutableListOf<core.ProviderNode>()
        val consumers = mutableListOf<core.ConsumerNode>()
        val edges = mutableListOf<core.Edge>()
        graphs.forEach { g ->
            g.types.forEach { types.putIfAbsent(it.id, it) }
            providers += g.providers
            consumers += g.consumers
            edges += g.edges
        }
        return GraphJson(types.values.toList(), providers, consumers, edges,
            GraphJson.Metrics(generatedAt = java.time.Instant.now().toString(),
                nodeCount = types.size + providers.size + consumers.size,
                edgeCount = edges.size))
    }
}

class CheckCmd : CliktCommand(name = "check") {
    override fun help(context: Context) = "Fail if graph regressions exceed thresholds"
    private val file by option("--file").file(mustExist = true).required()
    private val maxCycles by option("--max-cycles", metavar = "<number>").int().default(0)
    private val maxInDeg by option("--max-fan-in").int().default(200)

    override fun run() {
        val g = JSON.decodeFromString<GraphJson>(file.readText())
        // Consider only structural edges (needs + requires) for SCC
        val structural = g.edges.filter { it.kind == EdgeKind.needs || it.kind == EdgeKind.requires }
        val scc = Algorithms.stronglyConnectedComponents(structural)
        val deg = Algorithms.degrees(structural)

        var ok = true
        if (scc.size > maxCycles) {
            echo("❌ Cycles found: ${scc.size} > $maxCycles")
            scc.take(10).forEach { echo("  - SCC: ${it.nodes.take(10)}${if (it.nodes.size>10) "..." else ""}") }
            ok = false
        }
        val maxIn = deg.maxOfOrNull { it.value.inDeg } ?: 0
        if (maxIn > maxInDeg) {
            echo("❌ Max fan-in $maxIn > $maxInDeg")
            ok = false
        }
        if (!ok) {
            throw ProgramResult(2)
        } else {
            echo("✅ Check passed. Cycles=${scc.size}, Max fan-in=$maxIn")
        }
    }
}

class UploadCmd : CliktCommand(name = "upload") {
    override fun help(context: Context) = "Upload graph JSON to backend and print shareable ID/URL"
    private val file by option("--file").file(mustExist = true).required()
    private val base by option("--base-url").default("https://your-backend.example.com")

    override fun run() {
        val json = file.readText()
        val id = upload.Uploader(base).upload(json)
        echo("Share link: $base/graph/$id")
    }
}
