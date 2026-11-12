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
  PLAN-FORMAT "Sides" (1.6.0): `PlanWorkoutExercise.eachSide` is an
  exercise's `b: 1` (every set done on both sides; anything but `1` is
  false), and `PlanSet.side` is a set's sixth tuple position, SHARE-FORMAT's
  flags byte read as `ShareLinkCodec` reads it — bits 1-2 masked, `3` both,
  bit 0 ignored, missing or junk both. Both optional and trailing, so every
  call site and every plan written before them is unchanged. Decode only:
  the Coach apps own their encoders. `PlanSidesTest` reads
  `fixtures/web-plan-per-side.txt`, a link Coach web's encoder wrote.
- **`CompactEncoding`** — the one envelope both codecs above sit on: raw
  DEFLATE (no zlib wrapper) plus base64url. If you need a third wire codec,
  it goes on this envelope too rather than inventing another one.
- **`DayKey`** — local `yyyy-MM-dd` day keys and arithmetic through
  `LocalDate`, never raw seconds (seconds repeat a day across a DST
  fall-back).
- **`DclPalette`** — the DUGCANLIFT palette as ARGB constants, the one
  source every app's theme colors wrap rather than resampling screenshots.
  `DclPaletteTest` checks it against `palette.json`, a byte-for-byte copy of
  the iOS kit's fixture, so a token that drifts from `LiftCore.Theme` fails
  the build. Rust text uses `ACCENT_TEXT`, not `ACCENT`; sage text uses
  `ACCENT2_TEXT` (`879585` dark, `56664F` light), not `ACCENT2`, whose dark
  `7C8B7A` is 4.40:1 on `SURFACE`. `ACCENT`/`ACCENT2` stay for fills and
  lines. The KDoc has the Material role map.
- **`IngredientParser`** / **`RecipeIngredient`** / **`RecipeNutrition`** —
  recipe/ingredient parsing and macro types, mirrored against the iOS kit's
  parser and both web builds' (Coach web's `coach/parser.js`, LIFT web's
  `app.js`): all four must agree on the same input line. Spelled-out units
  (1.9.0): a unit word written out — "1.5 pounds ground beef", "2 Tablespoons",
  "1 litre" — is read wherever its abbreviation is, in any case and with
  `.`/`,` trimmed as before, and stored as the abbreviation (`lb`, `tbsp`,
  `l`, and so on), so grouping and the count sentinel follow unchanged and no
  line that parsed before parses differently. Not read: `fl oz`, a bare `t`
  or `T`, `c.`, `pt`, `qt`, `stick`. This kit has no per-unit costing; its
  `grams` is set for `g` alone, spelled out or not. Whitespace (1.9.1): every
  whitespace character in a line becomes a plain space before parsing, from
  one set written out in the code — U+0009–U+000D, U+0020, U+00A0, U+1680,
  U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF, JavaScript's
  `\s` — rather than `Char.isWhitespace`, so a non-breaking space between the
  unit and the item splits them, and U+001C–U+001F are no longer trimmed as
  whitespace. `rawText` keeps the line exactly as given; the item comes out
  with plain spaces. Fiber (1.10.0): `RecipeNutrition.fiberG` is a `Double?`,
  null for "not known" and never 0, as the iOS kit's `NutritionFacts.fiberG`
  is. `RecipeJsonLd` leaves it null when a page states no `fiberContent` (it
  wrote 0 before), it stays null through `scaled`, and `PlanLinkCodec` reads
  `u`'s fifth number as sent -- every sender writes a number there, so a 0
  stays 0 -- and anything that is not a number as null. A consumer writing `u`
  must still send a number, 0 for unknown: the iOS decoders reject a null in
  `u` and would drop the whole plan.
  One answer for every line (1.10.0): vulgar fractions are
  read ("½ cup", "1½ cups", "1 ½ cups"; the eighteen Unicode names a fraction
  with, and U+2044 as `/`); a quantity must end where its number ends, so
  "1,5 kg", "1,000 g", "1-2 cups", "1 2 eggs" and "2² cups" are not read
  rather than read as their first number; a fraction is whole numbers, every
  number finite; and a trailing run of `.` `,` `:` `;` is trimmed from the
  unit word, nothing from its front, so "2 tbsp: oil" reads `tbsp` and
  ".tbsp" is no longer a unit. All four parsers run dugcanlift-kit's
  `data/ingredient-lines.json`, copied byte for byte with its `.sha256` to
  `liftcore/src/test/resources/fixtures/`; never edit either here, or
  regenerate them from this parser.
