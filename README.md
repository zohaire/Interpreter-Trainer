# Interpreter Trainer

Interpreter Trainer is a production-oriented Android 12+ practice studio for Arabic, English and French interpreters. It combines guided practice, recording, transcription, review and an optional online AI coach in one Compose application.

## What is included

- Simultaneous interpretation from local media, direct streams, supported video sites or source text
- Shadowing in Arabic, English and French with recording, live transcript and replay
- Consecutive interpretation with 15, 30 or 60-second source segments
- Three-language live transcription through Android speech recognition
- Local practice history with transcripts, recordings, notes and feedback
- Online Interpreter AI chat and evidence-based evaluation using Qwen3.8 27B through Puter
- Non-blocking local coach startup with explicit timeout and retry recovery
- Interruptible Interpreter Live conversations with selectable Studio, Warm and Broadcast neural voices
- Short, activation-safe streamed responses with device speech/transcription fallbacks
- System, light and dark themes with a responsive Material 3 interface
- Bluetooth, wired and external audio-route awareness

AI is optional and online-only: the app does not download a 400–500 MB neural model. Before the coach is opened for the first time, the app explains which data may leave the device and requires an explicit choice. See [PRIVACY.md](PRIVACY.md).

## Build and test

Requirements: JDK 21 and Android SDK 36.

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Optimized release packages use R8 and resource shrinking. Provide a private release key through environment variables:

```bash
INTERPRETER_RELEASE_KEYSTORE=/absolute/path/release.jks \
INTERPRETER_RELEASE_STORE_PASSWORD='...' \
INTERPRETER_RELEASE_KEY_ALIAS='...' \
INTERPRETER_RELEASE_KEY_PASSWORD='...' \
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease
```

Package ID: `com.interpretertrainer.app`

Minimum SDK: 31

Target/compile SDK: 36

## Release channels

Every pull request and push to `main` verifies the live model catalog, professional prompt policy, neural voice configuration, first-turn authentication, voice interruption state machine, Kotlin unit tests, Android lint, debug and optimized release builds, signature verification, and artifact upload. After all gates pass on `main`, the workflow creates a versioned preview tag and publishes a GitHub prerelease with an installable APK and an AAB.

GitHub preview packages use the repository's public, stable preview key so testers can install updates. That key is intentionally not a Play production key and must never be used for a store production release. The Play handoff is documented in [docs/PLAY_STORE_RELEASE.md](docs/PLAY_STORE_RELEASE.md).

## Data and security

- Practice records and recordings use app-private storage; Android backup is disabled.
- Deleting a session removes its owned recording, and stale orphan recordings are pruned safely.
- The privileged coach WebView restricts top-level navigation and exposes only its narrow native bridge.
- No AI provider secret is embedded in the APK; users authenticate with Puter.
- AI feedback is advisory and is not a certified interpreting examination result.

Please report security issues using [SECURITY.md](SECURITY.md). Contribution standards are in [CONTRIBUTING.md](CONTRIBUTING.md), and third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Ownership and support

Created and developed by **Zouhair Elachaqi**.

Support: **zohaireachak@gmail.com**
