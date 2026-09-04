# Interpreter Trainer 1.0.0 Preview

## Interpreter AI network recovery

- Recovers from WebView's `[object XMLHttpRequest]` failure by retrying through Android HTTPS with the same Puter session and Qwen model.
- Preserves streamed replies and reuses the native connection path for subsequent chat and evaluation requests.
- Reports connection, secure-connection, expired-session, usage-limit and provider errors clearly. Expired sessions offer sign-in again without discarding the message.
- Cancels stalled native streams and disconnects requests when the AI screen closes.
- Adds native HTTP tests and full-page regressions for the reported XHR failure and consecutive recovered replies.

## Interpreter AI freeze fix

- Fixes an infinite loop in Arabic language labels that could freeze the AI screen, sign-in, chat and voice controls.
- Keeps Modern Standard Arabic labels and the existing Qwen model, coaching prompts and neural voice profiles.
- Adds a regression test that loads every AI script in the Android app's exact order and checks three chat turns, conversation context, evaluation and voice controls.

Provider authentication and AI generation require an internet connection. The UI regression tests simulate provider responses; they do not certify a user's live Puter session.

This is the first production-readiness preview of the Arabic, English and French interpretation practice studio.

Highlights include simultaneous and consecutive interpretation, shadowing, live transcription, local practice history, smoother screen motion, an optional Qwen3.8 professional coach, evidence-based performance feedback and interruptible voice conversation with Studio, Warm and Broadcast neural voice profiles. The release also includes explicit AI privacy controls, safer local recording cleanup and optimized APK/AAB builds.

The Interpreter AI interface now renders directly from the HTML bundled inside the APK and is never hidden behind a WebView readiness callback or connection timer. Slow or unavailable networks are reported inside the open coach and cannot replace the interface with a false startup-timeout screen.

The APK is suitable for direct preview installation on Android 12 or newer. It is signed with the repository's public preview key so it is **not** a Google Play production package. AI feedback is advisory, and the app does not claim certified assessment or sign-language translation.
