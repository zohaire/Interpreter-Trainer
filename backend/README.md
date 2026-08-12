# Interpreter Trainer AI Backend

This backend keeps the OpenAI API key out of the Android APK.

## Environment variables

- `OPENAI_API_KEY` — required, server-side only.
- `OPENAI_MODEL` — optional; defaults to `gpt-5-mini`.
- `OPENAI_TRANSCRIBE_MODEL` — optional; defaults to `gpt-4o-mini-transcribe`.
- `PORT` — optional; defaults to `8787`.

## Run locally

```bash
cd backend
npm install
OPENAI_API_KEY="your-key" npm start
```

On Windows PowerShell:

```powershell
cd backend
npm install
$env:OPENAI_API_KEY="your-key"
npm start
```

The server exposes:

- `GET /health`
- `POST /api/coach`
- `POST /api/shadowing-feedback`

## Android configuration

Open **AI Interpreter Coach** in the Android app and enter the public HTTPS URL of this backend, for example:

`https://interpreter-trainer-ai.example.com`

Do not enter an OpenAI API key in the Android app. The API key belongs only in the backend environment.

For testing on an Android emulator while the backend runs on the same computer, `http://10.0.2.2:8787` can reach the host machine. Android production builds should use HTTPS.
