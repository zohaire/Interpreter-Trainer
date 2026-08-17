# Interpreter Trainer

Interpreter Trainer is an Android 12+ application for Arabic, English and French interpreter practice.

## Current capabilities

- Branded Compose home dashboard with System / Light / Dark appearance modes
- Simultaneous Interpretation with a modern responsive source workspace:
  - video/audio pane with local files, direct streams, YouTube/Vimeo and ordinary webpage links
  - source text / transcript pane displayed beside the media on larger screens and stacked cleanly on phones
  - source and target language selection
  - microphone recording while source media plays
  - recording replay, interpretation transcript, notes and measurable local feedback
- Consecutive Interpretation with 15 / 30 / 60 second playback-position segments for native/direct media plus embedded web-player support for webpage links
- Local media import plus network links; progressive media, HLS and DASH playback modules are included
- Live external-audio indicator for Bluetooth, wired headset / headphones and other external outputs
- Live Transcription with Android SpeechRecognizer and ar-MA / en-US / fr-FR selection
- Practice History with Room persistence, recordings, notes, transcripts and saved feedback
- Interpreter Coach with online Qwen3.6 27B chat and performance evaluation
- GitHub Actions debug APK build with stable development signing

Sight Translation is no longer an active training mode. Existing historical session records are preserved rather than deleted.

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

## Media link support

The media-link field accepts links with or without an explicit `https://` prefix and can extract a URL pasted as part of shared text.

Direct MP4/MP3/M4A/WebM media, HLS playlists, DASH manifests and other recognized media files use the native Media3 player. YouTube and Vimeo links are converted to their embedded-player form, while ordinary webpage links open in a restricted in-app WebView instead of being incorrectly treated as raw media files.

For Simultaneous Interpretation, web-player playback and microphone recording are intentionally independent: use the controls inside the embedded page while the app records your interpretation. In Consecutive Interpretation, precise 15/30/60-second automatic seeking remains available for local/direct media; webpage players use their own controls because arbitrary websites do not expose reliable seek control to the app.

Some websites can still require sign-in, restrict embedding, or block playback in third-party WebViews. Interpreter Trainer does not bypass a website's access controls or extract protected media streams.

## Privacy and connectivity

Interpreter AI requires an internet connection. There is no neural model download and no private provider API key stored in the APK.

When the user chooses to use Interpreter AI, the text they submit and relevant practice context supplied to the coach are sent to the hosted AI service so it can generate a response. Users authenticate with the AI gateway on first use.

Core practice features, saved history and the deterministic local evaluator can continue independently of hosted AI availability where they are used elsewhere in the app.

## Build on GitHub

The Android build command is:

```bash
gradle --no-daemon :app:assembleDebug
```

The GitHub workflow also runs media-link regression tests, checks that the old on-device neural-model architecture has not returned, verifies that `qwen/qwen3.6-27b` is present in Puter's live model catalog, validates the Puter authentication flow, and verifies the stable APK signing certificate.

Open **Actions → Build Android Debug APK**, then download `interpreter-trainer-debug-apk` from a successful run's Artifacts section.

## Local development

Clone the repository normally and open it in Android Studio. The project targets API 36 with minimum SDK 31 (Android 12).

Package/application ID: `com.interpretertrainer.app`

## Ownership

Application owner: **Zouhair Elachaqi**  
Email: **zohaireachak@gmail.com**  
Phone: **+212655156667**

Third-party notices are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Next quality milestones

- Automatic transcription of recorded interpreter audio before evaluation
- Better structured progress trends across saved sessions
- Per-segment Room entities for consecutive notes, transcript and score history
- Robust extractable-text PDF and DOCX import
- More Android UI/instrumentation tests around authentication, media URL errors, segment boundaries and database migrations
