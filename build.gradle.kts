// Every plugin any module uses is declared here with `apply false`. Gradle
// resolves each one exactly once, at the version the catalog names, and the
// modules then apply them by alias without re-requesting a version — which is
// what stops "already on the classpath with an unknown version" the moment two
// modules want two faces of the same Kotlin plugin (jvm here, android in :app).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
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
