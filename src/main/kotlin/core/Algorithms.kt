package core

data class Scc(val nodes: Set<String>)

object Algorithms {
    // Tarjan SCC on a directed graph: nodes = ids, edges = from->to (only use “structural” edges)
    fun stronglyConnectedComponents(edges: List<Edge>): List<Scc> {
        val g = mutableMapOf<String, MutableList<String>>()
        edges.forEach { g.computeIfAbsent(it.from) { mutableListOf() }.add(it.to) }
        // Ensure all nodes appear
        edges.forEach { g.putIfAbsent(it.to, mutableListOf()) }

        var index = 0
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val idx = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val sccs = mutableListOf<Scc>()

        fun dfs(v: String) {
            idx[v] = index
            low[v] = index
            index++
            stack.addFirst(v); onStack.add(v)

            for (w in g[v].orEmpty()) {
                if (w !in idx) {
                    dfs(w); low[v] = minOf(low[v]!!, low[w]!!)
                } else if (w in onStack) {
                    low[v] = minOf(low[v]!!, idx[w]!!)
                }
            }
            if (low[v] == idx[v]) {
                val comp = mutableSetOf<String>()
                while (true) {
                    val w = stack.removeFirst()
                    onStack.remove(w)
                    comp.add(w)
                    if (w == v) break
                }
                if (comp.size > 1) sccs.add(Scc(comp))
            }
        }

        g.keys.forEach { if (it !in idx) dfs(it) }
        return sccs
    }

    data class Degree(val inDeg: Int, val outDeg: Int)
    fun degrees(edges: List<Edge>): Map<String, Degree> {
        val inMap = mutableMapOf<String, Int>()
        val outMap = mutableMapOf<String, Int>()
        edges.forEach {
            outMap[it.from] = (outMap[it.from] ?: 0) + 1
            inMap[it.to] = (inMap[it.to] ?: 0) + 1
        }
        val nodes = (inMap.keys + outMap.keys).toSet()
        return nodes.associateWith { n -> Degree(inMap[n] ?: 0, outMap[n] ?: 0) }
    }
}
