# Interpreter Trainer — inline AI practice update

This preview adds compact AI text generation directly inside Simultaneous, Shadowing, Consecutive and Live Transcription. Tapping **Generate** opens a temporary sheet for the topic and length, then inserts a clean passage into the current exercise without navigating to the AI workspace or reserving extra screen space.

Inline passage generation now uses the low-latency GPT-5 Nano route with reasoning disabled, while the full Interpreter AI coach remains on Qwen3.6 27B. This avoids starting a large coaching model for a short, tightly constrained exercise passage and prevents duplicate rapid requests.

Interpreter AI now uses a polished single-chat workspace; the separate evaluation interface and request path have been removed. Streamed replies, practice transfer, voice controls and the fixed Modern Standard Arabic policy remain available. The assistant's authoritative context also includes Zouhair Elachaqi's verified developer and academic profile.

The application ID, saved practice data and stable preview signing key are retained so this APK can update recent previews. The live provider catalog is checked for both `qwen/qwen3.6-27b` and `gpt-5-nano`; Puter authentication and account quota still apply. Automated browser checks simulate the SDK and therefore do not establish live account availability.

Android 12 or newer is required. Download the APK for direct installation; an AAB is not an installable APK.
