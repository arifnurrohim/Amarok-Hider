<p align="center">
  <img src="fastlane/metadata/android/en-US/images/featureGraphic.png" alt="Amarok banner" width="720">
</p>

<h1 align="center">Amarok</h1>

<p align="center">
  A lightweight file hider for Android — privacy without encryption overhead.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="License: Apache 2.0" src="https://img.shields.io/badge/License-Apache%202.0-blue.svg">
  <img alt="Unofficial fork" src="https://img.shields.io/badge/fork-of%20deltazefiro%2FAmarok--Hider-blue">
</p>

> [!NOTE]
> This is an unofficial, file-only fork of
> [deltazefiro/Amarok-Hider](https://github.com/deltazefiro/Amarok-Hider),
> based on upstream v0.10.1. It is not affiliated with the upstream project,
> F-Droid, or IzzyOnDroid. For official builds and documentation, use upstream.

## What is Amarok?

Amarok hides selected files by obfuscating filenames and, optionally, file
headers or contents. It is built for casual privacy: hiding a file is fast and
does not require encrypting a large movie or archive first.

> [!WARNING]
> **Amarok is NOT an encryption tool.**  
> It hides data but does not protect it cryptographically. Do not use it for
> high-value secrets.

## What this fork changes

- **File-only focus** — app-hiding functionality and its related UI were removed.
- **Large-folder reliability** — the file tree is snapshotted before renaming,
  files are processed before directories, and post-hide media scans are skipped.
- **Safer folder renaming** — folder-name obfuscation is opt-in and disabled by
  default. Existing obfuscated folder names are still restored when unhiding.
- **Long filename support** — names that exceed Android's filename limit are
  compressed, or mapped through a local hash-to-name table.
- **Visible, recoverable operations** — progress notification, cancel action,
  wake lock, 60-second watchdog, and a journal that offers recovery after a crash
  or forced stop.
- **GitHub Actions** — debug builds on push/PR, signed FOSS releases on `v*` tags.

## Features

- **One-click privacy** — hide or unhide selected files instantly.
- **Fast on large files** — rename and header obfuscation avoid rewriting file
  contents by default.
- **Flexible hiding** — filename obfuscation without root; optional root-based
  permission hiding.
- **Panic button** — a discreet floating button for quick hiding.
- **Quick Settings tile** — toggle visibility without opening the app.
- **Secure access** — protect Amarok with a password or fingerprint.
- **Camouflage mode** — disguise Amarok as a calendar app.

## Install

Prebuilt APKs are published on the **Releases** page of this repository.

- Package name: `deltazero.amarok.foss`
- Minimum Android version: Android 8.0 (API 26)
- Recommended flavor: `foss`

For local testing:

```bash
adb install -r Amarok-<version>-foss.apk
```

## Build from source

### Requirements

- JDK 17 or newer
- Android SDK Platform 36 and Build-Tools 36.0.0
- The included Gradle wrapper

```bash
git clone <this-repository-url>
cd Amarok

# Debug FOSS build
./gradlew assembleFossDebug

# Release FOSS build
./gradlew assembleFossRelease
```

Output paths:

```text
app/build/outputs/apk/foss/debug/
app/build/outputs/apk/foss/release/
```

Release builds are unsigned unless signing properties are provided:

```bash
./gradlew assembleFossRelease \
  -PRELEASE_STORE_FILE=/path/to/release.keystore \
  -PRELEASE_STORE_PASSWORD=your-store-password \
  -PRELEASE_KEY_ALIAS=your-key-alias \
  -PRELEASE_KEY_PASSWORD=your-key-password
```

Never commit a keystore or its passwords.

## GitHub Actions

| Workflow | Trigger | Result |
| --- | --- | --- |
| `Build` | Push, pull request, or manual run | FOSS debug APK artifact |
| `Release` | Push of a `v*` tag | Signed FOSS APK + SHA-256 on GitHub Releases |

The release workflow reads the version from the tag (`v1.2.3` → `1.2.3`) and
uses the commit count as `versionCode`.

Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Required | Description |
| --- | --- | --- |
| `KEYSTORE_BASE64` | Yes | Base64-encoded release keystore |
| `KEYSTORE_PASSWORD` | Yes | Keystore password |
| `KEY_ALIAS` | No | Key alias (defaults to `amarok`) |
| `KEY_PASSWORD` | No | Key password (defaults to the keystore password) |

Publish a release:

```bash
git tag v0.10.1
git push origin v0.10.1
```

## Project layout

```text
app/src/main/java/     Application source
app/src/main/res/      Resources and translations
app/src/foss/          FOSS flavor
app/src/playstore/     Play Store flavor
app/src/appcenter/     AppCenter flavor
fastlane/metadata/     Store metadata and changelogs
.github/workflows/     Build and release automation
```

## Contributing

Bug reports, feature requests, and code contributions for this fork are welcome.
Please open an issue or pull request in this repository.

Translation work should target the
[upstream Weblate project](https://hosted.weblate.org/engage/amarok-hider/).

## Credits

- **[deltazefiro/Amarok-Hider](https://github.com/deltazefiro/Amarok-Hider)** —
  the original project this fork is based on.
- **[Icongeek26](https://www.flaticon.com/authors/icongeek26) &
  [Freepik](https://www.freepik.com)** — iconography.
- **[JetBrains](https://www.jetbrains.com/community/opensource/#support)** — IDE
  support.
- All upstream contributors and translators.

## License

Licensed under the [Apache License 2.0](LICENSE).

## Disclaimers

**Amarok is provided "as is" without warranties or conditions of any kind.**
You are fully responsible for any harm, data loss, or consequences resulting
from its use. Back up important data before using file-hiding tools.
