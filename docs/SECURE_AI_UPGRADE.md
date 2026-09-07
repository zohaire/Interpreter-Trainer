# Direct-access Interpreter Trainer

The app opens the training studio immediately. Email, Facebook and Firebase are removed from the Android entry point, dependencies, manifest and backend. Practice modes work without an account or network. Chat and evaluation use the existing streaming backend protocol without an Authorization header.

## Local data

A stable device-local profile owns new practice and encrypted chat history. Pre-account practice is retained when no prior account claimed it. If a previous account already claimed a database, the new local profile gets a separate database; existing account data and recordings are retained and are not exposed automatically. Clearing app data or uninstalling can remove local data. There is no account sync or sign-out screen.

## AI deployment

The app still needs an operator-run AI service. Set the repository variable INTERPRETER_BACKEND_URL to its externally reachable HTTPS origin, then build a replacement APK. Firebase and Facebook configuration are no longer needed. The backend must be updated together with the Android app: the old backend rejects requests without Firebase tokens.

Run Node 22 or later in backend/ with the provider settings from .env.example, then run npm start. PRIMARY_BASE_URL and PRIMARY_MODEL must name an actual working OpenAI-compatible service; PRIMARY_API_KEY is a server-only credential where the provider requires it. An optional secondary provider is tried only before response text starts. Self-hosted Ollama on the server can use the loopback endpoint in .env.example. No hosted provider has been selected or verified live by this change.

Terminate HTTPS at an ingress and disable response buffering for /v1/chat. The backend limits requests by TCP peer: 12 requests/minute, one concurrent generation per address, and 100 global concurrent generations per process. Caller-supplied forwarding headers cannot reset quotas. Behind a reverse proxy, clients share its quota; enforce per-client limits at the trusted ingress and use a single backend replica until a shared limiter exists. Keep provider spend limits enabled. No embedded API key or caller-chosen device ID is treated as authentication.

## Verification and delivery

Run node --test backend/test/*.test.mjs and python3 -m unittest discover -s .github/scripts -p 'test_*.py'. The browser suite exercises the actual coach assets with simulated native replies. Android instrumentation checks direct launch/recreation and encrypted storage. These tests do not prove availability of an external model.

CI compiles and tests the app without account configuration. Distribution requires a valid HTTPS backend URL; automatic publication additionally requires INTERPRETER_LIVE_RELEASE_VERIFIED=true. Verify two real AI turns and an evaluation on the deployed service, cancellation, network interruption, and the signed APK before enabling publication. Missing backend configuration is not repaired by removing login, and no live AI result is claimed until tested.
