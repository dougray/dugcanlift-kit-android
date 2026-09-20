# dugcanlift-kit-android

Shared Kotlin core for LIFT Android and Coach Android.

## What's in `liftcore`

`liftcore` is a pure-JVM Kotlin module (no Android Gradle plugin, no
`android.*` imports, no Google Play Services, no runtime network dependency)
holding domain logic shared between the two apps:

- **`ShareLinkCodec`** (`ShareLink.kt`) — the SHARE-FORMAT link codec: a
  lifter's training/nutrition data, encoded by LIFT (Android, iOS, or the
  web app) and decoded by Coach. `ShareClient`/`ShareGoal`/`ShareDay`/
  `ShareExercise`/`ShareFood`/`SharePayload`/`ShareDecodeResult` are the
  typed payload shapes, plus the optional outdoor parts `ShareOutdoor` (a
  day's `o`), `ShareOutdoorBest` (`ob`) and `ShareLastRoute` (`lr`).
- **`ShareSide`** (`ShareLink.kt`, 1.5.0) — which limb a set was performed
  with, as SHARE-FORMAT's set tuple carries it: **flags bits 1-2**, `0` both,
  `1` left, `2` right, beside bit 0's warmup flag. `ShareSet.side` is optional
  and trailing, and null means both — which is what every set written before
  per-limb tracking means, so nothing has to be guessed at. No new tuple
  position, so a decoder that knows nothing of sides still reads the weight,
  the reps and the volume; a both-sided set still encodes 0 flags and still has
  them trimmed away, so a log with no per-limb sets is byte for byte the link
  this codec wrote at 1.4.0. Bits 1-2 holding `3` — a value this format does
  not define — decodes as both rather than as an invented side.
- **`OutdoorShare`** (`OutdoorShare.kt`) — builds those outdoor parts from
  `OutdoorShareActivity` values: day tuples, all-time bests, the trimmed and
  thinned last route, and the encoded polyline. Pinned to fixtures LIFT web's
  `outdoor.js` wrote; never regenerate them from this code.
- **`NutrientDetails`** / **`ShareNutrients`** (`NutrientDetails.kt`, 1.4.0) —
  saturated fat, sugar and sodium: tracked and shown, never targeted.
  `ShareFood.details` is a food's `fe` entry and `ShareDay.nutrientTotals`
  (`ShareNutrientTotals`) is the day's `fx`, both optional trailing parameters
  so older call sites compile and older payloads round-trip unchanged.
  `ShareNutrients.dayTotals` builds `fx` from a day's foods (per serving ×
  servings, totals only over known values, with counts) and `itemRow` builds
  an `fe` entry with trailing nulls trimmed. Grams to one decimal, sodium to
  whole mg, half-up (`Math.round`). A malformed `fx`/`fe` decodes as null
  without failing the payload; an `fe` whose length disagrees with `f` is
  ignored rather than misaligned. `PlanRecipe.nutrientDetailsPerServing` is a
  plan recipe's `ux`, and `RecipeNutrition` carries the same three as
  optional fields, read from schema.org JSON-LD by `RecipeJsonLd` (sodium
  published in grams becomes milligrams).
- **`PlanLinkCodec`** (`PlanLink.kt`) — the PLAN-FORMAT link codec for
  workout plans (`PlanSet`, `PlanWorkoutExercise`, …).
- **`CompactEncoding`** — the one envelope both codecs above sit on: raw
  DEFLATE (no zlib wrapper) plus base64url. If you need a third wire codec,
  it goes on this envelope too rather than inventing another one.
- **`DayKey`** — local `yyyy-MM-dd` day keys and arithmetic through
  `LocalDate`, never raw seconds (seconds repeat a day across a DST
  fall-back).
- **`DclPalette`** — the DUGCANLIFT palette as ARGB constants, the one
  source every app's theme colors wrap rather than resampling screenshots.
