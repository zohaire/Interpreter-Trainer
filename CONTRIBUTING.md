# Contributing

Contributions should preserve Interpreter Trainer's Arabic, English and French scope, accessibility, user-data protections and online-only AI architecture.

1. Create a focused branch and keep unrelated changes separate.
2. Do not commit credentials, private signing keys, user recordings or practice data.
3. Run `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
4. For coach changes, also run syntax checks on assets under `app/src/main/assets` and preserve the browser-level AI workflow.
5. Explain user-visible, privacy, database and release effects in the pull request.

New training modes must accurately describe their capabilities. Experimental demonstrations must not be labeled as certified translation, accessibility or assessment tools.
