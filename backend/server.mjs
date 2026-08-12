import express from "express";
import multer from "multer";

const app = express();
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 25 * 1024 * 1024 }
});

app.use(express.json({ limit: "1mb" }));

const PORT = Number(process.env.PORT || 8787);
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "";
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-5-mini";
const TRANSCRIBE_MODEL = process.env.OPENAI_TRANSCRIBE_MODEL || "gpt-4o-mini-transcribe";

function requireApiKey() {
  if (!OPENAI_API_KEY) {
    const error = new Error("OPENAI_API_KEY is not configured on the server.");
    error.status = 503;
    throw error;
  }
}

async function openAiJson(path, init) {
  requireApiKey();
  const response = await fetch(`https://api.openai.com${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${OPENAI_API_KEY}`,
      ...(init.headers || {})
    }
  });

  const text = await response.text();
  let json = {};
  try {
    json = text ? JSON.parse(text) : {};
  } catch {
    json = { error: { message: text || `OpenAI returned HTTP ${response.status}` } };
  }

  if (!response.ok) {
    const message = json?.error?.message || `OpenAI returned HTTP ${response.status}`;
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return json;
}

function extractResponseText(json) {
  if (typeof json.output_text === "string" && json.output_text.trim()) {
    return json.output_text.trim();
  }

  const chunks = [];
  for (const item of json.output || []) {
    for (const content of item.content || []) {
      if (typeof content.text === "string") chunks.push(content.text);
    }
  }
  return chunks.join("\n").trim();
}

async function coachResponse(prompt) {
  const json = await openAiJson("/v1/responses", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: OPENAI_MODEL,
      store: false,
      input: prompt
    })
  });
  const text = extractResponseText(json);
  if (!text) throw new Error("The AI returned an empty response.");
  return text;
}

function languageCode(tag) {
  const normalized = String(tag || "").toLowerCase();
  if (normalized.startsWith("ar")) return "ar";
  if (normalized.startsWith("fr")) return "fr";
  if (normalized.startsWith("en")) return "en";
  return "";
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, aiConfigured: Boolean(OPENAI_API_KEY) });
});

app.post("/api/coach", async (req, res, next) => {
  try {
    const message = String(req.body?.message || "").trim();
    const language = String(req.body?.language || "en-US").trim();
    const context = String(req.body?.context || "").trim();

    if (!message) return res.status(400).json({ error: "message is required" });

    const prompt = `You are the AI Interpreter Coach inside Interpreter Trainer, a serious training application for interpreters and interpretation students.\n\nRespond in the language requested by the user when practical. Be precise, constructive and concise. Focus on interpreting skills such as accuracy, completeness, omissions, additions, terminology, register, reformulation, memory, note-taking, shadowing, consecutive interpretation and sight translation. Never invent evidence about a performance that was not provided.\n\nPreferred response language tag: ${language}\n${context ? `Practice context:\n${context}\n\n` : ""}User question:\n${message}`;

    const reply = await coachResponse(prompt);
    res.json({ reply });
  } catch (error) {
    next(error);
  }
});

app.post("/api/shadowing-feedback", upload.single("recording"), async (req, res, next) => {
  try {
    requireApiKey();
    if (!req.file) return res.status(400).json({ error: "recording is required" });

    const language = String(req.body?.language || "en-US");
    const sourceName = String(req.body?.sourceName || "");
    const notes = String(req.body?.notes || "");
    const speed = String(req.body?.speed || "1.0");

    const form = new FormData();
    const blob = new Blob([req.file.buffer], { type: req.file.mimetype || "audio/mp4" });
    form.append("file", blob, req.file.originalname || "shadowing.m4a");
    form.append("model", TRANSCRIBE_MODEL);
    const code = languageCode(language);
    if (code) form.append("language", code);

    const transcription = await openAiJson("/v1/audio/transcriptions", {
      method: "POST",
      body: form
    });
    const transcript = String(transcription.text || "").trim();

    const prompt = `You are evaluating a shadowing practice session for an interpreter trainee.\n\nImportant limitations: you are receiving the trainee recording transcript, not the original source transcript, so do not claim that you verified exact source fidelity or pronunciation acoustics. Evaluate only what can reasonably be inferred from the transcript and practice metadata.\n\nReturn practical feedback with these headings:\nScore: NN/100\nStrengths\nIssues to work on\nSpecific practice advice\nNext exercise\n\nLanguage: ${language}\nPlayback speed: ${speed}x\nSource file: ${sourceName || "unknown"}\nTrainee notes: ${notes || "none"}\n\nTrainee shadowing transcript:\n${transcript || "No reliable transcript was produced."}`;

    const feedback = await coachResponse(prompt);
    const match = feedback.match(/Score\s*:\s*(\d{1,3})\s*\/\s*100/i);
    const score = match ? Math.max(0, Math.min(100, Number(match[1]))) : null;

    res.json({ transcript, feedback, score });
  } catch (error) {
    next(error);
  }
});

app.use((error, _req, res, _next) => {
  console.error(error);
  const status = Number(error.status || 500);
  res.status(status).json({ error: error.message || "Unexpected server error" });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Interpreter Trainer AI backend listening on port ${PORT}`);
});
