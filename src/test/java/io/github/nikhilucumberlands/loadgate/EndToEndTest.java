package io.github.nikhilucumberlands.loadgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nikhilucumberlands.loadgate.config.LoadConfig;
import io.github.nikhilucumberlands.loadgate.http.StreamingClient;
import io.github.nikhilucumberlands.loadgate.load.LoadRunner;
import io.github.nikhilucumberlands.loadgate.load.PromptSource;
import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.load.RunResult;
import io.github.nikhilucumberlands.loadgate.metrics.Summary;
import io.github.nikhilucumberlands.loadgate.mock.MockLlmServer;
import io.github.nikhilucumberlands.loadgate.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs real load against the bundled mock server over loopback. */
class EndToEndTest {

    private static MockLlmServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = MockLlmServer.start(MockLlmServer.Options.defaults().withPort(0).withLatency(40, 4).withJitter(0));
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    private static LoadConfig.Builder config() {
        return LoadConfig.builder(server.baseUrl()).model("mock-llm").maxTokens(16);
    }

    private static RunResult run(LoadConfig config) throws InterruptedException {
        return new LoadRunner(config, new StreamingClient(config), PromptSource.builtIn(), null).run();
    }

    @Test
    void closedLoopMeasuresStreamingLatency() throws InterruptedException {
        RunResult run = run(config().numRequests(20).concurrency(4).build());

        assertEquals(20, run.results().size());
        for (RequestResult r : run.results()) {
            assertTrue(r.ok(), () -> "request failed: " + r.error());
            assertEquals(16, r.outputTokens());
            assertTrue(r.usageReported());
            assertEquals(15, r.interTokenLatencies().length);
            assertTrue(r.ttftSeconds() >= 0.035, "TTFT includes the server's 40 ms first-token delay");
            assertTrue(r.latencySeconds() > r.ttftSeconds());
            assertTrue(Double.isFinite(r.tpotSeconds()) && r.tpotSeconds() > 0);
            assertEquals((r.latencySeconds() - r.ttftSeconds()) / 15.0, r.tpotSeconds(), 1e-12);
        }
        Summary summary = Summary.of(run, null);
        assertEquals(0.0, summary.errorRate());
        // Network buffering and thread scheduling can batch streamed chunks at the receiver.
        // Check the recorded measurements, rather than assuming client gaps match server sleeps.
        assertEquals(20, summary.tpot().count());
        double expectedMean = run.results().stream()
                .mapToDouble(r -> (r.latencySeconds() - r.ttftSeconds()) / 15.0).average().orElseThrow();
        assertEquals(expectedMean, summary.tpot().mean(), 1e-12);
        assertTrue(summary.ttft().p50() < summary.e2e().p50());
    }

    @Test
    void completionsEndpointCountsChunksWhenUsageIsOff() throws InterruptedException {
        RunResult run = run(config().endpoint(LoadConfig.Endpoint.COMPLETIONS).requestUsage(false)
                .numRequests(5).concurrency(5).maxTokens(8).build());
        for (RequestResult r : run.results()) {
            assertTrue(r.ok(), r::error);
            assertFalse(r.usageReported());
            assertEquals(8, r.outputTokens());
        }
    }

    @Test
    void openLoopPacesArrivals() throws InterruptedException {
        RunResult run = run(config().requestRate(50.0).maxConcurrency(8).numRequests(25).maxTokens(4).build());
        assertEquals(25, run.results().size());
        assertTrue(run.results().stream().allMatch(RequestResult::ok));
        // 25 Poisson arrivals at 50/s take about half a second; far less would mean no pacing.
        assertTrue(run.durationSeconds() > 0.2, "duration was " + run.durationSeconds());
    }

    @Test
    void durationLimitStopsNewRequests() throws InterruptedException {
        RunResult run = run(config().numRequests(10_000).durationSeconds(0.5).concurrency(2).maxTokens(4).build());
        assertTrue(run.results().size() < 100, "sent " + run.results().size());
        assertTrue(run.durationSeconds() < 2.0);
    }

    @Test
    void injectedErrorsAreCountedNotHidden() throws IOException, InterruptedException {
        try (MockLlmServer flaky = MockLlmServer.start(
                MockLlmServer.Options.defaults().withPort(0).withLatency(5, 1).withErrorRate(0.5))) {
            LoadConfig cfg = LoadConfig.builder(flaky.baseUrl()).model("mock-llm").maxTokens(4)
                    .numRequests(40).concurrency(4).build();
            Summary summary = Summary.of(run(cfg), null);
            assertTrue(summary.errorRate() > 0.2 && summary.errorRate() < 0.8, "error rate " + summary.errorRate());
            assertTrue(summary.topErrors().keySet().iterator().next().startsWith("HTTP 503"));
            assertEquals(summary.succeeded(), summary.ttft().count());
        }
    }

