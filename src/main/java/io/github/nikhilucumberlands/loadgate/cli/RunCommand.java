package io.github.nikhilucumberlands.loadgate.cli;

import io.github.nikhilucumberlands.loadgate.config.LoadConfig;
import io.github.nikhilucumberlands.loadgate.config.LoadConfig.Endpoint;
import io.github.nikhilucumberlands.loadgate.http.StreamingClient;
import io.github.nikhilucumberlands.loadgate.load.LoadRunner;
import io.github.nikhilucumberlands.loadgate.load.PromptSource;
import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.load.RunResult;
import io.github.nikhilucumberlands.loadgate.metrics.Goodput;
import io.github.nikhilucumberlands.loadgate.metrics.Summary;
import io.github.nikhilucumberlands.loadgate.report.Reports;
import io.github.nikhilucumberlands.loadgate.report.RunReport;
import io.github.nikhilucumberlands.loadgate.slo.Slo;
import io.github.nikhilucumberlands.loadgate.slo.SloResult;
import io.github.nikhilucumberlands.loadgate.util.Json;
import io.github.nikhilucumberlands.loadgate.util.JsonException;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code llm-loadgate run}: generate load, summarize, check SLOs, set the exit code. */
public final class RunCommand {

    /** Request cap used when only {@code --duration} limits the run. */
    static final int UNBOUNDED = 1_000_000;

    private static final Set<String> VALUE_OPTIONS = Set.of(
            "base-url", "model", "endpoint", "max-tokens", "num-requests", "duration", "concurrency", "rate",
            "max-concurrency", "warmup", "timeout", "api-key", "header", "extra-body", "prompts",
            "synthetic-input-tokens", "temperature", "slo", "goodput", "json-out", "markdown-out", "seed");
    private static final Set<String> SWITCHES = Set.of(
            "help", "no-usage", "http2", "save-requests", "no-github-summary", "quiet");

    static final String HELP = """
            Usage: llm-loadgate run --base-url URL [options]

            Load-tests a compatible LLM API endpoint (vLLM, Triton, TGI, Ollama, ...) with streaming
            requests, reports latency percentiles and fails when an SLO is breached.

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

            Exit status: 0 all SLOs met, 1 an SLO failed or no request succeeded, 2 bad usage.
            """;

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;
    private final String version;

    public RunCommand(PrintStream out, PrintStream err, Map<String, String> env, String version) {
        this.out = out;
        this.err = err;
        this.env = env;
        this.version = version;
    }

