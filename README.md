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
- Interpreter Coach with:
  - deterministic on-device evaluator for measured performance scores
  - a basic rule-based coach available before model installation
  - a real on-device neural chatbot using Qwen2.5-0.5B-Instruct through llama.cpp after a one-time model download
- GitHub Actions debug APK build

## Real on-device Interpreter AI

The app does not require a commercial AI API key or an owner-hosted AI server.

The neural chatbot uses:

- `Qwen/Qwen2.5-0.5B-Instruct-GGUF`, Q4_0 quantization — Apache-2.0 model
- `ggml-org/llama.cpp` — MIT-licensed on-device inference runtime

The GGUF model is intentionally **not bundled inside the APK**. In **Interpreter Coach**, the user taps **Install Interpreter AI** once. The app downloads the approximately 429 MB model into app-private storage, verifies its SHA-256 digest, loads it with llama.cpp, and then generates responses locally on the phone.

The UI clearly labels whether an answer came from **Interpreter AI • neural** or **Basic coach • rule-based**. A neural-generation failure is shown as an error instead of silently pretending that a rule-based fallback is the neural model.

The deterministic `LocalInterpreterCoach` remains authoritative for numeric performance scores. The neural model can explain the measured evidence, discuss interpreting technique, use relevant saved practice context and suggest exercises, but it is instructed not to fabricate or overwrite measured scores.

## Media URL scope

The URL field is for a **direct playable media or stream URL**, such as a direct MP4/MP3/M4A/WebM file, HLS playlist or DASH manifest. A normal webpage URL is not automatically a media stream and may not be playable unless the site exposes a direct compatible media endpoint.

## Privacy

After the one-time model download, neural chat inference is on-device. Practice-session context used by the chatbot stays on the phone and is passed directly to the local inference runtime. No ChatGPT/OpenAI/Claude/Gemini API key is required.

## Build on GitHub

The repository pins llama.cpp as a git submodule. GitHub Actions checks out submodules, installs the Android NDK/CMake toolchain used by the llama.cpp Android binding, builds the native runtime and then assembles the debug APK.

The Android build command remains:

```bash
gradle --no-daemon :app:assembleDebug
```

Open **Actions → Build Android Debug APK**, then download `interpreter-trainer-debug-apk` from a successful run's Artifacts section.

## Local development

Clone with submodules:

```bash
git clone --recurse-submodules https://github.com/zohaire/Interpreter-Trainer.git
```

Open the repository in Android Studio. The project targets API 36 with minimum SDK 31 (Android 12). The on-device llama runtime currently builds for `arm64-v8a`, which covers modern 64-bit Android phones.

Package/application ID: `com.interpretertrainer.app`

## Ownership

Application owner: **Zouhair Elachaqi**  
Email: **zohaireachak@gmail.com**  
Phone: **0655156667**

Third-party model/runtime notices are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Next quality milestones

- Automatic local transcription of recorded interpreter audio before evaluation
- Better semantic evaluation for cross-language Arabic / English / French interpretation
- Per-segment Room entities for consecutive notes, transcript and score history
- Robust extractable-text PDF and DOCX import
- Automated tests for model installation, media URL errors, segment boundaries, database migrations and coach scoring
