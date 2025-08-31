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

private fun isMultiReturn(jvmType: String): Boolean =
    jvmType == "java.util.List" || jvmType == "java.util.Set" || jvmType == "java.util.Map"

private fun isProjectType(fqn: String): Boolean =
    !(fqn.startsWith("java.") || fqn.startsWith("kotlin.") || fqn.startsWith("javax.") || fqn.startsWith("org.jetbrains."))

private val PRIMITIVES = setOf("void","boolean","byte","short","char","int","long","float","double")

private fun isPrimitiveType(fqn: String): Boolean =
    fqn in PRIMITIVES || (fqn.endsWith("[]") && isPrimitiveType(fqn.removeSuffix("[]")))

// Keep helper (true = include in graph)
private fun keepType(fqn: String): Boolean = !isPrimitiveType(fqn)

// Very small, conservative generic return parser using the Signature attribute
// Example input: "(...)Ljava/util/List<Lknit/demo/GitCommand;>;" -> "java.util.List<knit.demo.GitCommand>"
private fun decodeGenericReturn(signature: String?): String? {
    if (signature == null) return null
    val retStart = signature.lastIndexOf(')') + 1
    if (retStart <= 0 || retStart >= signature.length) return null
    val retSig = signature.substring(retStart)

    fun decType(sig: String, i0: Int = 0): Pair<String, Int>? {
        var i = i0
        if (i >= sig.length) return null
        return when (sig[i]) {
            'L' -> { // object type
                val semi = sig.indexOf(';', i)
                if (semi < 0) return null
                val raw = sig.substring(i + 1, semi).replace('/', '.')
                raw to (semi + 1)
            }
            'T' -> { // type var
                val semi = sig.indexOf(';', i)
                if (semi < 0) return null
                val tv = sig.substring(i + 1, semi)
                tv to (semi + 1)
            }
            '[' -> decType(sig, i + 1)?.let { ("${it.first}[]") to it.second }
            else -> null // primitives / wildcards ignored here
        }
    }

    // Parse something like Ljava/util/List<Lfoo/Bar;>;
    var i = 0
    if (retSig.startsWith("L")) {
        val (raw, j) = decType(retSig) ?: return null
        if (j < retSig.length && retSig[j] == '<') {
            // generics
            val close = retSig.indexOf('>', j)
            if (close > j + 1) {
                val genBody = retSig.substring(j + 1, close)
                // Only render the first parameter for brevity (works for List<T> and Set<T>)
                val (param, _) = decType(genBody) ?: return "$raw<>"
                return "$raw<$param>"
            }
        }
        return raw
    }
    return null
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

        return compactToProvidedProjectTypes(res, moduleName)
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
            private val observedGetterCalls = mutableSetOf<String>() // entries like "knit.demo.MemoryStoreComponent#getFileSystem"

            private val classFields = mutableMapOf<String, String>()

            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                className = name.replace('/', '.')
            }

            override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                if (isProvides(desc)) classProvides = true
                return super.visitAnnotation(desc, visible)
            }

            override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor {
                val fieldType = Type.getType(desc).className
                classFields[name] = fieldType
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
                val genericReturn = decodeGenericReturn(signature)   // e.g., "java.util.List<knit.demo.GitCommand>"
                val argTypes = Type.getArgumentTypes(desc).map { it.className }

                // If class-level @Provides, ctor "provides" the class; "requires" = ctor params
                if (isCtor && classProvides) {
                    val provId = "${simple(className)}.<init>"
                    res.providers += ProviderNode(
                        id = provId, type = className, owner = className, module = moduleName, requires = argTypes
                    )
                    res.types.putIfAbsent(className, TypeNode(id = className, module = moduleName, `package` = pkg(className)))
                    argTypes.filter(::keepType).forEach { t -> res.types.putIfAbsent(t, TypeNode(id = t)) }
                    res.edges += Edge(from = provId, to = className, kind = EdgeKind.provides)
                    argTypes.filter(::keepType).forEach { t -> res.edges += Edge(from = provId, to = t, kind = EdgeKind.requires) }
                }

                // tracking
                var constructsReturnType = false
                var methodHasProvides = false
                var elementBuildCount = 0                   // crude signal for MULTI
                val callPath = StringBuilder()              // provenance (very light)

                return object : MethodVisitor(api) {
                    override fun visitAnnotation(annDesc: String, visible: Boolean): AnnotationVisitor? {
                        if (isProvides(annDesc)) methodHasProvides = true
                        return super.visitAnnotation(annDesc, visible)
                    }

                    override fun visitTypeInsn(opcode: Int, typeInternalName: String) {
                        if (opcode == Opcodes.NEW) {
                            val newType = Type.getObjectType(typeInternalName).className
                            // If we are NEW'ing a type that matches the return type => likely assembling return
                            if (newType == returnType) constructsReturnType = true

                            // Heuristic: in MULTI builders, we often NEW individual element types (e.g., GitCommand impls)
                            if (isMultiReturn(returnType) && newType != returnType) {
                                elementBuildCount++
                                if (callPath.length < 256) callPath.append("new ").append(newType.substringAfterLast('.')).append("; ")
                            }
                        }
                        super.visitTypeInsn(opcode, typeInternalName)
                    }

                    override fun visitMethodInsn(opcode: Int, owner: String, mName: String, mDesc: String, isInterface: Boolean) {
                        val ownerFqn = owner.replace('/', '.')
                        val calleeRet = Type.getReturnType(mDesc).className

                        val calleeArgs = Type.getArgumentTypes(mDesc)
                        if (calleeArgs.isEmpty() && mName.startsWith("get") && mName.length > 3) {
                            observedGetterCalls += ownerFqn + "#" + mName
                        }

                        // 1) Direct constructor of returnType
                        if (opcode == Opcodes.INVOKESPECIAL && mName == "<init>" && ownerFqn == returnType) {
                            constructsReturnType = true
                        }
                        // 2) Any call returning our returnType (factory)
                        if (calleeRet == returnType) {
                            constructsReturnType = true
                        }

                        // MULTI heuristic: calls that look like list/set/map 'add'/'put' or Knit multi builders
                        if (isMultiReturn(returnType)) {
                            val simpleOwner = ownerFqn.substringAfterLast('.')
                            val m = mName.lowercase()
                            if (m == "add" || m == "put" || m.contains("append") || m.contains("builder")) {
                                elementBuildCount++
                            }
                        }

                        // provenance (trim to keep json small)
                        if (callPath.length < 256) {
                            val frag = "${simple(ownerFqn)}.$mName"
                            if (!frag.startsWith("${simple(className)}.$name")) {
                                callPath.append(frag).append(" -> ")
                            }
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface)
                    }

                    override fun visitParameterAnnotation(parameter: Int, annDesc: String, visible: Boolean): AnnotationVisitor? {
                        if (isProvides(annDesc)) {
                            val t = argTypes[parameter]
                            if (keepType(t)) {
                                val provId = "${simple(className)}.param$parameter"
                                res.providers += ProviderNode(
                                    id = provId, type = t, owner = className, module = moduleName, requires = emptyList()
                                )
                                res.types.putIfAbsent(t, TypeNode(id = t))
                                res.edges += Edge(from = provId, to = t, kind = EdgeKind.provides)
                            }
                        }
                        return super.visitParameterAnnotation(parameter, annDesc, visible)
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, fName: String, fDesc: String) {
                        // If we are in the constructor and we assign to a field of THIS class…
                        if (isCtor && opcode == Opcodes.PUTFIELD) {
                            val ownerFqn = owner.replace('/', '.')
                            if (ownerFqn == className) {
                                val fqnType = Type.getType(fDesc).className
                                // Only record meaningful DI types (non-primitive etc.).
                                // If you kept the compact-to-providers pass, we can emit freely here;
                                // compaction will prune anything not actually provided later.
                                if (fqnType != "void") {
                                    val consumerId = "${simple(className)}.$fName"
                                    // Add consumer node once
                                    if (res.consumers.none { it.id == consumerId }) {
                                        res.consumers += ConsumerNode(
                                            id = consumerId,
                                            needs = fqnType,
                                            owner = className,
                                            module = moduleName
                                        )
                                    }
                                    // Ensure the type node + needs edge exist
                                    res.types.putIfAbsent(fqnType, TypeNode(id = fqnType))
                                    if (res.edges.none { it.from == consumerId && it.to == fqnType && it.kind == EdgeKind.needs }) {
                                        res.edges += Edge(from = consumerId, to = fqnType, kind = EdgeKind.needs)
                                    }
                                }
                            }
                        }
                        super.visitFieldInsn(opcode, owner, fName, fDesc)
                    }

                    override fun visitEnd() {
                        val isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0
                        val isBridge = (access and Opcodes.ACC_BRIDGE) != 0
                        val returnsReal = returnType != "void"

                        // ====== (1) Composite accessor provider (getX(): T with zero args) ======
                        // Heuristic: public/getter-ish, zero params, returns a type, not @Provides.
                        val looksLikeGetter = name.startsWith("get") && argTypes.isEmpty()
                        val isPublic = (access and Opcodes.ACC_PUBLIC) != 0
                        val ownerLooksLikeComponent = className.endsWith("Component") || className.endsWith("Application") || className.endsWith("Module")
                        val returnsProjectType = isProjectType(returnType)
                        val notACollection = !isMultiReturn(returnType) // don’t make List/Set/Map “providers”
                        val isObservedCall = observedGetterCalls.contains(className + "#" + name)

                        // Emit COMPOSITE only for real component accessors:
                        //  - public, zero-arg getter
                        //  - returns a project type (not JDK/collections)
                        //  - and either owner looks like a component OR we actually saw this getter used
                        val shouldEmitCompositeProvider =
                            !isCtor && looksLikeGetter && isPublic && returnsProjectType && notACollection &&
                                    !methodHasProvides && (ownerLooksLikeComponent || isObservedCall)

                        if (shouldEmitCompositeProvider) {
                            val provId = "${simple(className)}.$name"
                            res.providers += ProviderNode(
                                id = provId,
                                type = returnType,
                                owner = className,
                                module = moduleName,
                                requires = emptyList()
                            )
                            res.types.putIfAbsent(returnType, TypeNode(id = returnType))
                            res.edges += Edge(
                                from = provId,
                                to = returnType,
                                kind = EdgeKind.provides,
                                attrs = mapOf("tag" to "COMPOSITE")
                            )
                        }

                        // ====== (2) Consumer detection (including MULTI tagging) ======
                        if (!isCtor && !isSynthetic && !isBridge && returnType != "void" &&
                            constructsReturnType && !methodHasProvides && keepType(returnType)) {
                            val consumerId = "${simple(className)}.$name"
                            val tags = mutableListOf<String>()
                            val attrs = mutableMapOf<String, String>()

                            // MULTI if: return is List/Set/Map AND we saw 2+ element constructions/collects
                            if (isMultiReturn(returnType) && elementBuildCount >= 2) {
                                tags += "MULTI"
                                attrs["multi.count"] = elementBuildCount.toString()
                                genericReturn?.let { attrs["genericReturn"] = it } // e.g., "java.util.List<knit.demo.GitCommand>"
                            }

                            // provenance (trimmed)
                            val prov = callPath.toString().trim().trimEnd('-', '>', ' ')

                            res.consumers += ConsumerNode(
                                id = consumerId,
                                needs = returnType,
                                owner = className,
                                module = moduleName,
                                tags = tags,
                                provenance = prov.ifEmpty { null }
                            )
                            res.types.putIfAbsent(returnType, TypeNode(id = returnType))
                            if (res.edges.none { it.from == consumerId && it.to == returnType && it.kind == EdgeKind.needs }) {
                                res.edges += Edge(from = consumerId, to = returnType, kind = EdgeKind.needs, attrs = if (attrs.isEmpty()) null else attrs.toMap())
                            }
                        }

                        super.visitEnd()
                    }
                }
            }

            override fun visitEnd() {
                // Use Kotlin metadata to find delegated properties + their types, and filter out for primitives
                MetadataUtil.extractDelegatedProperties(bytes)?.forEach { (propName, returnFqn) ->
                    if (!keepType(returnFqn)) return@forEach
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
                    if (keepType(fieldType)) {
                        val provId = "${simple(className)}.$fieldName"
                        res.providers += ProviderNode(id = provId, type = fieldType, owner = className, module = moduleName)
                        res.types.putIfAbsent(fieldType, TypeNode(id = fieldType))
                        res.edges += Edge(from = provId, to = fieldType, kind = EdgeKind.provides)
                    }
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

        return compactToProvidedProjectTypes(res, moduleName)
    }

    /**
     * Keep ONLY “knit provider types”:
     *  - types that are the target of a ProviderNode (i.e., actually provided)
     *  - and also NOT in JDK/Kotlin namespaces (filters out String, List/Map, etc.)
     *
     * Prunes:
     *  - types[] to the allowed set
     *  - providers[] to those whose type is allowed
     *  - consumers[] to those whose 'needs' is allowed
     *  - edges[] to those whose 'to' is an allowed type
     * Also trims ProviderNode.requires to only allowed types.
     */
    private fun compactToProvidedProjectTypes(res: ScanResult, moduleName: String?): GraphJson {
        // 1) Allowed = provided types that look like project types
        val allowedTypes: Set<String> = res.providers
            .map { it.type }
            .filter { isProjectType(it) }
            .toSet()

        // 2) Filter providers to those that provide an allowed type
        val keptProviders = res.providers.filter { it.type in allowedTypes }.map { p ->
            // Trim requires list to allowed types
            if (p.requires.isNullOrEmpty()) p
            else p.copy(requires = p.requires.filter { it in allowedTypes })
        }

        // 3) Consumers that need an allowed type
        val keptConsumers = res.consumers.filter { it.needs in allowedTypes }

        // 4) Only keep edges whose *target* type is allowed
        val keptEdges = res.edges.filter { it.to in allowedTypes }

        // 5) Build types[] from allowed set (preserve any known module/pkg if present)
        val keptTypes = allowedTypes.map { t ->
            res.types[t] ?: TypeNode(id = t, module = moduleName, `package` = pkg(t))
        }

        return GraphJson(
            types = keptTypes,
            providers = keptProviders,
            consumers = keptConsumers,
            edges = keptEdges,
            metrics = GraphJson.Metrics(
                generatedAt = java.time.Instant.now().toString(),
                nodeCount = keptTypes.size + keptProviders.size + keptConsumers.size,
                edgeCount = keptEdges.size
            )
        )
    }


    private fun simple(fqn: String) = fqn.substringAfterLast('.')
    private fun pkg(fqn: String) = fqn.substringBeforeLast('.', missingDelimiterValue = "")
}