- **`IngredientParser`** / **`RecipeIngredient`** / **`RecipeNutrition`** —
  recipe/ingredient parsing and macro types, mirrored against the iOS and
  web parsers (all three must agree on the same input line).
- **`Format`** — `Double.trimZeros()`, the one shared numeric-display rule.

Everything here stays boring and is covered by its own tests in
`liftcore/src/test/` — a change reaches every consumer app with no way for
any one of them to opt out.

## Coordinates

Consumers depend on this as:

```kotlin
implementation("com.github.dougray:dugcanlift-kit-android:<tag>")
```

resolved through JitPack. Note the artifact name is `dugcanlift-kit-android`
— the **repository's** name — not `liftcore`, the Gradle **module's** own
`artifactId` (set in `liftcore/build.gradle.kts`'s `publishing` block).
JitPack's default single-module build publishes a repo's output under
`com.github.<user>:<repo-name>:<tag>`, regardless of what the module calls
itself internally, since a consumer resolving `com.github.dougray:<repo>`
has no way to know or care how many modules the build has inside it. If this
repo ever grows a second published module, that module gets its own
JitPack-visible coordinate (`com.github.dougray:dugcanlift-kit-android:<module>:<tag>`);
until then, depend on the repo-level coordinate above, not `liftcore`
directly — `liftcore` as a Maven coordinate resolves nowhere on JitPack.

## Versioning: pin exact, always

Both LIFT Android and Coach Android pin an **exact** release tag of this
repo — never a branch, never `SNAPSHOT`, never a version range. Bump
deliberately in each consumer's `build.gradle.kts` when picking up a new
release; never let Gradle pick a version for you. A range or branch
dependency means a consumer's behavior can change on a rebuild with no
commit in that consumer's own history to point at — exactly the failure
mode an exact pin exists to rule out.

`liftcore/build.gradle.kts`'s `version` is read from the `KIT_VERSION`
Gradle property (or environment variable), defaulting to `0.1.0-SNAPSHOT`
when neither is set — that default is for a local, unpublished build only
and should never appear in a consumer's dependency line.

## Cutting a release

1. Land the change on `main` and confirm `./gradlew test` is green.
2. Tag it with the version consumers will pin — semver, no `v` prefix, e.g.:
   ```bash
   git tag 1.1.0
   git push origin 1.1.0
   ```
3. JitPack builds on first request, not on push: either visit
   `https://jitpack.io/#dougray/dugcanlift-kit-android/1.1.0` once to trigger
   and confirm the build succeeds, or just let a consumer's Gradle sync be
   the first request — but check the JitPack build log if that sync fails,
   rather than assuming the consumer's own config is wrong.
4. Bump the pin in each consumer (`coach-android/app/build.gradle.kts`,
   `LIFT/android/app/build.gradle.kts`) to the new tag, deliberately, in its
   own commit.

There is no `main`-branch "latest" consumers should ever depend on — a tag
is the only thing that is safe to pin.

## Local development against a checkout

Working on the kit and a consumer at the same time without publishing a tag
for every iteration: in the consumer repo's `local.properties` (gitignored,
not committed), add

```
kitPath=../dugcanlift-kit-android
```

(path relative to that consumer's Gradle root — `coach-android`'s is one
level up; `LIFT/android`'s needs an extra `../`). The consumer's
`settings.gradle.kts` turns that into an `includeBuild` with a dependency
substitution — `substitute(module("com.github.dougray:dugcanlift-kit-android")).using(project(":liftcore"))`
— so the local `:liftcore` project wins over the JitPack artifact with no
network resolution and no tag needed. Remove the line (or delete the file)
to go back to building against the pinned tag. This only works if the local
`dugcanlift-kit-android` checkout is a sibling directory at the path given;
it does not fetch or clone anything itself.

## Local commands

```bash
./gradlew test                              # run tests
./gradlew :liftcore:publishToMavenLocal     # publish to ~/.m2 for local consumption
```
