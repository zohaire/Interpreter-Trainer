# Third-party notices

Interpreter Trainer integrates third-party hosted AI services and open-weight model families for its optional online Interpreter Coach.

## Puter.js

- Project: Puter.js / Puter
- Website and documentation: Puter Developer / Puter.js documentation
- Integration: browser JavaScript SDK loaded by the Interpreter Coach WebView

Puter.js provides the authentication and AI gateway used by Interpreter Coach. Interpreter Trainer does not claim authorship or ownership of Puter, Puter.js, or the hosted gateway.

The integration follows Puter's user-pays model: users authenticate with Puter when using hosted AI, and Interpreter Trainer does not embed a private provider API key in the APK.

## Qwen

Interpreter Coach currently requests these hosted Qwen models through Puter.js:

- `qwen/qwen3.7-flash` for general chat
- `qwen/qwen3.8-max` for deeper performance evaluation

Qwen models are developed by Alibaba's Qwen team. Interpreter Trainer does not claim authorship or ownership of the underlying foundation models. The application supplies its own interpreter-training interface, prompts, practice-session context and evaluation workflow around the hosted model service.

No Qwen model weights are bundled with or downloaded by the Android application.

Users and distributors should review the current upstream service terms, model terms and notices applicable to the hosted models they use.
