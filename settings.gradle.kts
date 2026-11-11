pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral(); google() } }
rootProject.name = "dugcanlift-kit-android"
include(":liftcore")
// The street map (MapLibre): an Android library, so building it needs the Android SDK.
include(":liftmap")
