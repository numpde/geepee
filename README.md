# GeePee

GeePee is a narrow Android app for following a preloaded GPX route and noticing when you drift off it.

It is intentionally not a map app. The main screen is just:

- the local route slice
- your position relative to the route
- distance back to the route
- a small amount of movement/status UI

## Status

This repo contains:

- a native Android app in `app/`
- the older browser proof-of-concept in `poc/`
- a checked-in GPX route fixture in `routes/`
- ADB mock-replay tooling in `scripts/`
- audit notes in `AUDIT.md`

## Requirements

- JDK 21 recommended
- Android SDK with:
  - `platforms;android-36`
  - `build-tools;36.0.0`
  - `platform-tools`
- a connected Android phone with `adb` debugging enabled

`local.properties` is intentionally ignored. Android Studio or your local SDK setup should generate/use that locally.

The repo assumes:

- Gradle runs on JDK 21
- CI runs on JDK 21
- app source/bytecode compatibility stays at Java 17 unless there is a reason to raise it

If your default `java` is only a runtime and does not include `javac`, point `JAVA_HOME` at a full JDK before running Gradle:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build

From the repo root:

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected phone:

```bash
./gradlew installDebug
adb shell am start -n dev.ra.geepee/.MainActivity
```

## Test

Run JVM unit tests:

```bash
./gradlew testDebugUnitTest
```

The matcher tests include:

- synthetic hairpin continuity checks
- a real-route regression against the checked-in Tisza GPX
- deterministic noisy and outlier stress cases on that same hairpin

## Mock Replay

Replay a checked-in GPX route to a real phone over ADB:

```bash
./scripts/replay_gpx_mock.py ./routes/unneplos-tisza-ride.gpx --points 35 --period-s 1.0 --speed-mps 2.2 --noise-m 2.0 --accuracy-m 4.0
```

More examples are in [scripts/REPLAY.md](scripts/REPLAY.md).

## Publish On GitHub

The simplest publish path in this repo is a GitHub Release with the debug APK attached.

Two ways to do it:

1. Push a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

2. Or run the `Release APK` workflow manually in GitHub Actions and provide a tag like `v0.1.0`.

That workflow will:

- build `app-debug.apk`
- rename it to `geepee-vX.Y.Z-debug.apk`
- create or update the matching GitHub Release
- upload the APK as a release asset

Important:

- this is a debug build, not a Play-ready signed release build
- it is fine for simple GitHub distribution / sideloading
- if you later want a proper distributable release APK, add a signing key and a release build workflow

## Repo Layout

- `app/`: Android app
- `poc/`: browser prototype kept for reference
- `routes/`: checked-in GPX fixtures
- `scripts/`: development and replay tooling
- `AUDIT.md`: side-effect boundaries, storage model, and review notes

## Privacy / Scope

GeePee is deliberately narrow:

- no network access
- no analytics
- no ads
- no background location permission
- no GPX copying into app storage

See [AUDIT.md](AUDIT.md) for the audit-oriented overview.
