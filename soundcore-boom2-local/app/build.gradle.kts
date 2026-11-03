import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "de.privat.boom2local"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.privat.boom2local"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-unverified"
        // No test runner: there is nothing in this app that talks to a server, so there
        // is no instrumentation harness to configure either.
    }

    signingConfigs {
        getByName("debug") {
            // CI passes a key that persists across runs, so a rebuilt APK installs over
            // the previous one instead of forcing an uninstall first.
            System.getenv("BOOM2_DEBUG_KEYSTORE")?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Same key as the debug build. R8 shrinks this variant to a fraction of the
            // size, which matters on a phone with a flaky connection, and sharing the
            // key means it installs over the larger build instead of clashing with it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    // The Kotlin Android plugin registers src/main/kotlin for each Android source set on
    // its own, so no sourceSets block is needed here.
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------------------
// Build-time guarantees
//
// The promise of this app is that it cannot phone home and cannot reach any service but
// the Bluetooth control channel. A promise in a README is worth little, so the build
// enforces it and fails if it is ever broken — including by a dependency that quietly
// pulls a network or analytics library in behind your back.
// ---------------------------------------------------------------------------------------

/** Permissions that must never appear in this app, in any manifest, ever. */
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_PHONE_STATE",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_CONTACTS",
)

/** Only these dependency groups may end up on the runtime classpath. */
val allowedDependencyPrefixes = listOf(
    "androidx.",
    "org.jetbrains.kotlin:",
    "org.jetbrains.kotlinx:",
    "org.jetbrains:annotations",
    "com.google.guava:listenablefuture", // empty stub, pulled in transitively by androidx
    // Annotation-only artifacts: no runtime behaviour, no I/O. They show up as
    // transitive dependencies of androidx and are harmless; if the build fails on a
    // module not listed here, look it up before adding it rather than after.
    "com.google.errorprone:error_prone_annotations",
    "com.google.code.findbugs:jsr305",
    "org.checkerframework:checker-qual",
    "com.google.j2objc:j2objc-annotations",
)

/** Packages the app's own code may not import. */
val forbiddenImportPrefixes = listOf(
    "java.net.",
    "javax.net.",
    "java.rmi.",
    "android.net.",
    "android.webkit.",
    "okhttp3.",
    "retrofit2.",
    "io.ktor.",
    "com.google.firebase.",
    "com.google.android.gms.",
)

val sourceManifests = fileTree("src") { include("**/AndroidManifest.xml") }
val appSources = fileTree("src/main/kotlin") { include("**/*.kt") }
val protocolSources = fileTree("src/main/kotlin/de/privat/boom2local/protocol") {
    include("**/*.kt")
}

val verifySourceManifests by tasks.registering {
    group = "verification"
    description = "Fails when a source manifest declares a network or tracking permission."
    inputs.files(sourceManifests)
    doLast {
        // Match real <uses-permission> declarations, not every mention of the string:
        // the manifest explains in a comment which permissions are deliberately absent,
        // and a plain substring search would trip over its own documentation.
        val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val declaration = Regex("<uses-permission[\\w-]*[^>]*android:name\\s*=\\s*\"([^\"]+)\"")
        val offences = mutableListOf<String>()
        sourceManifests.forEach { file ->
            val xml = file.readText().replace(comment, "")
            declaration.findAll(xml)
                .map { it.groupValues[1] }
                .filter { it in forbiddenPermissions }
                .forEach { offences += "${file.path} declares $it" }
        }
        if (offences.isNotEmpty()) {
            throw GradleException(
                "This app must not request network or tracking permissions:\n" +
                    offences.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle(
            "source manifests are clean (${sourceManifests.files.size} checked, " +
                "${forbiddenPermissions.size} permissions forbidden)",
        )
    }
}

val verifyNoNetworkImports by tasks.registering {
    group = "verification"
    description = "Fails when the app's own code imports a networking or tracking API."
    inputs.files(appSources)
    doLast {
        val offences = mutableListOf<String>()
        appSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val imported = trimmed.removePrefix("import ").trim()
                    forbiddenImportPrefixes.firstOrNull { imported.startsWith(it) }?.let {
                        offences += "${file.name}:${index + 1} imports $imported"
                    }
                }
            }
        }
        if (offences.isNotEmpty()) {
            throw GradleException(
                "The app must not import networking or tracking APIs:\n" +
                    offences.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("no networking imports (${appSources.files.size} source files checked)")
    }
}

val verifyProtocolCoreIsPure by tasks.registering {
    group = "verification"
    description = "Fails when the protocol core depends on Android, so it stays testable."
    inputs.files(protocolSources)
    doLast {
        val forbidden = listOf("android.", "androidx.", "java.net.", "java.io.File")
        val offences = mutableListOf<String>()
        protocolSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val imported = trimmed.removePrefix("import ").trim()
                    forbidden.firstOrNull { imported.startsWith(it) }?.let {
                        offences += "${file.name}:${index + 1} imports $imported"
                    }
                }
            }
        }
        if (offences.isNotEmpty()) {
            throw GradleException(
                "The protocol core must stay platform free so protocol-verify can test it:\n" +
                    offences.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("protocol core is platform free (${protocolSources.files.size} files)")
    }
}

val verifyDependencyAllowlist by tasks.registering {
    group = "verification"
    description = "Fails when anything outside the allowlist reaches the runtime classpath."
    doLast {
        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val modules = configuration.incoming.resolutionResult.allDependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}" }
            .distinct()
            .sorted()
        val offences = modules.filterNot { module ->
            allowedDependencyPrefixes.any { module.startsWith(it) }
        }
        if (offences.isNotEmpty()) {
            throw GradleException(
                "Unexpected dependencies on the runtime classpath. Every library here can " +
                    "see everything the app does, so the list is deliberately short:\n" +
                    offences.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("dependency allowlist holds (${modules.size} modules resolved)")
    }
}

/**
 * The strongest of the checks: it reads the manifest AGP actually packages, after every
 * library manifest has been merged in. A dependency that declares INTERNET for you is
 * caught here even though your own manifest is clean.
 */
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val verifyMerged = tasks.register("verify${name}MergedManifest") {
            group = "verification"
            description = "Fails when the merged manifest of $name requests network access."
            inputs.file(manifest)
            doLast {
                val file = manifest.get().asFile
                val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
                val declaration =
                    Regex("<uses-permission[\\w-]*[^>]*android:name\\s*=\\s*\"([^\"]+)\"")
                val xml = file.readText().replace(comment, "")
                val found = declaration.findAll(xml)
                    .map { it.groupValues[1] }
                    .filter { it in forbiddenPermissions }
                    .distinct()
                    .toList()
                if (found.isNotEmpty()) {
                    throw GradleException(
                        "The merged manifest of variant ${variant.name} requests:\n" +
                            found.joinToString("\n") { "  - $it" } +
                            "\nThis is probably a dependency adding it. Find it with:\n" +
                            "  ./gradlew :app:verifyDependencyAllowlist\n" +
                            "Merged manifest: ${file.absolutePath}",
                    )
                }
                logger.lifecycle("merged manifest of ${variant.name} requests no network access")
            }
        }
        tasks.matching { it.name == "assemble$name" }.configureEach {
            dependsOn(verifyMerged)
        }
        tasks.named("check") { dependsOn(verifyMerged) }
    }
}

tasks.named("check") {
    dependsOn(
        verifySourceManifests,
        verifyNoNetworkImports,
        verifyProtocolCoreIsPure,
        verifyDependencyAllowlist,
    )
}

// Also gate the actual packaging, so you cannot ship a build that skipped the checks.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifySourceManifests, verifyNoNetworkImports, verifyProtocolCoreIsPure)
}
