package core

import kotlinx.serialization.Serializable

typealias FqName = String
typealias NodeId = String

@Serializable
data class TypeNode(
    val id: FqName,
    val module: String? = null,
    val `package`: String? = null
)

@Serializable
data class ProviderNode(
    val id: NodeId,            // e.g., "User.<init>" or "UserService.name"
    val type: FqName,          // the provided type
    val owner: FqName,         // class that owns this provider
    val module: String? = null,
    val requires: List<FqName> = emptyList()
)

@Serializable
data class ConsumerNode(
    val id: String,
    val needs: String,
    val owner: String,
    val module: String? = null,
    val tags: List<String> = emptyList(),     // e.g. ["MULTI"]
    val provenance: String? = null            // e.g. "getStoreComponent.getFileSystem -> new FileSystem"
)

@Serializable
enum class EdgeKind { provides, requires, needs }

@Serializable
data class Edge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val attrs: Map<String, String>? = null
)

@Serializable
data class GraphJson(
    val types: List<TypeNode>,
    val providers: List<ProviderNode>,
    val consumers: List<ConsumerNode>,
    val edges: List<Edge>,
    val metrics: Metrics? = null
) {
    @Serializable
    data class Metrics(
        val generatedAt: String? = null,
        val moduleCount: Int? = null,
        val nodeCount: Int? = null,
        val edgeCount: Int? = null
    )
}
