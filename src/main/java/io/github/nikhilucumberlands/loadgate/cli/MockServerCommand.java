package io.github.nikhilucumberlands.loadgate.cli;

import io.github.nikhilucumberlands.loadgate.mock.MockLlmServer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** {@code llm-loadgate mock-server}: run the mock LLM API server until interrupted. */
public final class MockServerCommand {

    private static final Set<String> VALUE_OPTIONS = Set.of(
            "host", "port", "model", "ttft-ms", "itl-ms", "tokens", "jitter", "error-rate", "max-concurrency", "seed");
    private static final Set<String> SWITCHES = Set.of("help");

    static final String HELP = """
            Usage: llm-loadgate mock-server [options]

            Runs a mock LLM API server with controllable latency, for trying llm-loadgate
            and for CI without a GPU. Serves /v1/chat/completions, /v1/completions, /v1/models, /health.

              --host HOST              bind address (default: 127.0.0.1; use 0.0.0.0 in containers)
              --port PORT              port (default: 8000, 0 picks a free port)
              --model NAME             model name to report (default: mock-llm)
              --ttft-ms MS             time to first token (default: 150)
              --itl-ms MS              time between tokens (default: 20)
              --tokens N               tokens when a request has no max_tokens (default: 128)
              --jitter FRACTION        random +/- jitter on delays, 0..1 (default: 0.1)
              --error-rate FRACTION    share of requests answered with HTTP 503 (default: 0)
              --max-concurrency N      requests generating at once; others queue (default: unlimited)
              --seed N                 random seed (default: 0)
            """;

    private final PrintStream out;

    public MockServerCommand(PrintStream out) {
        this.out = out;
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
        MockLlmServer.Options defaults = MockLlmServer.Options.defaults();
        MockLlmServer.Options options;
        try {
            options = new MockLlmServer.Options(
                    args.value("host") == null ? defaults.host() : args.value("host"),
                    args.intValue("port", defaults.port(), 0),
                    args.value("model") == null ? defaults.model() : args.value("model"),
                    args.doubleInRange("ttft-ms", defaults.ttftMillis(), 0, 600_000),
                    args.doubleInRange("itl-ms", defaults.itlMillis(), 0, 60_000),
                    args.intValue("tokens", defaults.defaultTokens(), 1),
                    args.doubleInRange("jitter", defaults.jitter(), 0, 1),
                    args.doubleInRange("error-rate", defaults.errorRate(), 0, 1),
                    args.intValue("max-concurrency", 0, 0),
                    args.longValue("seed", 0));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }

        MockLlmServer server;
        try {
            server = MockLlmServer.start(options);
        } catch (IOException e) {
            out.println("error: cannot start mock server on " + options.host() + ":" + options.port() + ": " + e.getMessage());
            return 1;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }));
        out.printf(Locale.ROOT, "mock server listening on %s (ttft %.0f ms, itl %.0f ms, error rate %.1f%%)%n",
                server.baseUrl(), options.ttftMillis(), options.itlMillis(), options.errorRate() * 100);
        out.flush();
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
        }
        return 0;
    }
}
