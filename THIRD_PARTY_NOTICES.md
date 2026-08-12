# Third-party notices

Interpreter Trainer includes integration with open-source / open-weight AI components for on-device neural chat.

## Qwen2.5-0.5B-Instruct-GGUF

- Project/model: Qwen2.5-0.5B-Instruct-GGUF by the Qwen team
- Repository: `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
- Quantization used by the app: `qwen2.5-0.5b-instruct-q4_0.gguf`
- License: Apache License 2.0

The model weights are downloaded separately by the user from the upstream model repository and stored in app-private Android storage. Interpreter Trainer does not claim authorship or ownership of the Qwen foundation model. The application supplies its own interpreter-training interface, deterministic evaluator, prompts, saved-session context and coaching workflow around the model.

## llama.cpp

- Project: `ggml-org/llama.cpp`
- License: MIT
- Integration: official Android/JNI inference implementation, pinned in this repository as a git submodule

`llama.cpp` runs the GGUF language model directly on compatible Android devices. The app uses its Android inference engine to load the locally stored model, apply the Interpreter AI system prompt and stream generated tokens.

Users and distributors should review and preserve applicable upstream licenses/notices when redistributing runtime or model components.
