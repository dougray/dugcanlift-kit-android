// Every plugin is put on the build classpath once, here, so :liftcore (Kotlin
// JVM) and :liftmap (Android library, AGP's built-in Kotlin) share one Kotlin
// Gradle plugin rather than each loading its own.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
