package scan

import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.visibility
import kotlinx.metadata.jvm.Metadata as Km
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

object MetadataUtil {
    // Returns map: propertyName -> returnTypeFqn for delegated properties
    fun extractDelegatedProperties(classBytes: ByteArray): Map<String, String> {
        val cn = ClassNode()
        ClassReader(classBytes).accept(cn, 0)
        val md = cn.visibleAnnotations?.find { it.desc == "Lkotlin/Metadata;" } ?: return emptyMap()

        // Convert ASM annotation to KotlinClassMetadata
        val args = mutableMapOf<String, Any?>()
        md.values?.chunked(2)?.forEach { (k, v) -> args[k as String] = v }
        val header = Km(
            kind = (args["k"] as? Int) ?: 1,
            metadataVersion = (args["mv"] as? List<Int>)?.toIntArray() ?: intArrayOf(),
            data1 = (args["d1"] as? List<String>)?.toTypedArray() ?: emptyArray(),
            data2 = (args["d2"] as? List<String>)?.toTypedArray() ?: emptyArray(),
            extraString = args["xs"] as? String ?: "",
            packageName = args["pn"] as? String ?: "",
            extraInt = (args["xi"] as? Int) ?: 0
        )

        val km = KotlinClassMetadata.readLenient(header) as? KotlinClassMetadata.Class ?: return emptyMap()
        val kmClass = km.kmClass

        val out = mutableMapOf<String, String>()
        for (p in kmClass.properties) {
            // Check if property is delegated by looking at visibility
            val isDelegated = p.visibility == kotlinx.metadata.Visibility.LOCAL
            if (!isDelegated) continue
            val propName = p.name
            val returnTypeFqn = p.returnType.toString() // crude; good enough for Kotlin types
                .removePrefix("kotlin.") // optional normalization
            // Better: map KmType to FQN properly; for demo we keep .toString()
            out[propName] = returnTypeFqn
        }
        return out
    }
}
