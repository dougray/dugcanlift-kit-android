# dugcanlift-kit-android

Shared Kotlin core for LIFT Android and Coach Android.

## What this is

`liftcore` is a pure-JVM Kotlin module (no Android Gradle plugin, no
`android.*` imports, no Google Play Services, no runtime network
dependency) holding domain logic shared between the two apps. Domain code
lands in Tasks 3-5 of the Coach Android plan; this repo currently ships
only the module skeleton, `maven-publish` wiring, and a smoke test to keep
the build green.

## Versioning

Both LIFT Android and Coach Android pin an **exact** version of
`liftcore` — no dynamic or range versions. Bump deliberately in each app
when picking up a new `liftcore` release. Releases are versioned by the
git tag via `KIT_VERSION` (JitPack passes it as a Gradle property; a
local publish can pass either the property or the env var).

## Coordinates

JitPack builds this repo from tags. The exact Maven coordinate
(`com.github.dougray:liftcore:<tag>` vs. the repo-level
`com.github.dougray:dugcanlift-kit-android:<tag>` artifact name) is
confirmed in Task 6 once the first tag has built successfully on JitPack.

## Local development

```bash
./gradlew test                              # run tests
./gradlew :liftcore:publishToMavenLocal     # publish to ~/.m2 for local consumption
```
