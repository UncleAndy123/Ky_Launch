# Contributing to Flip Launcher

Thanks for hacking on Flip Launcher. This guide gets you from a fresh clone to
a built APK, and covers the few project-specific conventions.

## Prerequisites

You need two things on your machine. Everything else (notably the JDK) is
already whatever's running Gradle for you — see below.

### 1. Android SDK

The build needs `platforms;android-36` and `build-tools;36.0.0` (AGP 9.3's
defaults). If you use Android Studio, install those via the SDK Manager and
you're done. For a headless / command-line setup on macOS:

```bash
brew install --cask android-commandlinetools
# Point sdkmanager at a sdk root and install the required components:
sdkmanager --sdk_root="$HOME/Library/Android/sdk" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
sdkmanager --sdk_root="$HOME/Library/Android/sdk" --licenses
```

Then tell the build where the SDK is, using **either**:

- a `local.properties` file in the repo root (gitignored):
  ```
  sdk.dir=/Users/you/Library/Android/sdk
  ```
- or an `ANDROID_HOME` environment variable pointing at the same path.

### 2. adb (for installing / debugging on a device)

`adb` should be on your `PATH`. It comes with the SDK's `platform-tools`, or on
macOS: `brew install --cask android-platform-tools`.

### What you do NOT need

- **A specific JDK.** Compilation just runs on whichever JDK is already
  launching Gradle for you — Android Studio's own bundled JBR when building
  from the IDE, or whatever `JAVA_HOME`/`PATH` points at on the command
  line. Any version 17 through 26 works; do not set `JAVA_HOME` or prefix
  Gradle commands with it unless you're troubleshooting something else. The
  build still always produces Java 17-level bytecode
  (`android.compileOptions` in `app/build.gradle.kts`) no matter which of
  those JDKs compiles it, and never reaches out to the network for a JDK.

## Building & installing

```bash
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# Install onto a connected device / emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set Flip Launcher as the default Home app (Options -> Set as Default Launcher),
and grant Notification Access from Launcher Settings for the Notices screen and
Home notification badges.

## Project layout

Sources live under `app/src/main/java/com/flipos/launcher/`:

- `activities/` — every screen Activity plus the shared `BaseListActivity`.
- `ui/` — RecyclerView adapters and custom views.
- `data/` — prefs, repositories, and the built-in icon/wallpaper registries.
- `service/` — the notification listener.
- `util/` — small extensions.

Dependency and plugin versions are centralized in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Adding a built-in icon or wallpaper

Bundled assets live in `app/src/main/res/drawable-nodpi/` (the single source of
truth — there are no staging folders).

1. Drop the PNG (icon) or JPG (wallpaper) into
   `app/src/main/res/drawable-nodpi/`.
2. Register the filename (without extension) in the matching list:
   - icons: `BuiltInIcons.NAMES` in `data/BuiltInIcons.kt`
   - wallpapers: `BuiltInWallpapers.NAMES` in `data/BuiltInWallpapers.kt`

Step 2 is required — resources are looked up by name at runtime and are not
auto-discovered.
