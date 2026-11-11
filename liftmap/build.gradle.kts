plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = "com.github.dougray"
version = providers.gradleProperty("KIT_VERSION").orNull ?: System.getenv("KIT_VERSION") ?: "0.1.0-SNAPSHOT"

android {
    namespace = "com.dugcanlift.liftmap"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // DclPalette's colors only, and they are `const val`s, compiled into this
    // module's classes: no liftcore class is loaded at run time, so the
    // published POM names no liftcore artifact (which would not resolve on
    // JitPack under the repo-level coordinate the apps use).
    compileOnly(project(":liftcore"))

    implementation(platform(libs.androidx.compose.bom))
    // `api`: the public signatures name Modifier, Composable and StateFlow.
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.sqlite.bundled)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}

// Unit tests run on the build machine's JVM, so they take SQLite's bundled
// native library for this machine (sqlite-bundled-jvm) instead of the Android
// one, which holds only Android ABIs.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled"))
            .using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.androidxSqlite.get()}"))
    }
}

// SharedMapFilesTest and NoNetworkTest read the assets and the sources off
// disk, so a change to either must rerun them.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/assets"), fileTree("src/main/kotlin"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "liftmap"
            afterEvaluate { from(components["release"]) }
        }
    }
}
