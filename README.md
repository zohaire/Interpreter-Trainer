# Interpreter Trainer

Interpreter Trainer is an Android 12+ application for Arabic, English and French interpreter practice.

## Current capabilities

- Branded Compose home dashboard with System / Light / Dark appearance modes
- Sight Translation practice with text-size controls, timing, notes and session saving
- Shadowing with Media3 playback, 0.75x / 1.0x / 1.25x speed, simultaneous trainee microphone recording, replay, notes and feedback
- Consecutive Interpretation with 15 / 30 / 60 second playback-position segments, replay / previous / next controls, notes and transcript fields
- Local media import plus direct network audio/video URLs; progressive media, HLS and DASH playback modules are included
- Live external-audio indicator for Bluetooth, wired headset / headphones and other external outputs
- Live Transcription with Android SpeechRecognizer and ar-MA / en-US / fr-FR selection
- Practice History with Room persistence, recordings, notes, transcripts and saved feedback
- Interpreter Coach with online Qwen3.6 27B chat and performance evaluation
- GitHub Actions debug APK build with stable development signing

## Online Interpreter AI

Interpreter Coach does not download or run a large neural model on the phone. There is no LiteRT/llama.cpp runtime and no 400–500 MB model package.

The coach uses Puter.js to access the hosted `qwen/qwen3.6-27b` model over the internet for both interpreter-training chat and performance evaluation.

Puter.js uses a user-pays architecture, so Interpreter Trainer does not embed a private AI provider key and does not require an owner-hosted backend. The first time a user accesses Interpreter AI, Puter handles authentication in a browser popup. After authentication, chat and evaluation requests are sent online.

The Android app bundles its Interpreter Coach interface as an HTML asset displayed in a WebView. A narrow JavaScript bridge can provide recent saved-practice summaries to the coach when useful. The bridge does not expose arbitrary Android APIs.

## Performance evaluation

The Evaluate tab accepts:

- practice mode
- language direction
- source transcript
- trainee / interpretation transcript
- optional source and trainee durations

Qwen3.6 27B is prompted to examine meaning transfer, omissions, additions, numbers, names, terminology, reformulation, register, fluency and delivery when the supplied evidence allows it. Cross-language evaluation is based on meaning rather than word overlap. AI feedback remains advisory and should not be treated as a certified examination result.

## Media URL scope

The URL field is for a **direct playable media or stream URL**, such as a direct MP4/MP3/M4A/WebM file, HLS playlist or DASH manifest. A normal webpage URL is not automatically a media stream and may not be playable unless the site exposes a direct compatible media endpoint.

## Privacy and connectivity

Interpreter AI requires an internet connection. There is no neural model download and no private provider API key stored in the APK.

When the user chooses to use Interpreter AI, the text they submit and relevant practice context supplied to the coach are sent to the hosted AI service so it can generate a response. Users authenticate with the AI gateway on first use.

Core practice features, saved history and the deterministic local evaluator can continue to exist independently of hosted AI availability where they are used elsewhere in the app.

## Build on GitHub

The Android build command is:

```bash
gradle --no-daemon :app:assembleDebug
```

The GitHub workflow also checks that the old on-device neural-model architecture has not returned, verifies that `qwen/qwen3.6-27b` is present in Puter's live model catalog, validates the Puter authentication flow, and verifies the stable APK signing certificate.

Open **Actions → Build Android Debug APK**, then download `interpreter-trainer-debug-apk` from a successful run's Artifacts section.

## Local development

Clone the repository normally and open it in Android Studio. The project targets API 36 with minimum SDK 31 (Android 12).

Package/application ID: `com.interpretertrainer.app`

## Ownership

Application owner: **Zouhair Elachaqi**  
Email: **zohaireachak@gmail.com**  
Phone: **0655156667**

Third-party notices are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Next quality milestones

- Automatic transcription of recorded interpreter audio before evaluation
- Better structured progress trends across saved sessions
- Per-segment Room entities for consecutive notes, transcript and score history
- Robust extractable-text PDF and DOCX import
- More Android UI/instrumentation tests around authentication, media URL errors, segment boundaries and database migrations
