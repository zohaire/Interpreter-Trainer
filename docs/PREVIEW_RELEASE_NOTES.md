# Interpreter Trainer 1.0.0 Preview

## Interpreter AI connection recovery

- Shows a truthful connection state and turns green only after Puter authentication succeeds.
- Starts secure sign-in directly from the user's tap so Android WebView does not block the popup.
- Keeps an unsent or failed message available for retry instead of silently discarding it.
- Recovers from stalled request startup and interrupted streams instead of loading forever.

This is the first production-readiness preview of the Arabic, English and French interpretation practice studio.

Highlights include simultaneous and consecutive interpretation, shadowing, live transcription, local practice history, smoother screen motion, an optional Qwen3.8 professional coach, evidence-based performance feedback and interruptible voice conversation with Studio, Warm and Broadcast neural voice profiles. The release also includes explicit AI privacy controls, safer local recording cleanup and optimized APK/AAB builds.

The Interpreter AI interface now renders directly from the HTML bundled inside the APK and is never hidden behind a WebView readiness callback or connection timer. Slow or unavailable networks are reported inside the open coach and cannot replace the interface with a false startup-timeout screen.

The APK is suitable for direct preview installation on Android 12 or newer. It is signed with the repository's public preview key so it is **not** a Google Play production package. AI feedback is advisory, and the app does not claim certified assessment or sign-language translation.
