// The Android Gradle Plugin is loaded here, on the root buildscript classpath,
// and only when there is an SDK to use it with.
//
// Both halves of that matter, and getting either wrong breaks a build in a way
// that is invisible from the other environment:
//
//   * AGP must be visible to the Kotlin plugin. `plugins {}` scopes are children
//     of this buildscript scope, so a class loaded here is visible to them —
//     whereas AGP declared only in :app lands in a *sibling* scope that the
//     root-declared Kotlin plugin cannot see, and applying
//     org.jetbrains.kotlin.android then dies with
//     `NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`.
//
//   * AGP must not be resolved when it cannot be. It exists only on
//     dl.google.com, so an unconditional declaration fails every build in an
//     environment without access to that host — including
//     `./gradlew :core:domain:test`, which has nothing to do with Android. That
//     is the case the conditional `include(":app")` in settings.gradle.kts
//     exists to support, and a root declaration silently defeats it.
//
// The same SDK probe as settings.gradle.kts, so the two decisions cannot drift
// apart: either both include :app and load AGP, or neither does.
buildscript {
    val sdkDir: String? = run {
        val props = java.util.Properties()
        val f = file("local.properties")
        if (f.exists()) f.inputStream().use { props.load(it) }
        props.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
    }

    // Read from the catalog rather than repeated here: a root classpath pinned
    // to one AGP version while :app's alias resolves another is exactly the kind
    // of mismatch that produces an unreadable plugin error.
    val agpVersion = file("gradle/libs.versions.toml").readLines()
        .first { it.substringBefore('=').trim() == "agp" }
        .substringAfter('"').substringBefore('"')

    repositories {
        mavenCentral()
        google()
    }

    dependencies {
        if (sdkDir != null) {
            classpath("com.android.tools.build:gradle:$agpVersion")
        }
    }
}

// Plugins that more than one module uses are declared here with `apply false`.
// Gradle resolves each one exactly once, at the version the catalog names, and
// the modules then apply them by alias without re-requesting a version — which
// is what stops "already on the classpath with an unknown version" the moment
// two modules want two faces of the same Kotlin plugin (jvm here, android in
// :app).
//
// AGP is absent from this list on purpose — it comes from the conditional
// buildscript block above, which is the only way to make it optional.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
