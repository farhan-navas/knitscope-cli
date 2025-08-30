package scan

import core.*
import org.objectweb.asm.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

private fun isProvides(desc: String): Boolean {
    val slash = desc.lastIndexOf('/')
    val dot = desc.lastIndexOf('.')
    val semi = desc.lastIndexOf(';')
    val start = maxOf(slash, dot) + 1
    val end = if (semi >= 0) semi else desc.length
    val simple = if (start in 0..<end) desc.substring(start, end) else desc
    return simple == "Provides"
}

data class ScanResult(
    val types: MutableMap<String, TypeNode> = mutableMapOf(),
    val providers: MutableList<ProviderNode> = mutableListOf(),
    val consumers: MutableList<ConsumerNode> = mutableListOf(),
    val edges: MutableList<Edge> = mutableListOf()
)

class AsmScanner {

    fun scanClassDir(root: Path, moduleName: String? = null): GraphJson {
        // for debug only!!
        val classCount = Files.walk(root).use { paths ->
            // Convert to Kotlin sequence so we can use count { predicate }
            paths.asSequence().count { it.isRegularFile() && it.extension == "class" }
        }
        println("Scanning $root ... found $classCount .class files")

        val res = ScanResult()
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "class" }.forEach { parseClass(it, moduleName, res) }
        }
        val graph = GraphJson(
            types = res.types.values.toList(),
            providers = res.providers.toList(),
            consumers = res.consumers.toList(),
            edges = res.edges.toList(),
            metrics = GraphJson.Metrics(
                generatedAt = java.time.Instant.now().toString(),
                nodeCount = res.types.size + res.providers.size + res.consumers.size,
                edgeCount = res.edges.size
            )
        )
        return graph
    }

    private fun parseClass(file: Path, moduleName: String?, res: ScanResult) {
        val bytes = Files.readAllBytes(file)
        parseClassBytes(bytes, moduleName, res)
    }

    private fun parseClassBytes(bytes: ByteArray, moduleName: String?, res: ScanResult) {
        val cr = ClassReader(bytes)
        val cv = object : ClassVisitor(Opcodes.ASM9) {
            lateinit var className: String
            private var classProvides = false
            private val delegateFields = mutableSetOf<String>() // propertyName for $delegate
            private val fieldProviders = mutableListOf<Pair<String, String>>() // (fieldName, fieldType)

            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                className = name.replace('/', '.')
            }

            override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                if (isProvides(desc)) classProvides = true
                return super.visitAnnotation(desc, visible)
            }

            override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor {
                val fieldType = Type.getType(desc).className
                val isDelegate = name.endsWith("\$delegate")
                return object : FieldVisitor(api) {
                    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                        if (isProvides(desc)) {
                            // @Provides on a field -> provides fieldType
                            fieldProviders += (name to fieldType)
                        }
                        return super.visitAnnotation(desc, visible)
                    }
                    override fun visitEnd() {
                        if (isDelegate) {
                            val propName = name.removeSuffix("\$delegate")
                            delegateFields += propName
                        }
                        super.visitEnd()
                    }
                }
            }

            override fun visitMethod(
                access: Int,
                name: String,
                desc: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val isCtor = name == "<init>"
                val returnType = Type.getReturnType(desc).className
                val argTypes = Type.getArgumentTypes(desc).map { it.className }

                // If class-level @Provides, ctor "provides" the class; "requires" = ctor params
                if (isCtor && classProvides) {
                    val provId = "${simple(className)}.<init>"
                    res.providers += ProviderNode(
                        id = provId, type = className, owner = className, module = moduleName, requires = argTypes
                    )
                    // graph edges
                    res.types.putIfAbsent(className, TypeNode(id = className, module = moduleName, `package` = pkg(className)))
                    argTypes.forEach { t -> res.types.putIfAbsent(t, TypeNode(id = t)) }
                    res.edges += Edge(from = provId, to = className, kind = EdgeKind.provides)
                    argTypes.forEach { t -> res.edges += Edge(from = provId, to = t, kind = EdgeKind.requires) }
                }

                // Track whether THIS method appears to construct/return its own return type.
                var constructsReturnType = false
                var methodHasProvides = false

                return object : MethodVisitor(api) {
                    override fun visitAnnotation(annDesc: String, visible: Boolean): AnnotationVisitor? {
                        if (isProvides(annDesc)) {
                            methodHasProvides = true
                        }
                        return super.visitAnnotation(annDesc, visible)
                    }

                    // Detect `new <ReturnType>` bytecode
                    override fun visitTypeInsn(opcode: Int, typeInternalName: String) {
                        if (opcode == Opcodes.NEW) {
                            val newType = Type.getObjectType(typeInternalName).className
                            if (newType == returnType) {
                                constructsReturnType = true
                            }
                        }
                        super.visitTypeInsn(opcode, typeInternalName)
                    }

                    // Detect calls that *return* the method's return type (factory methods)
                    override fun visitMethodInsn(opcode: Int, owner: String, mName: String, mDesc: String, isInterface: Boolean) {
                        // 1) Direct constructor call of the returnType
                        if (opcode == Opcodes.INVOKESPECIAL && mName == "<init>") {
                            val ownerFqn = owner.replace('/', '.')
                            if (ownerFqn == returnType) constructsReturnType = true
                        }
                        // 2) Any method whose return type matches our method's returnType
                        val calleeRet = Type.getReturnType(mDesc).className
                        if (calleeRet == returnType) {
                            constructsReturnType = true
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface)
                    }

                    // Capture @Provides on constructor parameters → they provide their type within class scope
                    override fun visitParameterAnnotation(parameter: Int, annDesc: String, visible: Boolean): AnnotationVisitor? {
                        if (isProvides(annDesc)) {
                            val t = argTypes[parameter]
                            val provId = "${simple(className)}.param$parameter"
                            res.providers += ProviderNode(
                                id = provId, type = t, owner = className, module = moduleName, requires = emptyList()
                            )
                            res.types.putIfAbsent(t, TypeNode(id = t))
                            res.edges += Edge(from = provId, to = t, kind = EdgeKind.provides)
                        }
                        return super.visitParameterAnnotation(parameter, annDesc, visible)
                    }

                    override fun visitEnd() {
                        // === Consumer detection for rewritten getters/assemblers ===
                        // Emit a Consumer if:
                        // - not a constructor
                        // - method returns a real type (not void/Unit)
                        // - we saw bytecode that constructs/returns that return type (post-Knit assembly)
                        // - the method itself is NOT a provider (@Provides) to avoid double-classifying
                        val isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0
                        val isBridge = (access and Opcodes.ACC_BRIDGE) != 0
                        if (!isCtor && !isSynthetic && !isBridge && returnType != "void" && constructsReturnType && !methodHasProvides) {
                            val consumerId = "${simple(className)}.$name"
                            // node + edge
                            res.consumers += ConsumerNode(
                                id = consumerId,
                                needs = returnType,
                                owner = className,
                                module = moduleName
                            )
                            res.types.putIfAbsent(returnType, TypeNode(id = returnType))
                            if (res.edges.none { it.from == consumerId && it.to == returnType && it.kind == EdgeKind.needs }) {
                                res.edges += Edge(from = consumerId, to = returnType, kind = EdgeKind.needs)
                            }
                        }

                        super.visitEnd()
                    }
                }
            }

            override fun visitEnd() {
                // Use Kotlin metadata to find delegated properties + their types
                MetadataUtil.extractDelegatedProperties(bytes)?.forEach { (propName, returnFqn) ->
                    val consumerId = "${simple(className)}.$propName"
                    res.consumers += ConsumerNode(
                        id = consumerId,
                        needs = returnFqn,
                        owner = className,
                        module = moduleName
                    )
                    res.types.putIfAbsent(returnFqn, TypeNode(id = returnFqn))
                    res.edges += Edge(from = consumerId, to = returnFqn, kind = EdgeKind.needs)
                }

                // Field-level providers recorded earlier
                fieldProviders.forEach { (fieldName, fieldType) ->
                    val provId = "${simple(className)}.$fieldName"
                    res.providers += ProviderNode(id = provId, type = fieldType, owner = className, module = moduleName)
                    res.types.putIfAbsent(fieldType, TypeNode(id = fieldType))
                    res.edges += Edge(from = provId, to = fieldType, kind = EdgeKind.provides)
                }
                super.visitEnd()
            }
        }
        cr.accept(cv, ClassReader.SKIP_DEBUG)
    }

    fun scanJar(jarPath: Path, moduleName: String? = null): GraphJson {
        val res = ScanResult()
        val zf = java.util.zip.ZipFile(jarPath.toFile())
        val entries = zf.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.toList()
        println("Scanning JAR $jarPath ... found ${entries.size} .class files")
        for (e in entries) {
            val bytes = zf.getInputStream(e).use { it.readAllBytes() }
            parseClassBytes(bytes, moduleName, res)
        }
        zf.close()
        return GraphJson(
            types = res.types.values.toList(),
            providers = res.providers.toList(),
            consumers = res.consumers.toList(),
            edges = res.edges.toList(),
            metrics = GraphJson.Metrics(
                generatedAt = java.time.Instant.now().toString(),
                nodeCount = res.types.size + res.providers.size + res.consumers.size,
                edgeCount = res.edges.size
            )
        )
    }

    private fun simple(fqn: String) = fqn.substringAfterLast('.')
    private fun pkg(fqn: String) = fqn.substringBeforeLast('.', missingDelimiterValue = "")
}
