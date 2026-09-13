plugins { alias(libs.plugins.kotlin.jvm); `maven-publish` }
group = "com.github.dougray"
version = providers.gradleProperty("KIT_VERSION").orNull ?: System.getenv("KIT_VERSION") ?: "0.1.0-SNAPSHOT"
kotlin { jvmToolchain(17) }
dependencies {
    // Android ships org.json in the platform; declaring it compileOnly keeps a second copy out of the apps.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
java { withSourcesJar() }
publishing { publications { create<MavenPublication>("maven") { from(components["java"]); artifactId = "liftcore" } } }
tasks.test { useJUnit() }
