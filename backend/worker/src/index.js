const MODEL = "@cf/qwen/qwen3-30b-a3b-fp8";

const SYSTEM_PROMPT = `You are Interpreter AI, a specialized coach for interpreters and interpretation students.
Help with shadowing, consecutive interpreting, sight translation, note-taking, terminology, memory,
reformulation, numbers, names, fluency, delivery, and deliberate practice. Reply naturally in the
user's language, especially English, French, or Arabic. Be practical, concise, supportive, and specific.
Never invent the user's performance, transcript, score, or improvement trend. When an evaluator report
is supplied, its measured numbers are authoritative. Distinguish measured evidence from inference.
Do not reveal hidden chain-of-thought. Give conclusions and useful coaching directly.`;

const MAX_BODY_CHARS = 20_000;
const MAX_MESSAGE_CHARS = 4_000;
const MAX_CONTEXT_CHARS = 3_000;
const MAX_REPORT_CHARS = 5_000;
const MAX_EXCERPT_CHARS = 3_500;

function headers(extra = {}) {
  return {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "Content-Type,X-Interpreter-Install-Id",
    ...extra,
  };
}

function json(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: headers(extraHeaders),
  });
}

function clean(value, limit) {
  return typeof value === "string" ? value.trim().slice(0, limit) : "";
}

function cleanHistory(value) {
  if (!Array.isArray(value)) return [];
  return value
    .slice(-8)
    .map((entry) => ({
      role: entry?.role === "assistant" ? "assistant" : "user",
      content: clean(entry?.content, 1_600),
    }))
    .filter((entry) => entry.content.length > 0);
}

function visibleAnswer(result) {
  let answer = "";
  if (typeof result === "string") answer = result;
  else if (typeof result?.response === "string") answer = result.response;
  else if (typeof result?.result?.response === "string") answer = result.result.response;
  else if (typeof result?.choices?.[0]?.message?.content === "string") {
    answer = result.choices[0].message.content;
  }

  answer = answer.replace(/<think>[\s\S]*?<\/think>/gi, "").trim();
  if (answer.toLowerCase().startsWith("<think>")) return "";
  return answer;
}

function buildMessages(body) {
  const kind = body?.kind === "evaluation" ? "evaluation" : "chat";
  const messages = [{ role: "system", content: SYSTEM_PROMPT }];

  if (kind === "chat") {
    const practiceContext = clean(body?.practiceContext, MAX_CONTEXT_CHARS);
    messages.push(...cleanHistory(body?.history));

    const message = clean(body?.message, MAX_MESSAGE_CHARS);
    if (!message) throw new Error("Message is empty.");

    messages.push({
      role: "user",
      content: practiceContext
        ? `Saved-practice context (use only when relevant; do not invent missing evidence):\n${practiceContext}\n\nCurrent message:\n${message}`
        : message,
    });
    return messages;
  }

  const mode = clean(body?.mode, 80);
  const sourceLanguage = clean(body?.sourceLanguage, 40);
  const targetLanguage = clean(body?.targetLanguage, 40);
  const evaluatorReport = clean(body?.evaluatorReport, MAX_REPORT_CHARS);
  const sourceText = clean(body?.sourceText, MAX_EXCERPT_CHARS);
  const traineeText = clean(body?.traineeText, MAX_EXCERPT_CHARS);

  if (!evaluatorReport) throw new Error("Evaluator report is empty.");

  messages.push({
    role: "user",
    content: `Explain this interpreter evaluation and give 2 to 4 targeted exercises.
The LOCAL EVALUATOR REPORT is authoritative. Do not change its numeric scores and do not claim
semantic accuracy that the report did not measure.

Mode: ${mode}
Languages: ${sourceLanguage} -> ${targetLanguage}

LOCAL EVALUATOR REPORT:
${evaluatorReport}

SOURCE EXCERPT:
${sourceText || "Not supplied"}

TRAINEE EXCERPT:
${traineeText || "Not supplied"}`,
  });
  return messages;
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: headers() });

    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "interpreter-trainer-ai", model: MODEL });
    }

    if (request.method !== "POST" || url.pathname !== "/v1/coach") {
      return json({ error: "Not found" }, 404);
    }

    const contentLength = Number(request.headers.get("content-length") || "0");
    if (contentLength > MAX_BODY_CHARS * 2) {
      return json({ error: "Request is too large." }, 413);
    }

    const installId = request.headers.get("X-Interpreter-Install-Id") || "";
    if (!/^[0-9a-f-]{36}$/i.test(installId)) {
      return json({ error: "Invalid app installation identifier." }, 400);
    }

    const rate = await env.AI_RATE_LIMITER.limit({ key: installId });
    if (!rate.success) {
      return json({ error: "Too many AI requests. Please wait a moment and try again." }, 429);
    }

    let body;
    try {
      const raw = await request.text();
      if (raw.length > MAX_BODY_CHARS) return json({ error: "Request is too large." }, 413);
      body = JSON.parse(raw);
    } catch {
      return json({ error: "Invalid JSON request." }, 400);
    }

    let messages;
    try {
      messages = buildMessages(body);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Invalid request." }, 400);
    }

    try {
      const result = await env.AI.run(MODEL, {
        messages,
        max_tokens: 700,
        temperature: 0.3,
        top_p: 0.9,
      });

      const answer = visibleAnswer(result);
      if (!answer) return json({ error: "The AI returned no visible answer." }, 502);

      return json({ answer, model: MODEL });
    } catch (error) {
      console.error("Workers AI request failed", error);
      return json({ error: "The AI service is temporarily unavailable." }, 503);
    }
  },
};