- **`Format`** — `Double.trimZeros()`, the one shared numeric-display rule.

Everything here stays boring and is covered by its own tests in
`liftcore/src/test/` — a change reaches every consumer app with no way for
any one of them to opt out.

## What's in `liftmap` (1.11.0)

`liftmap` is an **Android library** (AGP, Compose, MapLibre
`android-sdk-opengl` 13.7.1), the Kotlin twin of the Swift kit's
`LiftMapCore` / `LiftMap`: the same names, numbers, SQL schema and on-screen
words. It draws a run over streets the person downloaded, and nothing else.

- **`StreetTiles` / `StreetAreaPlan`** -- tile math and what a download
  covers: a wide layer (zoom 0-10, 100 km) and a detail layer (zoom 11-14,
  10, 25 or 50 km; `forRoute` grows a route's bounds by 2 km).
- **`StreetStore`** -- every area in one MBTiles file,
  `filesDir/liftmap/streets.mbtiles`, on `androidx.sqlite`'s bundled driver,
  journal mode DELETE (MapLibre opens it read-only). Deleting an area keeps
  tiles another area owns; an unfinished download is not an area; `revision`
  goes up when an area finishes or is deleted. Each app excludes the file from
  Android backup in its own `data_extraction_rules.xml`.
- **`MapStyle`** -- the shared styles filled with `mbtiles://` and
  `asset://` glyph URLs. No server is named; a map is only ever built with
  `Style.Builder().fromJson`.
- **`MapDownload`** -- **the one request**: OpenFreeMap's TileJSON and tiles,
  https on `tiles.openfreemap.org` only (redirects included), four at a time,
  `User-Agent: LIFT-maps/1`, no cookies, and only when the person starts it.
- **`MapNetworkGuard`** -- tells MapLibre it is offline and hands it an HTTP
  client that refuses every call.
- **`RouteMap`**, **`MapsCard`**, **`StreetsSheet`** -- the route over the
  streets (or the app's own canvas, passed as `fallback`, when no area covers
  it), the Maps settings card, and the download sheet.

`:liftmap` declares no permissions of its own. Each app declares `INTERNET`
(for the download) and removes the network-state, Wi-Fi-state and location
permissions MapLibre's manifest merges in, with `tools:node="remove"`.

**Writes pause maps, by handshake.** Every write to the store goes through
a writer -- `MapDownload.download` / `resume`, the download sheet, and
`StreetStore.removeArea` -- which raises the store's gate (`writing`) first,
then waits for every open map to close (`awaitMapsClosed`: `mapsOpen` back to
0, or 2 s), and only then writes; the gate comes down in a `finally`, however
the write ends. While the gate is up a map cannot even open: the store refuses
it under the same lock, so it draws the line and tries again when the gate
comes down. After the 2 s timeout nothing keeps a stuck map and the writer
apart (`busy_timeout` cannot: the two use different SQLite libraries), which
is why the timeout is only for a map that never closes, and why the on-device
check below matters.
A delete and a cancelled download's cleanup run on the store's own
long-lived scope, so leaving the screen while they wait never drops them. While
the gate is up every `RouteMap` draws the plain line, which disposes its
MapView, and a map counts itself closed only after that MapView's
`onDestroy`. When the write ends the map is made again from the file as it now
is. The writing calls (`put`, `beginArea`, `finishArea`, the synchronous
`deleteArea`, the constructor) are internal, so nothing outside the module
can write past the gate, and `StreetStore.standard(ctx)` is the only way an
app gets a store (one per file). Disposing a MapView stops MapLibre's reads;
MapLibre may still keep an idle connection to the file open, which reads
nothing.

On Android the handshake is load-bearing: the store writes through
androidx's bundled SQLite and MapLibre reads through its own native SQLite,
two different libraries in one process, and POSIX file locks do not exclude
each other within a process, so SQLite itself keeps nothing apart.
Not yet checked on a device: open a run's map, start an Update from the Maps
card, and watch for crashes or blank tiles while it runs and after it
finishes.

The styles and fonts in `liftmap/src/main/assets/map/` are dugcanlift-kit's
(its `map/`), with the matching lines of its `map-files.sha256`; the kit's
manifest also lists web files this module does not carry. All 256 glyph
ranges ship: the five that hold glyphs, and an empty file for each of the
rest, because MapLibre never draws a tile still waiting on a range whose file
is missing. `SharedMapFilesTest` pins the files to those lines, so copy files
and lines over together and never edit them here. `NoNetworkTest` fails the
build if network code or an `http` literal appears outside `MapDownload.kt`,
OkHttp outside `MapNetworkGuard.kt`, or a style is loaded from anything but a
`Style.Builder` of local JSON.

## Coordinates

From 1.11.0 this repo publishes **two** modules, `liftcore` (pure JVM) and
`liftmap` (an Android library that brings MapLibre, about 11 MB per ABI), and
JitPack serves each under its own coordinate:

```kotlin
implementation("com.github.dougray.dugcanlift-kit-android:liftcore:<tag>")
implementation("com.github.dougray.dugcanlift-kit-android:liftmap:<tag>")
```

Both resolve (checked against JitPack's builds of 1.11.0 and 1.11.1, under
`https://jitpack.io/com/github/dougray/dugcanlift-kit-android/<module>/<tag>/`).
Apps use this per-module form, and only the modules they need.

The old repo-level coordinate, `com.github.dougray:dugcanlift-kit-android:<tag>`,
is from 1.11.0 an **aggregate** POM that depends on both modules, so anything
still using it pulls in `:liftmap` and MapLibre too. Do not use it for 1.11.0
or later. Up to 1.10.0 it was `liftcore` alone, the only module then.

## Versioning: pin exact, always

Both LIFT Android and Coach Android pin an **exact** release tag of this
repo — never a branch, never `SNAPSHOT`, never a version range. Bump
deliberately in each consumer's `build.gradle.kts` when picking up a new
release; never let Gradle pick a version for you. A range or branch
dependency means a consumer's behavior can change on a rebuild with no
commit in that consumer's own history to point at — exactly the failure
mode an exact pin exists to rule out.

Each module's `version` (`liftcore/build.gradle.kts`,
`liftmap/build.gradle.kts`) is read from the `KIT_VERSION` Gradle property
(or environment variable), defaulting to `0.1.0-SNAPSHOT` when neither is
set — that default is for a local, unpublished build only and should never
appear in a consumer's dependency line.

