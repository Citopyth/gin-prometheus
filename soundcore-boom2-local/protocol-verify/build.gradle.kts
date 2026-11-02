plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    sourceSets {
        named("main") {
            // The app's protocol package, compiled as plain Kotlin. If anything in here
            // ever imports an Android class, this build stops compiling — which is the
            // point of having it.
            kotlin.srcDir("../app/src/main/kotlin/de/privat/boom2local/protocol")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

/**
 * Guards the "plain Kotlin" promise by inspecting the sources directly, so the rule holds
 * even for an import the compiler would happily resolve from the classpath.
 */
val verifyProtocolCoreIsPure by tasks.registering {
    group = "verification"
    description = "Fails when the protocol package references Android or java.net APIs."
    val sources = fileTree("../app/src/main/kotlin/de/privat/boom2local/protocol") {
        include("**/*.kt")
    }
    inputs.files(sources)
    doLast {
        val forbidden = listOf("android.", "androidx.", "java.net.", "java.io.File")
        val offences = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val imported = trimmed.removePrefix("import ").trim()
                    forbidden.firstOrNull { imported.startsWith(it) }?.let { bad ->
                        offences += "${file.name}:${index + 1} imports $imported (forbidden: $bad)"
                    }
                }
            }
        }
        if (offences.isNotEmpty()) {
            throw GradleException(
                "The protocol core must stay free of platform and network APIs:\n" +
                    offences.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("protocol core is platform free (${sources.files.size} files checked)")
    }
}

tasks.named("check") {
    dependsOn(verifyProtocolCoreIsPure)
}
