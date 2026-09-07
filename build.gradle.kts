// Plugins that more than one module uses are declared here with `apply false`.
// Gradle resolves each one exactly once, at the version the catalog names, and
// the modules then apply them by alias without re-requesting a version — which
// is what stops "already on the classpath with an unknown version" the moment
// two modules want two faces of the same Kotlin plugin (jvm here, android in
// :app).
//
// The Android Gradle Plugin is deliberately NOT in this list even though :app
// uses it. Declaring it here makes Gradle resolve it during *every* build, and
// AGP only exists on dl.google.com — so a root declaration breaks the core
// modules in any environment without access to that host, which is exactly the
// case the conditional `include(":app")` in settings.gradle.kts exists to
// support. :app declares it alone, and being the only module that applies it,
// there is no version conflict to prevent.
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
