// Standalone Gradle build whose only job is to compile and unit test the protocol
// package of the app on a plain JVM, without the Android SDK. It reaches straight into
// the app's sources, so there is no second copy of the code to keep in sync.
rootProject.name = "protocol-verify"

pluginManagement {
    repositories {
        // repo1 and repo.maven.apache.org are the same artifacts; naming repo1 first
        // avoids the host that rate limits CI traffic hardest.
        maven { url = uri("https://repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}
