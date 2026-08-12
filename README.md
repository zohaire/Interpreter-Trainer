# Interpreter Trainer

Interpreter Trainer is an Android 12+ application for Arabic, English and French interpreter practice.

## Current baseline

- Home dashboard
- Sight Translation: pasted/TXT text, text-size control, timer and session saving
- Shadowing: Media3/ExoPlayer audio/video playback, standard player controls, 0.75x / 1.0x / 1.25x speeds, notes
- Consecutive Interpretation: 15 / 30 / 60 second media segments with replay, previous, next, notes and transcript fields
- Live Transcription: Android SpeechRecognizer, partial/final results, controlled automatic restart, microphone permission handling, ar-MA / en-US / fr-FR selector
- Practice History: Room persistence and session review
- Storage Access Framework for user-selected content
- GitHub Actions debug APK build

## Important scope notes

This repository is the production foundation, not a claim that every requested module is finished. TXT extraction is implemented. PDF text extraction and DOCX text extraction are intentionally left for the dedicated document-processing layer; scanned PDFs require a future OCR module. Voice recording files, per-segment note entities, Android 14+ recognition language switching, and richer media persistence are also subsequent milestones.

## Build on GitHub

1. Create a new GitHub repository.
2. Upload the contents of this folder to the repository root.
3. Commit/push to `main`.
4. Open **Actions** → **Build Android Debug APK**.
5. Run the workflow manually, or let it run after a push.
6. Open the completed workflow run and download `interpreter-trainer-debug-apk` from **Artifacts**.

The workflow uses Java 17 and Gradle 8.13, then runs:

```bash
gradle --no-daemon :app:assembleDebug
```

## Local development

Open the repository in Android Studio. The project targets API 36 and has a minimum SDK of 31 (Android 12).

Package/application ID: `com.interpretertrainer.app`

## Next recommended milestones

1. Verify the first GitHub APK build and correct any dependency/toolchain issue surfaced by CI.
2. Add robust PDF extractable-text and DOCX import.
3. Add voice recording and saved recording URIs.
4. Move consecutive notes/transcripts to per-segment Room entities.
5. Integrate SpeechRecognizer directly into Shadowing and Consecutive screens.
6. Add source transcript support and Android 14+ language detection/switching when the recognition engine supports it.
7. Add tests for segment boundary behavior and database persistence.
