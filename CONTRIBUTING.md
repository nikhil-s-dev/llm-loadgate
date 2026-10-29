# Contributing

Thanks for taking the time to improve llm-loadgate.

## Development setup

You need JDK 21 or newer. The Maven wrapper downloads Maven on first use.

```bash
./mvnw verify
java -jar target/llm-loadgate.jar mock-server &
java -jar target/llm-loadgate.jar run --base-url http://127.0.0.1:8000/v1 --num-requests 50
```

## Guidelines

- Keep the jar free of runtime dependencies. Test-only dependencies are fine.
- Add or update a test for every behavior change. Tests that need a server should use `MockLlmServer` on port 0.
- Measurement changes (what a timestamp means, how tokens are counted) need a note in the README's "What it measures" section.
- Keep pull requests focused; one change per pull request is easiest to review.

## Reporting a measurement problem

Include the exact command, the server and version you tested (for example vLLM 0.x with the model name), and the JSON report from `--json-out --save-requests`.
