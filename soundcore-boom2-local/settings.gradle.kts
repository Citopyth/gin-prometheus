pluginManagement {
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "Boom2Local"
include(":app")

// protocol-verify/ is deliberately NOT included. It is a separate, Android-free Gradle
// build so the protocol core can be compiled and unit tested without the Android SDK:
//   cd protocol-verify && ./gradlew test
