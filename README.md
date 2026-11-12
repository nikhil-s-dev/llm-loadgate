# llm-loadgate

**A Java load-test client for streaming LLM APIs.** Send requests at fixed concurrency or at a target arrival rate, measure latency from the client side, and exit non-zero if a configured service-level objective (SLO) fails.

Java 21+ · OpenAI-compatible streaming endpoints · Apache-2.0  
Maintainer: **Nikhil S**

## Why I built it

A model endpoint can return successful responses and still become slow enough to be unusable. Mean latency alone can miss queuing delays, especially near the point where incoming traffic exceeds serving capacity.

`llm-loadgate` focuses on streaming inference: how long a user waits for the first token, how quickly the rest arrive, and how those numbers change under load.

## Run a local test

Requires JDK 21 or newer. The built-in server speaks a mock streaming API. This checks the client, reporting, and gates **without needing a GPU**.

```bash
git clone https://github.com/nikhil-s-dev/llm-loadgate.git
cd llm-loadgate
./mvnw verify
java -jar target/llm-loadgate.jar mock-server --port 8000
```

In a second terminal:

```bash
java -jar target/llm-loadgate.jar run \
  --base-url http://127.0.0.1:8000/v1 \
  --concurrency 4 --num-requests 30 --warmup 5 \
  --max-tokens 64 \
  --slo "ttft_p95<1s" --slo "error_rate<1%" \
  --json-out target/mock-load.json
```

Exit status: **0** when SLOs pass, **1** for failed objectives or unsuccessful requests, **2** for invalid arguments. The mock server deliberately simulates latency; its numbers are **not GPU inference benchmarks**.

## Run against a model server

Point `--base-url` at an accessible OpenAI-compatible API (for example a vLLM server). Choose an explicit model if the endpoint serves more than one.

```bash
java -jar target/llm-loadgate.jar run \
  --base-url http://localhost:8000/v1 \
  --model YOUR_MODEL_NAME \
  --rate 4 --num-requests 300 --warmup 20 \
  --synthetic-input-tokens 512 --max-tokens 256 \
  --slo "ttft_p95<800ms" \
  --slo "tpot_p95<60ms" \
  --slo "error_rate<1%" \
  --json-out target/inference-run.json
```

Use appropriate thresholds for the actual model, hardware, and workload; these are **example targets**, not validated performance expectations. `--api-key` / `LLM_API_KEY` and `--header` handle authenticated endpoints. Compatibility depends on the server's streaming API behavior.

## What gets measured

| Metric | Measurement |
| --- | --- |
| TTFT | Request start to first non-empty streamed content or reasoning chunk |
| TPOT | (End-to-end time − TTFT) / (output tokens − 1) |
| ITL | Time between streamed chunks (not necessarily individual tokens) |
| E2E | Request start until the response stream completes |
| Goodput | Requests per second that finish successfully and meet the configured per-request targets |
| Error rate | Failed requests divided by measured requests |

The client prefers server-reported output-token counts. If the server omits usage information, it falls back to counting content chunks; reports should be interpreted with that limitation in mind.

**Closed-loop** mode (`--concurrency`) sends another request when a client finishes. **Open-loop** mode (`--rate`) schedules arrivals independently of response time, exposing queues and tail latency when the service saturates. `--max-concurrency` bounds simultaneous requests in open-loop mode.

## Engineering tradeoffs

- Uses Java virtual threads and a streaming HTTP client; no runtime third-party dependencies.
- Supports SLO checks, JSON/Markdown summaries, and GitHub job summaries.
- A latency percentile can look healthy while errors increase, so track `error_rate` alongside latency.
- Prefix caching, server batching, prompt lengths, token-count reporting, and network position all affect results. Keep them fixed when comparing model or serving changes.
- Client-observed ITL is measured at chunk boundaries. Do not interpret it as exact per-token scheduling time.

The [CI workflow](.github/workflows/ci.yml) builds the project and exercises a latency gate against the mock server. The [Kubernetes Job example](examples/kubernetes/perf-gate-job.yaml) shows a deployment-stage check. Check [Actions](https://github.com/nikhil-s-dev/llm-loadgate/actions) for run status.

## Limits of the evidence

The repository does not include an independently verified benchmark against a named GPU, model, and vLLM/Triton release. Don't cite mock-server throughput or illustrative values as production results.

For a real comparison, save the JSON report and record the git commit, model and weights, server version, GPU, quantization, prompt/output length distributions, offered load, and whether caching was enabled. Run multiple trials before claiming an improvement.

## Development

```bash
./mvnw verify
```

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and [LICENSE](LICENSE).
