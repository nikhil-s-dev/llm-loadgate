# llm-loadgate

Created and maintained by Nikhil Sunku.

Load-test LLM API endpoints (vLLM, NVIDIA Triton, TGI, Ollama) and fail the build when latency SLOs regress.

![Java 21+](https://img.shields.io/badge/java-21%2B-blue)
![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-green)

Most LLM serving regressions show up as latency, not errors. A new quantization, a larger batch size or a changed scheduler setting can push time to first token from 300 ms to 2 s while every health check stays green. `llm-loadgate` turns latency targets into a CI check: it streams real requests at a fixed concurrency or a Poisson arrival rate, measures TTFT, time per output token, inter-token latency and end-to-end latency, and exits non-zero when an SLO is breached.

- Single jar built for Java 21: virtual threads and streaming `java.net.http`
- Closed-loop (fixed concurrency) and open-loop (Poisson arrivals) load models
- SLO gates with units: `ttft_p95<500ms`, `tpot_p99<50ms`, `error_rate<1%`
- Goodput: the share of requests that met every per-request latency target
- Reports for terminals, GitHub job summaries (Markdown) and pipelines (JSON)
- Built-in mock LLM server, so you can try it and test it in CI without a GPU

> **Validation:** GitHub Actions builds and tests on Java 21 and 25 and runs a latency gate against the mock server. See [CI runs](https://github.com/nikhilsunku/llm-loadgate/actions/workflows/ci.yml) for results. Sample outputs are illustrative unless reproduced and archived.

## Quick start

Requires Java 21 or later.

```bash
git clone https://github.com/nikhilsunku/llm-loadgate.git
cd llm-loadgate
./mvnw -q package                      # builds target/llm-loadgate.jar

# terminal 1: fake LLM server, 150 ms to first token, 20 ms per token
java -jar target/llm-loadgate.jar mock-server

# terminal 2: 400 requests from 16 concurrent users, checked against SLOs
java -jar target/llm-loadgate.jar run \
  --base-url http://127.0.0.1:8000/v1 \
  --concurrency 16 --num-requests 400 --warmup 32 --max-tokens 64 \
  --slo "ttft_p95<300ms" --slo "tpot_p95<40ms" --slo "error_rate<1%" \
  --goodput "ttft<200ms,tpot<25ms"
```

Example report format (sample values; rerun the commands above to obtain measured results on your machine):

```text
llm-loadgate 0.1.0
target     http://127.0.0.1:8000/v1/chat/completions (model mock-llm)
load       closed loop, concurrency 16; 400 requests, max_tokens 64
prompts    built-in prompts (8)

requests   400 sent, 400 ok, 0 failed (0.00%) in 36.06 s
throughput 11.09 req/s, 709.8 output tokens/s (token counts from server usage)

latency (ms)        mean       p50       p90       p95       p99       max
TTFT               153.9     153.9     165.9     167.1     173.3     178.1
TPOT                20.3      20.3      20.5      20.6      21.5      21.6
ITL                 20.3      20.2      21.9      22.1      23.2      74.6
E2E               1433.7    1431.3    1452.8    1464.4    1505.9    1520.0

goodput    100.00% of requests met ttft<200ms,tpot<25ms (11.09 req/s)

SLOs
  PASS  ttft_p95<300ms             167.1 ms
  PASS  tpot_p95<40ms              20.6 ms
  PASS  error_rate<1%              0.00%

result     PASS
```

The mock server is useful for validating report generation and CI wiring. Its latency is not evidence of performance against a production model server.

## Pointing it at a real server

```bash
# vLLM
vllm serve meta-llama/Llama-3.1-8B-Instruct --port 8000

java -jar target/llm-loadgate.jar run \
  --base-url http://localhost:8000/v1 \
  --rate 4 --num-requests 300 --warmup 20 \
  --synthetic-input-tokens 512 --max-tokens 256 \
  --extra-body '{"ignore_eos": true}' \
  --slo "ttft_p95<800ms" --slo "tpot_p95<60ms" --slo "error_rate<1%"
```

- `--model` is optional: the first model from `GET /v1/models` is used, which suits single-model deployments.
- `--extra-body '{"ignore_eos": true}'` makes vLLM generate exactly `--max-tokens` tokens, so runs are comparable across model versions. Any JSON object you pass is merged into each request.
- The same command works for TGI (`--base-url http://localhost:8080/v1 --model tgi`), Ollama (`--base-url http://localhost:11434/v1 --model llama3.2`), Triton's compatible LLM API frontend, or a gateway in front of any of them. `--api-key` (or `$LLM_API_KEY`) and `--header` handle auth.
- Use `--endpoint completions` for base models served on `/v1/completions`.

## What it measures

| Metric | Definition | Why it matters |
|:--|:--|:--|
| TTFT | send until the first non-empty content (or reasoning) chunk | Perceived responsiveness; dominated by queueing and prefill |
| TPOT | (E2E - TTFT) / (output tokens - 1) | Decode speed each user sees |
| ITL | gap between consecutive streamed chunks | Stalls from batching or preemption show up in p99 and max |
| E2E | send until the stream ends | Time to a complete answer |
| Goodput | share (and rate) of requests that succeeded and met every `--goodput` target | What users experienced, which averages hide |

Measurement details:

- Output tokens come from the server's `usage` block (requested with `stream_options.include_usage`). If the server does not report usage, content chunks are counted instead, and the report says which source was used.
- ITL is measured per streamed chunk. A server that packs several tokens into one chunk shows fewer, longer gaps; TPOT is unaffected because it uses token counts.
- Latency percentiles include successful requests only. Failures are counted in `error_rate` and grouped by message in the report.
- A request counts as failed if the HTTP status is not 200, the stream carries an error object, it ends without `[DONE]`, it produced no tokens, or it exceeds `--timeout` (which covers the whole stream, not just the response headers).
- Reused prompts can hit the server's prefix cache and make TTFT look better than production. `--synthetic-input-tokens N` generates a unique prompt per request; a `--prompts` file should have at least `--num-requests` entries.

## Closed loop or open loop

`--concurrency N` simulates N users who each send their next request when the previous answer finishes. Latency stays bounded because the clients slow down when the server does.

`--rate R` sends requests as a Poisson process at R per second regardless of how the server is doing, which is how independent users behave. This is the mode that finds the knee where queueing starts. The following is an illustrative sweep, not a benchmark verified in this review, against the mock server limited to 16 concurrent generations (`mock-server --ttft-ms 120 --itl-ms 15 --max-concurrency 16`, about 15 requests per second of capacity at 64 output tokens), 150 requests per rate:

| Offered rate (req/s) | TTFT p50 (ms) | TTFT p95 (ms) | E2E p95 (ms) | Goodput (TTFT < 500 ms) |
|--:|--:|--:|--:|--:|
| 4 | 125 | 134 | 1106 | 100% |
| 8 | 123 | 134 | 1103 | 100% |
| 12 | 124 | 148 | 1114 | 100% |
| 14 | 127 | 365 | 1327 | 99% |
| 16 | 134 | 795 | 1756 | 75% |
| 18 | 247 | 1148 | 2106 | 69% |

TTFT is flat until the offered rate nears capacity, then p95 climbs several times over while p50 barely moves. A fixed-concurrency test at 16 users would never show this.

## SLOs and exit codes

An SLO is `<metric><op><value>[unit]`, for example `ttft_p95<500ms`, `tpot_p99<=0.05s`, `error_rate<1%`, `output_tokens_per_s>=200` or `goodput_ratio>=0.95`.

- Latency metrics: `ttft`, `tpot`, `itl`, `e2e`, each with `_mean`, `_p50`, `_p90`, `_p95`, `_p99` or `_max`. They require a unit (`ms` or `s`); `ttft_p95<500` is rejected rather than read as 500 seconds.
- Ratios: `error_rate`, `goodput_ratio`, as a percentage or a fraction.
- Rates: `requests_per_s`, `output_tokens_per_s`, `prompt_tokens_per_s`, `goodput_requests_per_s`.

| Exit code | Meaning |
|:--|:--|
| 0 | every SLO held |
| 1 | an SLO was breached, no request succeeded, or the target was unreachable |
| 2 | invalid arguments |

## Running it in CI and Kubernetes

**GitHub Actions.** This repository's [CI workflow](.github/workflows/ci.yml) starts the mock server and runs the gate on every push. When `GITHUB_STEP_SUMMARY` is set, the Markdown report is appended to the job summary, and `--json-out` keeps the full report as an artifact. [examples/github-actions/staging-latency-gate.yml](examples/github-actions/staging-latency-gate.yml) shows how another repository can gate a release on its staging model server.

**Kubernetes.** [examples/kubernetes/perf-gate-job.yaml](examples/kubernetes/perf-gate-job.yaml) runs the container image as a Job next to a vLLM Service. With Argo CD it runs as a PostSync hook, so a breached SLO fails the sync.

**Docker.**

```bash
docker build -t llm-loadgate .
docker run --rm --network host llm-loadgate run --base-url http://localhost:8000/v1 --slo "ttft_p95<500ms"
```

Tagging `v*` runs the [release workflow](.github/workflows/release.yml), which attaches the jar to a GitHub release and pushes the image to `ghcr.io/nikhilsunku/llm-loadgate`.

## Design notes

- **Virtual threads.** Each in-flight request runs on its own virtual thread, so the load generator is plain blocking-style code with no pool sizing, and thousands of open streams cost little.
- **Timing at the socket.** Requests go through `HttpClient.sendAsync` with a line subscriber, so every chunk is timestamped when the client reads it rather than when a buffered reader hands it over. The same subscription is cancelled on `--timeout`; the JDK's per-request timeout only covers the response headers, which would let a stalled stream hang a CI job.
- **HTTP/1.1 by default.** vLLM (uvicorn) and TGI speak HTTP/1.1, and multiplexing many streams over one HTTP/2 connection would not match how real clients connect. `--http2` is available for gateways that support it.
- **No dependencies.** JSON, SSE parsing and argument parsing are small in-tree classes, so the jar runs anywhere a JRE does and has nothing to patch.

| Package | Responsibility |
|:--|:--|
| `load` | load models (`LoadRunner`), prompts, per-request results |
| `http` | streaming client, SSE parser, chunk inspection |
| `metrics` | percentiles, run summary, goodput |
| `slo` | SLO parsing and evaluation |
| `report` | console, Markdown and JSON output |
| `mock` | mock LLM API server for demos and tests |

## Building and testing

```bash
./mvnw verify          # compile, run the JUnit tests, build target/llm-loadgate.jar
```

The test suite starts the mock server on a random port and drives real load through the client, including timeouts, injected 503s, truncated streams and servers that ignore `stream: true`.

<details>
<summary>All <code>run</code> options</summary>

```text
Usage: llm-loadgate run --base-url URL [options]

Target
  --base-url URL               API root including the version, e.g. http://localhost:8000/v1
  --model NAME                 model to request (default: first model from GET /models)
  --endpoint chat|completions  API to call (default: chat)
  --api-key KEY                bearer token (default: $LLM_API_KEY)
  --header 'Name: value'       extra request header, repeatable
  --http2                      use HTTP/2 instead of HTTP/1.1

Load
  --concurrency N              closed loop with N concurrent users (default: 8)
  --rate R                     open loop: Poisson arrivals at R requests/s
  --max-concurrency N          cap in-flight requests in open-loop mode
  --num-requests N             requests to measure (default: 100)
  --duration SECONDS           stop starting new requests after this long
  --warmup N                   requests to send and discard before measuring (default: 0)
  --timeout SECONDS            limit for one request, whole stream included (default: 300)

Requests
  --max-tokens N               max_tokens per request (default: 128)
  --prompts FILE               .jsonl with "prompt" or "messages", or one prompt per line
  --synthetic-input-tokens N   unique generated prompts of about N tokens
  --temperature T              sampling temperature (default: server default)
  --extra-body JSON            fields merged into each request, e.g. '{"ignore_eos": true}'
  --no-usage                   do not send stream_options.include_usage
  --seed N                     seed for arrivals and synthetic prompts (default: 0)

Gates and reports
  --slo EXPR                   e.g. 'ttft_p95<500ms', 'error_rate<1%', repeatable
  --goodput TARGETS            per-request targets, e.g. 'ttft<500ms,tpot<50ms'
  --json-out FILE              write a JSON report
  --markdown-out FILE          write a Markdown report
  --save-requests              include every request in the JSON report
  --no-github-summary          do not append to $GITHUB_STEP_SUMMARY
  --quiet                      no progress output
```

</details>

## Roadmap

- Rate sweeps in one command (`--rate 2:20:2`) with the knee called out in the report
- Prompt and output lengths sampled from real traffic logs
- Prometheus Pushgateway output for dashboards of nightly runs

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)

## Scope and limitations

This project is a focused regression-testing tool, not a replacement for production monitoring or a guarantee of application quality. Review the implementation and tests before adopting it in a deployment gate. Measurements and outcomes depend on the input data, workload, environment, and configuration.

For a reproducible report, record the commit SHA, Java version, command, test dataset or workload, and the generated output. Do not treat the example numbers in this README as independent performance claims.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) for development steps and [SECURITY.md](SECURITY.md) for vulnerability reporting. Changes to metrics or regression thresholds should include a focused test demonstrating the intended behavior.
