# TikTok Reviewer

Android creator workspace with a soft blush interface, coral-to-orange actions, brief expandable feedback cards, and a cyan/pink short-video launcher icon. Repurposed from Interpreter Trainer. The launcher opens the new review experience; the previous training implementation remains in source for migration reference but is not part of the active navigation.

## Creator workflow

1. Optionally answer three short creator-profile questions, or skip setup.
2. Select a local video (up to 200 MB and 180 seconds), or enter a script.
3. Optionally set your niche, target audience/language, and review focus.
4. Optionally paste real viewer comments and post analytics.
5. Connect the existing Puter AI account, consent to sharing the supplied evidence, and request a review.
6. Read one observed strength and one suggested improvement. Open category cards for detail, try a rewritten hook, or copy the feedback.
7. Generate one complete post idea or a seven-day plan from your topic and optional profile. Bookmark favorites in the Saved shelf; history keeps your latest 20 results.

## AI integration and evidence

Retains the existing Puter SDK and authentication window. Text-only reviews use the existing `qwen/qwen3.6-27b` model; sampled-image reviews use `gpt-4.1-mini`, already used elsewhere in the project, for vision input. Availability and charges depend on the user's provider account.

Video frames are sampled locally (up to eight JPEG stills). Only these stills and the supplied text are submitted after consent. Original video files and audio are not uploaded. This is sparse visual analysis, not continuous video or automatic audio transcription. Supply a transcript for speech feedback. The Profiles tab retrieves available public account counts and recent captions/view counts. Comments, private analytics and trends are not fetched. The AI uses supplied feedback as context, without retraining the underlying model. No growth guarantees or fabricated performance scores.

## Validation

- `node --test .github/tests/tiktok-reviewer.cjs` (requires Playwright + Chromium; CI installs both)
- `node --check app/src/main/assets/tiktok_reviewer.js`
- `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Android device testing must cover video selection/decoding, Puter popup sign-in, a real text review and a real image review. Local mocked tests do not verify provider availability. Existing training tests cover retained legacy source only. The creator browser tests cover skippable setup, consent and optional context, text/vision routing, hidden-video isolation, provider failures, ideas/plans, bookmarks/deletion, safe rendering and mobile overflow. The AI is mocked in browser tests.

Public Grow Now store screenshots informed the blush palette, card hierarchy and expandable feedback pattern: https://play.google.com/store/apps/details?id=com.escape.grownow . Onboarding questions and visual assets are original. Profiles supports public-embed lookup by username without an API key. It returns available account counts, captions and views, not private analytics or predictive metrics.

Application ID is unchanged to preserve installation identity. Existing practice records are not sent to the reviewer. See PRIVACY.md for the new review data flow.

## Review chat and video creation

Ask follow-up questions beneath any review. Conversations stay with their review in local history and can be cleared. The AI sees that feedback and the conversation, not the original video.

The Videos tab requests a 15-second Seedance 2.0 clip through the existing Puter sign-in. Choose vertical or landscape, 1080p or 720p, and generated sound. Prompt sharing and AI credit use require consent. Clips are saved to Puter storage; preview them and use Save to phone for Android Downloads. Generation is not unlimited free. The provider can shorten clips for insufficient balance; actual duration and dimensions are checked from returned metadata and discrepancies are displayed. Keep the app open during generation. No automatic retries or model fallback that could spend additional credits.

Integration reference: https://docs.puter.com/AI/txt2vid/ . Live paid generation and physical-device downloading require signed-in/device verification.
