package io.github.nikhilucumberlands.loadgate.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.nikhilucumberlands.loadgate.util.Json;
import io.github.nikhilucumberlands.loadgate.util.JsonException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A mock LLM API server with controllable latency, for trying the tool and for tests
 * without a GPU. Built on the JDK's {@code HttpServer} with one virtual thread per request.
 *
 * <p>Streaming responses follow vLLM's shape: a role chunk, one chunk per token, a finish chunk,
 * an optional usage chunk, then {@code data: [DONE]}. Setting {@code maxConcurrency} makes requests
 * queue for a slot before "prefill" starts, which reproduces the TTFT climb you see on a saturated
 * server.
 */
public final class MockLlmServer implements AutoCloseable {

    private static final String[] WORDS = ("Latency budgets are easier to keep when every deploy is measured "
            + "against the same load profile and the results are compared before traffic shifts").split(" ");

    /**
     * @param ttftMillis     delay before the first token (after any queueing)
     * @param itlMillis      delay between tokens
     * @param defaultTokens  tokens to generate when the request has no {@code max_tokens}
     * @param jitter         uniform jitter as a fraction, e.g. 0.1 for plus or minus 10%
     * @param errorRate      share of requests answered with HTTP 503
     * @param maxConcurrency requests generating at once; 0 means unlimited
     */
    public record Options(String host, int port, String model, double ttftMillis, double itlMillis,
                          int defaultTokens, double jitter, double errorRate, int maxConcurrency, long seed) {

        public Options {
            if (ttftMillis < 0 || itlMillis < 0) {
                throw new IllegalArgumentException("delays cannot be negative");
            }
            if (defaultTokens < 1) {
                throw new IllegalArgumentException("defaultTokens must be at least 1");
            }
            if (jitter < 0 || jitter > 1) {
                throw new IllegalArgumentException("jitter must be between 0 and 1");
            }
            if (errorRate < 0 || errorRate > 1) {
                throw new IllegalArgumentException("errorRate must be between 0 and 1");
            }
            if (maxConcurrency < 0) {
                throw new IllegalArgumentException("maxConcurrency cannot be negative");
            }
        }

        public static Options defaults() {
            return new Options("127.0.0.1", 8000, "mock-llm", 150, 20, 128, 0.1, 0.0, 0, 0);
        }

        public Options withPort(int newPort) {
            return new Options(host, newPort, model, ttftMillis, itlMillis, defaultTokens, jitter, errorRate,
                    maxConcurrency, seed);
        }

        public Options withLatency(double newTtftMillis, double newItlMillis) {
            return new Options(host, port, model, newTtftMillis, newItlMillis, defaultTokens, jitter, errorRate,
                    maxConcurrency, seed);
        }

        public Options withErrorRate(double newErrorRate) {
            return new Options(host, port, model, ttftMillis, itlMillis, defaultTokens, jitter, newErrorRate,
                    maxConcurrency, seed);
        }

        public Options withMaxConcurrency(int newMaxConcurrency) {
            return new Options(host, port, model, ttftMillis, itlMillis, defaultTokens, jitter, errorRate,
                    newMaxConcurrency, seed);
        }

        public Options withJitter(double newJitter) {
            return new Options(host, port, model, ttftMillis, itlMillis, defaultTokens, newJitter, errorRate,
                    maxConcurrency, seed);
        }
    }

    private final Options options;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final AtomicLong requests = new AtomicLong();

    private MockLlmServer(Options options, HttpServer server, ExecutorService executor) {
        this.options = options;
        this.server = server;
        this.executor = executor;
        this.slots = options.maxConcurrency() > 0 ? new Semaphore(options.maxConcurrency(), true) : null;
    }