    public int execute(String[] argv) throws UsageException {
        Args args = Args.parse(argv, VALUE_OPTIONS, SWITCHES);
        if (args.flag("help")) {
            out.print(HELP);
            return 0;
        }
        if (!args.positionals().isEmpty()) {
            throw new UsageException("unexpected argument '" + args.positionals().get(0) + "'");
        }
        if (args.has("concurrency") && args.has("rate")) {
            throw new UsageException("use --concurrency (closed loop) or --rate (open loop), not both");
        }
        if (args.has("max-concurrency") && !args.has("rate")) {
            throw new UsageException("--max-concurrency only applies with --rate");
        }
        if (args.has("prompts") && args.has("synthetic-input-tokens")) {
            throw new UsageException("use --prompts or --synthetic-input-tokens, not both");
        }

        List<Slo> slos = new ArrayList<>();
        for (String expression : args.values("slo")) {
            try {
                slos.add(Slo.parse(expression));
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
        }
        Goodput goodput = null;
        if (args.has("goodput")) {
            try {
                goodput = Goodput.parse(args.value("goodput"));
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
        }

        long seed = args.longValue("seed", 0);
        LoadConfig config = buildConfig(args, seed);
        PromptSource prompts = buildPrompts(args, seed);
        boolean quiet = args.flag("quiet");

        if (config.model() == null) {
            try {
                config = config.withModel(StreamingClient.discoverModel(config));
                if (!quiet) {
                    err.println("using model " + config.model() + " (from GET /models)");
                }
            } catch (IOException e) {
                err.println("error: --model not given and model discovery failed: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 130;
            }
        }

        if (!quiet) {
            err.printf(Locale.ROOT, "llm-loadgate %s: %s -> %s%n", version, config.describeLoad(),
                    config.baseUrl() + config.endpoint().path());
        }
        Instant startedAt = Instant.now();
        LoadRunner runner = new LoadRunner(config, new StreamingClient(config), prompts,
                quiet ? null : new ProgressPrinter(err, config.numRequests()));
        RunResult run;
        try {
            run = runner.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("interrupted");
            return 130;
        }

        Summary summary = Summary.of(run, goodput);
        Map<String, Double> metrics = summary.metrics();
        List<SloResult> results = slos.stream().map(slo -> slo.evaluate(metrics)).toList();
        RunReport report = new RunReport(version, startedAt, config, prompts.describe(), summary, results,
                args.flag("save-requests") ? run.results() : List.of());

        out.print(Reports.console(report));
        if (summary.succeeded() == 0) {
            String first = run.results().stream().map(RequestResult::error).findFirst().orElse("no requests were sent");
            err.println("error: no request succeeded; first error: " + first);
        }

        boolean wroteAll = write(args.value("json-out"), Json.pretty(Reports.json(report, args.flag("save-requests"))), false)
                & write(args.value("markdown-out"), Reports.markdown(report), false);
        String stepSummary = env.get("GITHUB_STEP_SUMMARY");
        if (!args.flag("no-github-summary") && stepSummary != null && !stepSummary.isBlank()) {
            wroteAll &= write(stepSummary, Reports.markdown(report) + "\n", true);
        }
        if (!wroteAll) {
            return 2;
        }
        return report.passed() ? 0 : 1;
    }

    private LoadConfig buildConfig(Args args, long seed) throws UsageException {
        URI baseUrl = parseBaseUrl(args.require("base-url"));
        Endpoint endpoint = switch (args.value("endpoint") == null ? "chat" : args.value("endpoint").strip()) {
            case "chat" -> Endpoint.CHAT;
            case "completions" -> Endpoint.COMPLETIONS;
            default -> throw new UsageException("--endpoint must be 'chat' or 'completions'");
        };
        Double duration = args.positiveDouble("duration", 0);
        int numRequests = args.has("num-requests") || duration == null
                ? args.intValue("num-requests", 100, 1)
                : UNBOUNDED;
        Double timeoutSeconds = args.positiveDouble("timeout", 0);
        Double rate = args.positiveDouble("rate", 0);
        String apiKey = args.value("api-key") != null ? args.value("api-key") : env.get("LLM_API_KEY");

        Map<String, Object> extraBody = Map.of();
        if (args.has("extra-body")) {
            try {
                extraBody = Json.parseObject(args.value("extra-body"));
            } catch (JsonException e) {
                throw new UsageException("--extra-body must be a JSON object: " + e.getMessage());
            }
        }

        try {
            return LoadConfig.builder(baseUrl)
                    .model(args.value("model"))
                    .endpoint(endpoint)
                    .maxTokens(args.intValue("max-tokens", 128, 1))
                    .numRequests(numRequests)
                    .durationSeconds(duration)
                    .concurrency(args.intValue("concurrency", 8, 1))
                    .requestRate(rate)
                    .maxConcurrency(args.has("max-concurrency") ? args.intValue("max-concurrency", 1, 1) : null)
                    .warmupRequests(args.intValue("warmup", 0, 0))
                    .timeout(timeoutSeconds == null ? Duration.ofSeconds(300) : Duration.ofNanos((long) (timeoutSeconds * 1e9)))
                    .apiKey(apiKey)
                    .headers(parseHeaders(args.values("header")))
                    .extraBody(extraBody)
                    .requestUsage(!args.flag("no-usage"))
                    .http2(args.flag("http2"))
                    .temperature(args.has("temperature") ? args.doubleInRange("temperature", 1, 0, 2) : null)
                    .seed(seed)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    private static PromptSource buildPrompts(Args args, long seed) throws UsageException {
        if (args.has("prompts")) {
            Path path = Path.of(args.value("prompts"));
            try {
                return PromptSource.fromFile(path);
            } catch (IOException e) {
                throw new UsageException("cannot read prompts: " + e.getMessage());
            }
        }
        if (args.has("synthetic-input-tokens")) {
            return PromptSource.synthetic(args.intValue("synthetic-input-tokens", 0, 1), seed);
        }
        return PromptSource.builtIn();
    }

    static URI parseBaseUrl(String raw) throws UsageException {
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new UsageException("--base-url is not a valid URL: " + raw);
        }
        boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        if (!httpScheme || uri.getHost() == null) {
            throw new UsageException("--base-url must be an http(s) URL, e.g. http://localhost:8000/v1");
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            throw new UsageException("--base-url should include the API prefix, e.g. " + raw.replaceAll("/+$", "") + "/v1");
        }
        return uri;
    }

    static Map<String, String> parseHeaders(List<String> raw) throws UsageException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String header : raw) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                throw new UsageException("--header must look like 'Name: value', got '" + header + "'");
            }
            String name = header.substring(0, colon).strip();
            String value = header.substring(colon + 1).strip();
            try {
                HttpRequest.newBuilder(URI.create("http://localhost/")).header(name, value);
            } catch (IllegalArgumentException e) {
                throw new UsageException("header '" + name + "' cannot be set: " + e.getMessage());
            }
            headers.put(name, value);
        }
        return headers;
    }

    private boolean write(String target, String content, boolean append) {
        if (target == null) {
            return true;
        }
        try {
            Path path = Path.of(target);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (append) {
                Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content);
            }
            return true;
        } catch (IOException e) {
            err.println("error: cannot write " + target + ": " + e.getMessage());
            return false;
        }
    }

    /** Prints a progress line at every 10% of planned requests (every 100 for duration runs). */
    static final class ProgressPrinter implements LoadRunner.Progress {
        private final PrintStream err;
        private final int step;
        private int failed;

        ProgressPrinter(PrintStream err, int planned) {
            this.err = err;
            this.step = planned >= UNBOUNDED ? 100 : Math.max(1, planned / 10);
        }

        @Override
        public synchronized void completed(RequestResult result, int done, int planned) {
            if (!result.ok()) {
                failed++;
            }
            if (done % step == 0 || done == planned) {
                String of = planned >= UNBOUNDED ? "" : "/" + planned;
                err.printf(Locale.ROOT, "  %d%s done, %d failed%n", done, of, failed);
            }
        }
    }
}
