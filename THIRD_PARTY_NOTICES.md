# Third-party notices

Interpreter Trainer can optionally use a self-hosted open-source AI stack.

## Qwen2.5-1.5B-Instruct-GGUF

- Project/model: Qwen2.5-1.5B-Instruct-GGUF by the Qwen team
- Repository: `Qwen/Qwen2.5-1.5B-Instruct-GGUF`
- License: Apache License 2.0

Interpreter Trainer does not claim authorship or ownership of the Qwen foundation model. The application supplies its own interpreter-training interface, evaluator, prompts, session context and coaching workflow around that model.

## llama.cpp

- Project: `ggml-org/llama.cpp`
- License: MIT

`llama.cpp` is used as the optional self-hosted inference runtime and exposes the chat-completions interface consumed by the Android application.

Users and distributors should review the upstream license texts and notices when redistributing model/runtime components.