    @Test
    void timeoutCoversTheWholeStream() throws IOException, InterruptedException {
        try (MockLlmServer slow = MockLlmServer.start(MockLlmServer.Options.defaults().withPort(0).withLatency(2000, 1))) {
            LoadConfig cfg = LoadConfig.builder(slow.baseUrl()).model("mock-llm").numRequests(2).concurrency(2)
                    .timeout(Duration.ofMillis(300)).build();
            long start = System.nanoTime();
            RunResult result = run(cfg);
            assertTrue((System.nanoTime() - start) / 1e9 < 1.5, "requests should be cut off at the timeout");
            for (RequestResult r : result.results()) {
                assertFalse(r.ok());
                assertTrue(r.error().startsWith("timeout"), r.error());
            }
        }
    }

    @Test
    void warmupRequestsAreNotMeasured() throws InterruptedException {
        long before = server.requestsServed();
        RunResult run = run(config().numRequests(6).warmupRequests(4).concurrency(2).maxTokens(2).build());
        assertEquals(6, run.results().size());
        assertEquals(10, server.requestsServed() - before);
    }

    // ---- CLI -------------------------------------------------------------------------------------

    private record Cli(int exitCode, String out, String err) {}

    private static Cli cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of());
        return new Cli(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String[] runArgs(String... extra) {
        List<String> args = new ArrayList<>(List.of("run", "--base-url", server.baseUrl().toString(),
                "--num-requests", "8", "--concurrency", "4", "--max-tokens", "8", "--quiet", "--no-github-summary"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    @Test
    void cliPassesWhenSlosHoldAndDiscoversTheModel(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("out/report.json");
        Path markdown = dir.resolve("report.md");
        Cli result = cli(runArgs("--slo", "ttft_p95<5s", "--slo", "error_rate<1%",
                "--json-out", json.toString(), "--markdown-out", markdown.toString(), "--save-requests"));

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("PASS  ttft_p95<5s"), result.out());
        Map<String, Object> report = Json.parseObject(Files.readString(json));
        assertEquals(true, report.get("passed"));
        assertEquals("mock-llm", Json.asObject(report.get("config")).get("model"));
        assertEquals(8, Json.asList(report.get("requests")).size());
        assertTrue(Files.readString(markdown).contains("| `error_rate<1%` |"));
    }

    @Test
    void cliFailsWhenAnSloIsBreached(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("report.json");
        Cli result = cli(runArgs("--slo", "ttft_p95<1ms", "--json-out", json.toString()));

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("FAIL  ttft_p95<1ms"), result.out());
        Map<String, Object> slo = Json.asObject(Json.asList(Json.parseObject(Files.readString(json)).get("slos")).get(0));
        assertEquals(false, slo.get("passed"));
    }

    @Test
    void cliRejectsBadInputWithStatusTwo() {
        assertEquals(2, cli(runArgs("--slo", "ttft_p95<500")).exitCode());
        assertEquals(2, cli(runArgs("--rate", "5")).exitCode(), "--rate conflicts with --concurrency");
        assertEquals(2, cli("run", "--base-url", "http://localhost:8000").exitCode(), "missing /v1 prefix");
        assertEquals(2, cli(runArgs("--bogus")).exitCode());
        assertEquals(2, cli(runArgs("--header", "Host: example.com")).exitCode());
        assertEquals(2, cli("frobnicate").exitCode());
    }

    @Test
    void cliFailsWhenNothingSucceeds() {
        Cli result = cli("run", "--base-url", "http://127.0.0.1:9/v1", "--model", "m", "--num-requests", "2",
                "--quiet", "--no-github-summary");
        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("no request succeeded"), result.err());
    }

    @Test
    void cliAppendsToTheGithubStepSummary(@TempDir Path dir) throws IOException {
        Path summary = dir.resolve("step-summary.md");
        Files.writeString(summary, "existing\n");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(sink, true, StandardCharsets.UTF_8);
        String[] args = {"run", "--base-url", server.baseUrl().toString(), "--num-requests", "3", "--max-tokens", "2", "--quiet"};
        int code = Main.run(args, stream, stream, Map.of("GITHUB_STEP_SUMMARY", summary.toString()));
        assertEquals(0, code);
        String content = Files.readString(summary);
        assertTrue(content.startsWith("existing\n### llm-loadgate: PASS"), content);
    }
}
