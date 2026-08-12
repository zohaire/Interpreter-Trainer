# Interpreter Trainer

Interpreter Trainer is an Android 12+ application for Arabic, English and French interpreter practice.

## Current capabilities

- Branded Compose home dashboard with System / Light / Dark appearance modes
- Sight Translation practice with text-size controls, timing, notes and session saving
- Shadowing with Media3 playback, 0.75x / 1.0x / 1.25x speed, simultaneous trainee microphone recording, replay, notes and local feedback
- Consecutive Interpretation with 15 / 30 / 60 second playback-position segments, replay / previous / next controls, notes and transcript fields
- Local media import plus direct network audio/video URLs; progressive media, HLS and DASH playback modules are included
- Live external-audio indicator for Bluetooth, wired headset / headphones and other external outputs
- Live Transcription with Android SpeechRecognizer and ar-MA / en-US / fr-FR selection
- Practice History with Room persistence, recordings, notes, transcripts and saved feedback
- Interpreter Coach with two layers:
  - deterministic on-device evaluator and offline specialized fallback chatbot
  - optional self-hosted open-source Qwen2.5-1.5B-Instruct chatbot served by llama.cpp
- GitHub Actions debug APK build

## Enhanced open-source AI

The app does not require a commercial AI API key. The enhanced chatbot is designed to connect to infrastructure controlled by the app owner using:

- `Qwen/Qwen2.5-1.5B-Instruct-GGUF` — Apache-2.0 model
- `ggml-org/llama.cpp` — MIT-licensed inference runtime

See [`ai-server/README.md`](ai-server/README.md) for the Docker setup. The local Android evaluator remains authoritative for numeric performance scores. The generative model explains evidence, answers interpreter-training questions and provides practice suggestions without silently replacing measured scores.

## Media URL scope

The URL field is for a **direct playable media or stream URL**, such as a direct MP4/MP3/M4A/WebM file, HLS playlist or DASH manifest. A normal webpage URL is not automatically a media stream and may not be playable unless the site exposes a direct compatible media endpoint.

## Privacy and release security

The offline coach does not need a server. When enhanced AI is enabled, recent practice context can be sent to the self-hosted server so it can answer performance questions. Production deployments should use HTTPS and an authenticated reverse proxy. Cleartext HTTP is permitted only by the debug manifest to make same-Wi-Fi development testing practical.

## Build on GitHub

GitHub Actions uses Java 17 and Gradle 8.13, then runs:

```bash
gradle --no-daemon :app:assembleDebug
```

Open **Actions → Build Android Debug APK**, then download `interpreter-trainer-debug-apk` from a successful run's Artifacts section.

## Local development

Open the repository in Android Studio. The project targets API 36 with minimum SDK 31 (Android 12).

Package/application ID: `com.interpretertrainer.app`

## Ownership

Application owner: **Zouhair Elachaqi**  
Email: **zohaireachak@gmail.com**  
Phone: **0655156667**

Third-party model/runtime notices are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Next quality milestones

- Robust extractable-text PDF and DOCX import
- Automatic local/cloud transcription of recorded interpreter audio before evaluation
- Per-segment Room entities for consecutive notes, transcript and score history
- Better semantic evaluation for cross-language Arabic / English / French interpretation
- Automated tests for media URL errors, segment boundaries, database migrations and coach scoring
