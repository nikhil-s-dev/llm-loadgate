package io.github.nikhilucumberlands.loadgate.http;

import io.github.nikhilucumberlands.loadgate.config.LoadConfig;
import io.github.nikhilucumberlands.loadgate.config.LoadConfig.Endpoint;
import io.github.nikhilucumberlands.loadgate.load.Prompt;
import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.load.RequestSender;
import io.github.nikhilucumberlands.loadgate.util.Json;
import io.github.nikhilucumberlands.loadgate.util.JsonException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends streaming completion requests with {@link java.net.http.HttpClient}.
 *
 * <p>HTTP/1.1 is the default because inference servers such as vLLM (uvicorn) and TGI speak it,
 * and multiplexing many streams over one HTTP/2 connection would not match how real clients
 * connect. The timeout covers the whole request, from send until the last byte of the stream.
 */
public final class StreamingClient implements RequestSender {

    private final LoadConfig config;
    private final HttpClient http;
    private final URI uri;

    public StreamingClient(LoadConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .version(config.http2() ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.uri = URI.create(config.baseUrl() + config.endpoint().path());
    }

    @Override
    public RequestResult send(int index, Prompt prompt) {
        HttpRequest request = buildRequest(prompt);
        StreamCollector collector = new StreamCollector(config.endpoint());
        long start = System.nanoTime();
        collector.markStart(start);
        CompletableFuture<HttpResponse<Void>> future;
        try {
            future = http.sendAsync(request, info -> {
                collector.onHeaders(info.statusCode());
                return HttpResponse.BodySubscribers.fromLineSubscriber(collector);
            });
        } catch (RuntimeException e) {
            return RequestResult.failure(index, start, describe(e));
        }
        try {
            future.get(config.timeout().toNanos(), TimeUnit.NANOSECONDS);
            return collector.toResult(index, null);
        } catch (TimeoutException e) {
            collector.cancel();
            future.cancel(true);
            return collector.toResult(index, "timeout after " + seconds(config.timeout()));
        } catch (ExecutionException e) {
            return collector.toResult(index, describe(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            collector.cancel();
            future.cancel(true);
            return collector.toResult(index, "interrupted");
        }
    }

    HttpRequest buildRequest(Prompt prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        if (config.endpoint() == Endpoint.CHAT) {
            body.put("messages", prompt.messages());
        } else {
            body.put("prompt", prompt.text());
        }
        body.put("max_tokens", config.maxTokens());
        body.put("stream", true);
        if (config.requestUsage()) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        if (config.temperature() != null) {
            body.put("temperature", config.temperature());
        }
        body.putAll(config.extraBody());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        authorize(builder, config);
        return builder.build();
    }

    /**
     * Asks the server which models it serves ({@code GET /models}) and returns the first one, so a
     * single-model vLLM deployment works without {@code --model}.
     */
    public static String discoverModel(LoadConfig config) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/models"))
                .timeout(Duration.ofSeconds(30))
                .GET();
        authorize(builder, config);
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET " + config.baseUrl() + "/models returned HTTP " + response.statusCode());
            }
            try {
                List<Object> data = Json.asList(Json.parseObject(response.body()).get("data"));
                if (data != null) {
                    for (Object entry : data) {
                        Map<String, Object> model = Json.asObject(entry);
                        String id = model == null ? null : Json.string(model.get("id"));
                        if (id != null && !id.isBlank()) {
                            return id;
                        }
                    }
                }
            } catch (JsonException e) {
                throw new IOException("GET /models did not return JSON: " + e.getMessage(), e);
            }
            throw new IOException("GET " + config.baseUrl() + "/models listed no models");
        } catch (ConnectException e) {
            throw new IOException("cannot connect to " + config.baseUrl(), e);
        }
    }

    private static void authorize(HttpRequest.Builder builder, LoadConfig config) {
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        config.headers().forEach(builder::header);
    }

    static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpConnectTimeoutException) {
            return "connect timeout";
        }
        if (cause instanceof ConnectException) {
            return "connection refused";
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String seconds(Duration duration) {
        double value = duration.toNanos() / 1e9;
        return (value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value)) + "s";
    }
}
