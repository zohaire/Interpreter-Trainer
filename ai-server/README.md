# Interpreter Trainer — self-hosted AI

This folder runs the enhanced Interpreter Coach with an open-weight model on infrastructure controlled by the app owner.

## Selected model

- Model: `Qwen/Qwen2.5-1.5B-Instruct-GGUF`
- Suggested quantization: `Q4_K_M`
- Model license: Apache-2.0
- Runtime: `llama.cpp` / `llama-server` (MIT licensed)

The Android app does not require an OpenAI, Claude, Gemini or other commercial AI API key. Its deterministic local evaluator and rule-based coach continue to work when this server is unavailable.

## Quick start

Install Docker, then from this directory run:

```bash
docker compose up -d
```

The first start downloads the selected GGUF model into the Docker cache. The server exposes an OpenAI-compatible chat endpoint at:

```text
http://YOUR_SERVER_IP:8080/v1/chat/completions
```

In a debug build of Interpreter Trainer, open **Interpreter Coach → AI setup** and save:

```text
http://YOUR_SERVER_IP:8080
```

For a production deployment, put `llama-server` behind HTTPS and an authenticated reverse proxy, then configure that HTTPS base URL in the app. Do not expose an unauthenticated inference server directly to the public internet.

## Architecture

The local Android evaluator remains authoritative for numeric performance scores. The neural model is used for natural conversation, explanations and personalized practice suggestions. This prevents a generative model from silently inventing or changing measured scores.

## Hardware

This 1.5B model is intentionally modest so it can be hosted more cheaply than a large model. Actual speed depends on the server CPU/GPU and number of simultaneous users. If usage grows, the server can later move to a larger Qwen model without redesigning the Android chat interface because the app talks to the standard llama.cpp chat-completions endpoint.
