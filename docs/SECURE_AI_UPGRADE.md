# Secure streaming upgrade — integration candidate

This branch is not a verified production release. No live provider, Firebase project, Meta application or deployment credentials were available in the development environment. Do not replace the installed working build before the live gates below pass.

## Findings

Baseline: main 65aa946 (classic coach rollback). The previous LIVE6 implementation and native Puter transport remained as unused files. The actual active screen called Puter from a WebView, with sign-in before the request lock, no generation deadline, no user cancellation, raw exception text and premature Ready status. Interrupted partial replies were removed. The earlier failing LIVE6 HTTP transaction is unavailable, so its exact remote quota/model/account cause is not proven.

## Architecture

Android Firebase Auth → native HTTPS bridge with ID token → Node backend Firebase Admin verification → provider router → primary/secondary OpenAI-compatible streaming providers. Includes self-hosted Ollama support on backend loopback. Provider and model identifiers are environment configuration, never Android constants. No provider has been selected or verified live. No user API keys or separate provider login are required. The app owner must fund hosting/provider usage if a free allowance is exhausted; open-source weights do not make hosted inference unlimited or costless.

SSE from a provider becomes bounded NDJSON to Android. Backend limits: 75-second overall generation, 20-second upstream idle, at most two provider attempts, fallback only before text, 12 requests/minute/user, one active request/user, 100 concurrent requests/process, bounded context and output. Android adds connect/read/overall deadlines and explicit cancellation. No automatic replay of partial replies. Diagnostic logs contain categories, provider labels, request IDs, status and latency only; production diagnostic logs are disabled.

The limiter is process-local. Deploy one replica initially, with an ingress request/body limit. Multi-replica deployment requires a shared limiter and account spend budgets. No production claim is made for unlimited free usage or arbitrary scale.

## Configure Android

Build environment variables (public app configuration, not service secrets; CI reads GitHub repository variables with these names):

- INTERPRETER_BACKEND_URL: externally accessible HTTPS backend origin, no trailing route
- FIREBASE_ANDROID_API_KEY, FIREBASE_ANDROID_APP_ID, FIREBASE_PROJECT_ID: registered Android application configuration
- FACEBOOK_APP_ID and FACEBOOK_CLIENT_TOKEN: public Meta Android SDK identifiers; never use the Meta App Secret here

Firebase Android API keys identify the project; they are not AI provider keys or administrative service credentials. Restrict them to the intended APIs/application according to Firebase guidance. Missing configuration displays a configuration error and cannot bypass login.

Enable Firebase email/password and Facebook providers. Set a password policy and email templates. Register Android package com.interpretertrainer.app and signing fingerprints. Configure Meta's Android package, activity, release/debug key hashes and the Firebase OAuth redirect URI. Store the Meta App Secret in Firebase's provider configuration only. Switch the Meta app to the appropriate live mode and complete any required review for public users.

Facebook uses the official Android LoginManager callback and exchanges its access token for a Firebase credential. The app never asks for Facebook passwords. Email accounts require verification, can request verification again, sign in/out and request Firebase-hosted password reset links. Firebase restores sessions; offline practice remains available for previously verified accounts. Live OAuth and email delivery still need verification.

## User data and security

Firebase holds UID, display name, email and optional provider photo. Practice data/progress uses separate Room databases per account; the first verified account on an existing installation owns the original practice database. Later accounts never read it. Existing recordings are preserved; startup global pruning was removed because pruning against one account could delete another's recordings. The first verified account can migrate the classic V4 browser conversation into encrypted storage on first opening the coach. The legacy entry is removed only after the encrypted write succeeds; later accounts cannot import it.

New conversations are stored per UID, encrypted with AES-GCM using Android Keystore. Passwords are not saved to instance state. Firebase session tokens remain in the native SDK and are never exposed to JavaScript. The bundled coach blocks network resource loads, file/content access, popups and third-party cookies. Provider credentials only exist in backend secrets. Backup remains disabled and HTTPS remains enforced. Voice input/output and practice transfers remain native. Android installed voices replace the Puter paid speech dependency.

## Run backend

Node 22 or later. In backend/, run npm install and npm start with the environment from .env.example. The server uses Firebase Admin application-default credentials with revoked-token checks. Terminate HTTPS at a trusted ingress; disable proxy response buffering for /v1/chat. Never expose an unauthenticated self-hosted model publicly. Set provider model IDs to ones actually installed/available on your account.

Verification: node --test backend/test/*.test.mjs. The server tests inject authentication/provider fixtures; they are not live account/provider tests. Browser test: node .github/tests/interpreter-backend-runtime.cjs with Playwright and Chromium installed. CI runs these before Android unit tests/lint/debug/release builds.

## Release gates still required

1. CI clean debug/release builds, lint and existing unit tests.
2. Device/emulator launch, sign-up/email delivery, verification, sign-in, session restoration, logout, reset and official Facebook OAuth with the production signing identity.
3. Two real AI turns and evaluation through the deployed backend; record first-token latency, provider/model, request ID and failure categories without credentials or prompt content.
4. Cancel, rotate, navigate away, switch accounts and interrupt networking; verify history isolation and no stuck UI.
5. Force primary failure and prove fallback with configured providers; force midstream failure and verify partial reply is retained and never replayed.
6. Scan the resulting APK for server-only credentials; public Firebase/Meta application identifiers are expected.
7. Release signing, provider terms, hosting capacity and operational budgets. Only then set the repository variable INTERPRETER_LIVE_RELEASE_VERIFIED=true to allow the existing automatic preview publication job. It defaults to disabled for this upgrade.

Local attempt to run clean/testDebugUnitTest/assembleDebug/assembleRelease stopped before compilation because the Gradle distribution host was unreachable. Local browser syntax checks passed; Chromium was absent. CI subsequently passed the actual coach browser suite with simulated native/auth/provider boundaries. Backend and client protocol tests are executable without external services.

Official integration references:
- https://firebase.google.com/docs/auth/android/facebook-login
- https://firebase.google.com/docs/auth/android/password-auth
- https://firebase.google.com/docs/auth/admin/verify-id-tokens
- https://docs.ollama.com/api/openai-compatibility

## Disabled login screen incident

An unconfigured CI APK was installable but could not accept login input. That was a configuration-blocked app, not a working authentication test. Distribution now fails before packaging or uploading APKs when required Firebase, Facebook or backend variables are missing. Developer emulator tests run independently and upload reports only. This presence check cannot prove service activation; real login and inference still require the live release gates. The already installed APK must be replaced after configuration; waiting or changing device settings cannot populate its compiled settings.