    public static MockLlmServer start(Options options) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 1024);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        MockLlmServer mock = new MockLlmServer(options, server, executor);
        server.createContext("/v1/chat/completions", exchange -> mock.handleCompletion(exchange, true));
        server.createContext("/v1/completions", exchange -> mock.handleCompletion(exchange, false));
        server.createContext("/v1/models", mock::handleModels);
        server.createContext("/health", mock::handleHealth);
        server.createContext("/", exchange -> mock.sendJson(exchange, 404, error("not found", "not_found_error")));
        server.start();
        return mock;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** API root for clients, e.g. {@code http://127.0.0.1:8000/v1}. */
    public URI baseUrl() {
        String host = options.host().equals("0.0.0.0") ? "127.0.0.1" : options.host();
        return URI.create("http://" + host + ":" + port() + "/v1");
    }

    public long requestsServed() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handleCompletion(HttpExchange exchange, boolean chat) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("use POST", "invalid_request_error"));
                return;
            }
            Map<String, Object> request;
            try (InputStream in = exchange.getRequestBody()) {
                request = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (JsonException e) {
                sendJson(exchange, 400, error("request body is not valid JSON", "invalid_request_error"));
                return;
            }
            long number = requests.incrementAndGet();
            SplittableRandom random = new SplittableRandom(options.seed() * 7919 + number);
            if (random.nextDouble() < options.errorRate()) {
                sendJson(exchange, 503, error("mock server: injected failure", "server_error"));
                return;
            }
            Generation generation = new Generation(
                    chat,
                    Json.string(request.get("model")) == null ? options.model() : Json.string(request.get("model")),
                    positive(request.get("max_tokens"), options.defaultTokens()),
                    estimatePromptTokens(request),
                    includeUsage(request),
                    number,
                    random);
            if (Boolean.TRUE.equals(request.get("stream"))) {
                stream(exchange, generation);
            } else {
                respond(exchange, generation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client went away mid-response; nothing left to do.
        } finally {
            exchange.close();
        }
    }

    private record Generation(boolean chat, String model, int tokens, int promptTokens, boolean includeUsage,
                              long number, SplittableRandom random) {

        String id() {
            return (chat ? "chatcmpl-mock-" : "cmpl-mock-") + number;
        }

        String word(int i) {
            return WORDS[(int) ((number + i) % WORDS.length)] + " ";
        }
    }

    private void stream(HttpExchange exchange, Generation g) throws IOException, InterruptedException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        long created = Instant.now().getEpochSecond();
        acquireSlot();
        try {
            pause(options.ttftMillis(), g.random());
            if (g.chat()) {
                writeEvent(out, chunk(g, created, Map.of("role", "assistant", "content", ""), null));
            }
            for (int i = 0; i < g.tokens(); i++) {
                if (i > 0) {
                    pause(options.itlMillis(), g.random());
                }
                Object piece = g.chat() ? Map.of("content", g.word(i)) : g.word(i);
                writeEvent(out, chunk(g, created, piece, null));
            }
            writeEvent(out, chunk(g, created, g.chat() ? Map.of() : "", "length"));
        } finally {
            releaseSlot();
        }
        if (g.includeUsage()) {
            Map<String, Object> usage = base(g, created);
            usage.put("choices", List.of());
            usage.put("usage", usage(g));
            writeEvent(out, usage);
        }
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    private void respond(HttpExchange exchange, Generation g) throws IOException, InterruptedException {
        acquireSlot();
        try {
            pause(options.ttftMillis() + options.itlMillis() * (g.tokens() - 1), g.random());
        } finally {
            releaseSlot();
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < g.tokens(); i++) {
            text.append(g.word(i));
        }
        Map<String, Object> body = base(g, Instant.now().getEpochSecond());
        body.put("object", g.chat() ? "chat.completion" : "text_completion");
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        if (g.chat()) {
            choice.put("message", Map.of("role", "assistant", "content", text.toString()));
        } else {
            choice.put("text", text.toString());
        }
        choice.put("finish_reason", "length");
        body.put("choices", List.of(choice));
        body.put("usage", usage(g));
        sendJson(exchange, 200, body);
    }

    private Map<String, Object> chunk(Generation g, long created, Object delta, String finishReason) {
        Map<String, Object> chunk = base(g, created);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put(g.chat() ? "delta" : "text", delta);
        choice.put("finish_reason", finishReason);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private static Map<String, Object> base(Generation g, long created) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", g.id());
        map.put("object", g.chat() ? "chat.completion.chunk" : "text_completion");
        map.put("created", created);
        map.put("model", g.model());
        return map;
    }

    private static Map<String, Object> usage(Generation g) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", g.promptTokens());
        usage.put("completion_tokens", g.tokens());
        usage.put("total_tokens", g.promptTokens() + g.tokens());
        return usage;
    }

    private void handleModels(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("id", options.model());
            model.put("object", "model");
            model.put("owned_by", "llm-loadgate");
            sendJson(exchange, 200, Map.of("object", "list", "data", List.of(model)));
        } finally {
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            byte[] body = "ok\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> error(String message, String type) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        return Map.of("error", error);
    }

    private static void writeEvent(OutputStream out, Object payload) throws IOException {
        out.write(("data: " + Json.write(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void acquireSlot() throws InterruptedException {
        if (slots != null) {
            slots.acquire();
        }
    }

    private void releaseSlot() {
        if (slots != null) {
            slots.release();
        }
    }

    private void pause(double millis, SplittableRandom random) throws InterruptedException {
        double factor = 1 + options.jitter() * (2 * random.nextDouble() - 1);
        long nanos = (long) (Math.max(0, millis * factor) * 1_000_000);
        if (nanos > 0) {
            Thread.sleep(Duration.ofNanos(nanos));
        }
    }

    private static int positive(Object value, int fallback) {
        double number = Json.number(value);
        return Double.isNaN(number) || number < 1 ? fallback : (int) Math.min(number, 100_000);
    }

    private static boolean includeUsage(Map<String, Object> request) {
        Map<String, Object> streamOptions = Json.asObject(request.get("stream_options"));
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.get("include_usage"));
    }

    private static int estimatePromptTokens(Map<String, Object> request) {
        StringBuilder text = new StringBuilder();
        String prompt = Json.string(request.get("prompt"));
        if (prompt != null) {
            text.append(prompt);
        }
        List<Object> messages = Json.asList(request.get("messages"));
        if (messages != null) {
            for (Object message : messages) {
                Map<String, Object> m = Json.asObject(message);
                if (m != null && m.get("content") instanceof String content) {
                    text.append(' ').append(content);
                }
            }
        }
        String trimmed = text.toString().strip();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }
}
