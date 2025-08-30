plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    id("application")
    id("org.graalvm.buildtools.native") version "0.11.0"
}

repositories {
    mavenCentral() // <-- keep plugin portal out of here
}

dependencies {
    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.0.1")

    // Bytecode + Kotlin metadata
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.9.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // HTTP (upload)
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
}

application {
    mainClass.set("app.MainKt")
}

// Ensure Gradle builds with JDK 21 (matches your GraalVM)
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// GraalVM Native Image
graalvmNative {
    toolchainDetection.set(false)
    binaries {
        named("main") {
            imageName.set("knitscope")
            mainClass.set("app.MainKt")
            quickBuild.set(true)
            useFatJar.set(true)
            resources.autodetect()
            // 👇 Add this line:
            buildArgs.add("--initialize-at-build-time=kotlin")
        }
    }
}

// Keep your uber-jar task (handy for JVM distribution)
tasks.withType<Jar> {
    manifest { attributes["Main-Class"] = "app.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // include compiled classes + resources
    from(sourceSets.main.get().output)

    // include all runtime deps
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
