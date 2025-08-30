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
    val id: NodeId,            // e.g., "UserService.user"
    val needs: FqName,         // the needed type
    val owner: FqName,
    val module: String? = null
)

@Serializable
enum class EdgeKind { provides, requires, needs }

@Serializable
data class Edge(
    val from: NodeId,          // provider/consumer id
    val to: String,            // target node id or type id
    val kind: EdgeKind
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
