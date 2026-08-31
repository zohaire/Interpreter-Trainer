# Changelog

## Deterministic Interpreter AI opening — 2026-08-31

- Removed the native readiness gate that could cover a successfully bundled coach on some Android WebView implementations.
- Rendered the complete coach HTML directly from APK memory while preserving a secure HTTPS base origin for Puter.
- Limited the full-screen recovery state to genuine local document failures; slow or unavailable AI networking is now reported inside the already-open coach.
- Added a regression guard that rejects any return of the callback-dependent alpha mask or startup timeout.

## Interpreter AI startup hotfix — 2026-08-30

- Removed the remote Puter SDK from the coach document's blocking startup path.
- Restored Android's stable local HTTPS asset loader for the bundled coach interface.
- Made the local coach visible as soon as its first frame is committed, independently of online AI availability.
- Added an eight-second startup guard with clear retry and return-to-practice actions instead of an endless spinner.
- Added a browser regression test that deliberately stalls the remote SDK while verifying that the coach still opens.

## Professional experience update — 2026-08-30

- Added restrained forward/back screen motion and single-top navigation to prevent duplicate destinations.
- Reconnected the coach screen to the complete streaming, interruption and online transcription runtime that ships with the app.
- Made coach “Use in…” actions open the selected practice mode immediately with the generated material loaded.
- Upgraded the normal coach route to Qwen3.8 27B Free and added a modern, evidence-limited interpreter coaching policy.
- Added Studio, Warm and Broadcast neural voice profiles through documented Puter/OpenAI TTS options, with Android fallback.
- Added a loading-to-content transition, app-theme synchronization and frame-batched streaming/composer updates.
- Removed the obsolete responsive WebView patching path and its Kotlin script-escaping shim.
- Expanded CI to validate the current live model, professional prompt, neural voice options, first-turn authentication and actual Android runtime wiring.

## 1.0.0 preview series — 2026-08-28

- Refined the home experience into a responsive interpretation studio.
- Added explicit first-use disclosure and controls for online AI data.
- Hardened the Interpreter AI WebView origin, navigation and cleanup behavior.
- Disabled application backup for private practice data.
- Added safe deletion and orphan cleanup for app-owned recordings.
- Added deletion confirmation for saved sessions.
- Added optimized APK/AAB builds, Android lint, wrapper validation and tagged GitHub prereleases.
- Added privacy, security, contribution and Play release documentation.
