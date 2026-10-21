# TikTok Reviewer

Android creator workspace repurposed from Interpreter Trainer. The launcher opens the new review experience; the previous training implementation remains in source for migration reference but is not part of the active navigation.

## Creator workflow

1. Select a local video (up to 200 MB and 180 seconds), or enter a script.
2. Set your niche, target audience/language, and review focus.
3. Optionally paste real viewer comments and post analytics.
4. Connect the existing Puter AI account, consent to sharing the supplied evidence, and request a review.
5. Read prioritized edits, revised hooks, a proposed outline, caption ideas, and an experiment. Copy the report or reopen it from device-local history.

## AI integration and evidence

Retains the existing Puter SDK and authentication window. Text-only reviews use the existing `qwen/qwen3.6-27b` model; sampled-image reviews use `gpt-4.1-mini`, already used elsewhere in the project, for vision input. Availability and charges depend on the user's provider account.

Video frames are sampled locally (up to eight JPEG stills). Only these stills and the supplied text are submitted after consent. Original video files and audio are not uploaded. This is sparse visual analysis, not continuous video or automatic audio transcription. Supply a transcript for speech feedback. TikTok comments, metrics and trends are not fetched automatically. The AI uses supplied feedback as context, without retraining the underlying model. No growth guarantees or fabricated performance scores.

## Validation

- `node --test .github/tests/tiktok-reviewer.cjs`
- `node --check app/src/main/assets/tiktok_reviewer.js`
- `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Android device testing must cover video selection/decoding, Puter popup sign-in, a real text review and a real image review. Local mocked tests do not verify provider availability. Existing training tests cover retained legacy source only.

Application ID is unchanged to preserve installation identity. Existing practice records are not sent to the reviewer. See PRIVACY.md for the new review data flow.