## Cutting a release

A tag is a release. Both modules publish from the one tag, at the same
version (`jitpack.yml` publishes `:liftcore` and `:liftmap` together, on JDK
17), and JitPack builds a tag the first time anyone asks for it, not when it
is pushed.

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
   own commit -- every module the consumer uses, to the same tag.

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
substitution for **each** module the consumer uses, so the local projects win
over the JitPack artifacts with no network resolution and no tag needed:

```kotlin
includeBuild(kitPath) {
    dependencySubstitution {
        substitute(module("com.github.dougray.dugcanlift-kit-android:liftcore")).using(project(":liftcore"))
        substitute(module("com.github.dougray.dugcanlift-kit-android:liftmap")).using(project(":liftmap"))
    }
}
```

Map **both** modules, as LIFT Android's `settings.gradle.kts` does: a
consumer that substitutes only `liftcore` still resolves `liftmap` from
JitPack, at whatever version it pins. `:liftmap` is an Android library, so the
included build needs the Android SDK, and a composite build needs the kit and
the consumer on the same AGP version: the kit pins AGP 9.4.1 while Coach
Android's `main` is on 9.5.1, so align them locally before including the kit
there. The apps' CI never builds this way; it always resolves the JitPack
coordinates. Remove the line (or delete the file) to go back to building
against the pinned tag. This only works if the local `dugcanlift-kit-android`
checkout is a sibling directory at the path given; it does not fetch or clone
anything itself.

## Local commands

```bash
./gradlew test                              # run tests (both modules; :liftmap needs the Android SDK)
./gradlew :liftcore:publishToMavenLocal :liftmap:publishToMavenLocal   # publish to ~/.m2
```
