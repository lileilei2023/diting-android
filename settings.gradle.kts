pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle Plugin lives on google(); it is only needed when the
        // :app module is part of the build (see the conditional include below).
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "diting"

include(":core:protocol")
include(":core:domain")
include(":core:ai")

// ---------------------------------------------------------------------------
// :app needs the Android SDK. Sandboxes and CI images without it can still run
// `./gradlew :core:protocol:test` etc., because Gradle never has to configure an
// Android project it cannot resolve a plugin for.
//
// Point at the SDK with any one of:
//   * local.properties  ->  sdk.dir=/path/to/Android/Sdk
//   * ANDROID_HOME / ANDROID_SDK_ROOT environment variable
//   * -PforceAndroid=true  (fail loudly instead of skipping)
// ---------------------------------------------------------------------------
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sdkDir: String? = localProps.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
val forceAndroid = (startParameter.projectProperties["forceAndroid"] ?: "false").toBoolean()

if (sdkDir != null || forceAndroid) {
    include(":app")
} else {
    logger.lifecycle(
        """
        |
        |  [diting] Android SDK not found - skipping the :app module.
        |           The pure-Kotlin core modules still build and test:
        |               ./gradlew :core:protocol:test :core:domain:test :core:ai:test
        |           To build the APK, set sdk.dir in local.properties or export ANDROID_HOME.
        |
        """.trimMargin()
    )
}
