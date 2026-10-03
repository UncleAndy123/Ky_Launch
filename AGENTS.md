# Flip Launcher (KaiOS-style Android launcher)

`com.flipos.launcher` — minSdk 21, targetSdk 34, compileSdk 36, Kotlin, no Compose.

Guidance for AI agents and human contributors working in this repo. Kept
machine-agnostic on purpose: it should hold for any fork/checkout, not one
person's laptop.

## Build & toolchain

- Build the debug APK from the repo root:
  ```
  ./gradlew :app:assembleDebug
  ```
  Output: `app/build/outputs/apk/debug/app-debug.apk`.
- **No specific JDK is required.** Compilation runs on whichever JDK launches
  Gradle (Android Studio's own bundled JBR when building from the IDE, or
  `JAVA_HOME`/`PATH` on the command line) — any version 17 through 26. The
  app's `android.compileOptions.sourceCompatibility`/`targetCompatibility`
  (`app/build.gradle.kts`) pin the compiled bytecode to Java 17 regardless of
  which of those JDKs actually runs the compiler; with AGP 9's built-in
  Kotlin support that alone also pins Kotlin's own `jvmTarget`, so there's no
  separate `jvmToolchain(...)` declaration to satisfy. `gradle.properties`
  also disables toolchain auto-download
  (`org.gradle.java.installations.auto-download=false`) as defense in depth,
  so nothing here ever requires reaching `api.foojay.io` — important behind
  proxies/VPNs that break TLS to it.
- **Android SDK is required** and is the one thing not auto-provisioned. Point
  the build at an SDK either via `local.properties` (`sdk.dir=/path/to/sdk`,
  gitignored) or the `ANDROID_HOME` environment variable. You need
  `platforms;android-36` and `build-tools;36.0.0` (AGP 9.3's defaults). See
  `CONTRIBUTING.md` for a from-scratch setup.
- Versions are centralized in `gradle/libs.versions.toml` (AGP + AndroidX /
  Material dependencies). AGP 9 ships built-in Kotlin, so the
  `org.jetbrains.kotlin.android` plugin is intentionally not applied.

## Devices

- `adb` is expected on `PATH` (e.g. Homebrew `android-platform-tools`). Don't
  hardcode absolute `adb`/SDK paths — they differ per machine.
- Prefer an emulator for exploratory or repro-driven UI testing. This is a
  portrait, D-pad/soft-key launcher, so any small AVD works; boot it and poll
  `adb shell getprop sys.boot_completed` until it returns `1`.
- A real physical device may be connected and **actively in use by the owner**.
  Don't drive it with synthetic `input keyevent`/`input tap` unless explicitly
  asked to test on hardware — it can collide with real input and navigate the
  user away from what they were doing.
- Some phones ship key-remapping apps (e.g. "Button Mapper") that intercept
  hardware keys, making synthetic `adb shell input keyevent` behave
  inconsistently (dropped/double-fired events). If hardware-key repro looks
  contradictory or flaky, switch to the emulator for clean signal.
- `uiautomator dump` can capture a transitional frame for fast-changing focus
  state (stale/non-monotonic). Prefer `exec-out screencap -p` with ~0.5-0.8s
  settle time after the triggering input, or cross-check
  `dumpsys window | grep mCurrentFocus` for the foregrounded Activity.
- **Notification listener access is non-functional on Kyocera Android 9+
  builds** (confirmed on the E4810 and E4811). `Settings.Secure` and
  `adb shell cmd notification allow_listener` both appear to succeed - our
  component shows up in `settings get secure enabled_notification_listeners`
  - but `adb shell dumpsys notification | grep -i listener` shows the
  "Allowed"/"Live" listener registry never actually includes us, with zero
  log trace even during an explicit grant attempt; only the OEM's own two
  listener components (`jp.kyocera.server.sublcd.NotificationListener`,
  `jp.kyocera.kyocerahome.notification.NotificationListener`) ever bind. The
  same app code works fine on the older Kyocera 4610 (Android 7), so this is
  a platform-level restriction on the newer device lineage, not a bug in our
  manifest/service/grant flow - don't re-diagnose it from scratch.
  `service/NotificationAccessibilityService.kt` is the fallback (a separate
  OS subsystem, `AccessibilityService`'s notification events, not gated by
  the same allowlist) - it only feeds the Home banner, not Notices/icon
  dots, since accessibility has no "list active notifications" or removal
  event.

## Project structure

`app/src/main/java/com/flipos/launcher/` is split into sibling packages:

- `activities/` — every screen Activity plus `BaseListActivity`.
- `ui/` — RecyclerView adapters and custom views.
- `data/` — prefs, repositories, and the built-in icon/wallpaper registries.
- `service/` — the notification listener.
- `util/` — small extensions.

Key pieces:

- `BaseListActivity` is shared scaffolding (title bar + RecyclerView +
  SoftKeyBar) for every vertical list screen (Options, Launcher Settings,
  Hide Apps, Shortcuts, App/Activity Picker, Notices). It also owns the
  accent-color theme-overlay-on-`onCreate` + recreate-on-resume-if-changed
  pattern — new list screens should extend it rather than reinventing this.
- `ListRowAdapter` + `Row` is the generic one-line-or-icon-row adapter reused
  across those list screens; `AppGridAdapter` is the App Drawer's icon grid;
  `NoticeRowAdapter` is the richer 3-line notice row.
- `LauncherPrefs` is the single SharedPreferences wrapper — all settings
  (icon size/shape/pack, accent color, drawer view mode, badges, shortcuts,
  hidden apps, per-app icon overrides) live there.
- `IconShapeRenderer` masks app icons into the user's chosen shape (adaptive
  icons composite their own fg/bg layers then get clipped; legacy icons get
  an optional synthesized tinted background disc).

## Bundled icons & wallpapers

Built-in icons and wallpapers live directly in
`app/src/main/res/drawable-nodpi/` — that folder is the single source of truth
(there are no separate staging folders). To add one:

1. Drop the PNG (icon) or JPG (wallpaper) into
   `app/src/main/res/drawable-nodpi/`.
2. Add the filename (no extension) to the matching registry:
   `BuiltInIcons.NAMES` in `data/BuiltInIcons.kt` or `BuiltInWallpapers.NAMES`
   in `data/BuiltInWallpapers.kt`.

Resources aren't auto-discovered, so step 2 is required for the asset to show
up in the picker.
