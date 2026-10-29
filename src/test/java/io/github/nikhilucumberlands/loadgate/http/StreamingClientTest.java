package io.github.nikhilucumberlands.loadgate.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.nikhilucumberlands.loadgate.Main;
import io.github.nikhilucumberlands.loadgate.config.LoadConfig;
import io.github.nikhilucumberlands.loadgate.load.Prompt;
import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Protocol edge cases, served by a tiny handler that replies with a canned body. */
class StreamingClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + "\nAuthorization=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + "\nX-Team=" + exchange.getRequestHeaders().getFirst("X-Team"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RequestResult send(LoadConfig.Builder builder) {
        LoadConfig config = builder.build();
        return new StreamingClient(config).send(0, Prompt.ofText("hello"));
    }

    private LoadConfig.Builder config() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        return LoadConfig.builder(base).model("m");
    }

    private static String chunk(String content) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"}}]}\n\n";
    }

    @Test
    void sendsACompatibleBodyWithAuthAndHeaders() {
        body = chunk("Hi") + chunk(" there") + "data: [DONE]\n\n";
        RequestResult result = send(config().apiKey("secret").headers(Map.of("X-Team", "infra"))
                .temperature(0.0).extraBody(Map.of("ignore_eos", true)));

        assertTrue(result.ok(), result.error());
        assertEquals(2, result.outputTokens());
        assertFalse(result.usageReported());
        String[] parts = lastRequest.get().split("\n");
        Map<String, Object> request = Json.parseObject(parts[0]);
        assertEquals("m", request.get("model"));
        assertEquals(true, request.get("stream"));
        assertEquals(true, request.get("ignore_eos"));
        assertEquals(0.0, request.get("temperature"));
        assertEquals(Map.of("include_usage", true), request.get("stream_options"));
        assertEquals("Authorization=Bearer secret", parts[1]);
        assertEquals("X-Team=infra", parts[2]);
    }

    @Test
    void cliUsesEnvironmentKeyAndExplicitKeyTakesPrecedence() {
        body = chunk("hello") + "data: [DONE]\n\n";
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        String[] args = {"run", "--base-url", base, "--model", "m", "--num-requests", "1", "--quiet"};
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, Main.run(args, sink, sink, Map.of("LLM_API_KEY", "env-key")));
        assertTrue(lastRequest.get().contains("Authorization=Bearer env-key"));
        String[] override = {"run", "--base-url", base, "--model", "m", "--num-requests", "1", "--quiet", "--api-key", "explicit-key"};
        assertEquals(0, Main.run(override, sink, sink, Map.of("LLM_API_KEY", "env-key")));
        assertTrue(lastRequest.get().contains("Authorization=Bearer explicit-key"));
    }

    @Test
    void prefersServerReportedUsage() {
        body = chunk("a") + chunk("b")
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}\n\n"
                + "data: [DONE]\n\n";
        RequestResult result = send(config());
        assertTrue(result.ok());
        assertEquals(5, result.outputTokens());
        assertEquals(12, result.promptTokens());
        assertTrue(result.usageReported());
    }

    @Test
    void countsReasoningTokens() {
        body = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n" + chunk("answer")
                + "data: [DONE]\n\n";
        assertEquals(2, send(config()).outputTokens());
    }

    @Test
    void reportsHttpErrorsWithTheBody() {
        status = 429;
        body = "{\"error\":{\"message\":\"rate limited\"}}";
        RequestResult result = send(config());
        assertFalse(result.ok());
        assertEquals(429, result.statusCode());
        assertTrue(result.error().startsWith("HTTP 429") && result.error().contains("rate limited"), result.error());
    }

    @Test
    void flagsAServerThatIgnoredStreamTrue() {
        body = "{\"choices\":[{\"message\":{\"content\":\"whole answer\"}}]}";
        RequestResult result = send(config());
        assertFalse(result.ok());
        assertTrue(result.error().startsWith("expected an SSE stream"), result.error());
    }

    @Test
    void flagsATruncatedStream() {
        body = chunk("partial");
        RequestResult result = send(config());
        assertFalse(result.ok());
        assertEquals("stream ended before [DONE]", result.error());
    }

    @Test
    void flagsErrorsInsideTheStream() {
        body = chunk("x") + "data: {\"error\":{\"message\":\"CUDA out of memory\"}}\n\n" + "data: [DONE]\n\n";
        RequestResult result = send(config());
        assertFalse(result.ok());
        assertTrue(result.error().contains("CUDA out of memory"), result.error());
    }

    @Test
    void flagsAStreamWithoutTokens() {
        body = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\ndata: [DONE]\n\n";
        assertEquals("stream finished without any tokens", send(config()).error());
    }
}
