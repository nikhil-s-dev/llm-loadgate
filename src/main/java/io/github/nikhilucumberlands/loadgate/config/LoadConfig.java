package io.github.nikhilucumberlands.loadgate.config;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a load run needs to know. Exactly one load model applies: closed loop (a fixed number
 * of concurrent users) when {@code requestRate} is null, open loop (Poisson arrivals) otherwise.
 *
 * @param baseUrl         API root including the version prefix, e.g. {@code http://localhost:8000/v1}
 * @param model           model name sent in every request; may be null until discovered
 * @param numRequests     requests in the measured phase (upper bound when a duration is set)
 * @param durationSeconds optional wall-clock limit for issuing new requests
 * @param concurrency     closed-loop workers
 * @param requestRate     open-loop arrival rate in requests per second
 * @param maxConcurrency  optional cap on in-flight requests in open-loop mode
 * @param warmupRequests  requests sent before measuring and then discarded
 * @param timeout         limit for one request, from send until the stream finishes
 * @param extraBody       fields merged into every request body, e.g. {@code {"ignore_eos": true}}
 * @param requestUsage    ask the server for token usage via {@code stream_options.include_usage}
 * @param seed            seed for arrival times and synthetic prompts
 */
public record LoadConfig(
        URI baseUrl,
        String model,
        Endpoint endpoint,
        int maxTokens,
        int numRequests,
        Double durationSeconds,
        int concurrency,
        Double requestRate,
        Integer maxConcurrency,
        int warmupRequests,
        Duration timeout,
        String apiKey,
        Map<String, String> headers,
        Map<String, Object> extraBody,
        boolean requestUsage,
        boolean http2,
        Double temperature,
        long seed) {

    /** The LLM API endpoint being exercised. */
    public enum Endpoint {
        CHAT("/chat/completions"),
        COMPLETIONS("/completions");

        private final String path;

        Endpoint(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    public LoadConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1");
        }
        if (numRequests < 1) {
            throw new IllegalArgumentException("numRequests must be at least 1");
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        if (requestRate != null && !(requestRate > 0)) {
            throw new IllegalArgumentException("requestRate must be greater than 0");
        }
        if (maxConcurrency != null && maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        if (maxConcurrency != null && requestRate == null) {
            throw new IllegalArgumentException("maxConcurrency only applies to open-loop runs");
        }
        if (durationSeconds != null && !(durationSeconds > 0)) {
            throw new IllegalArgumentException("durationSeconds must be greater than 0");
        }
        if (warmupRequests < 0) {
            throw new IllegalArgumentException("warmupRequests cannot be negative");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        extraBody = Collections.unmodifiableMap(new LinkedHashMap<>(extraBody == null ? Map.of() : extraBody));
    }

    public boolean openLoop() {
        return requestRate != null;
    }

    public LoadConfig withModel(String newModel) {
        return new LoadConfig(baseUrl, newModel, endpoint, maxTokens, numRequests, durationSeconds,
                concurrency, requestRate, maxConcurrency, warmupRequests, timeout, apiKey, headers,
                extraBody, requestUsage, http2, temperature, seed);
    }

    /** Short description of the load model for reports. */
    public String describeLoad() {
        if (!openLoop()) {
            return "closed loop, concurrency " + concurrency;
        }
        String text = String.format(java.util.Locale.ROOT, "open loop, %.2f req/s (Poisson arrivals)", requestRate);
        return maxConcurrency == null ? text : text + ", max " + maxConcurrency + " in flight";
    }

    public static Builder builder(URI baseUrl) {
        return new Builder(baseUrl);
    }

    /** Builder with the same defaults as the CLI. */
    public static final class Builder {
        private final URI baseUrl;
        private String model;
        private Endpoint endpoint = Endpoint.CHAT;
        private int maxTokens = 128;
        private int numRequests = 100;
        private Double durationSeconds;
        private int concurrency = 8;
        private Double requestRate;
        private Integer maxConcurrency;
        private int warmupRequests;
        private Duration timeout = Duration.ofSeconds(300);
        private String apiKey;
        private Map<String, String> headers = Map.of();
        private Map<String, Object> extraBody = Map.of();
        private boolean requestUsage = true;
        private boolean http2;
        private Double temperature;
        private long seed;

        private Builder(URI baseUrl) {
            String text = Objects.requireNonNull(baseUrl, "baseUrl").toString();
            while (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            this.baseUrl = URI.create(text);
        }

        public Builder model(String value) { this.model = value; return this; }
        public Builder endpoint(Endpoint value) { this.endpoint = value; return this; }
        public Builder maxTokens(int value) { this.maxTokens = value; return this; }
        public Builder numRequests(int value) { this.numRequests = value; return this; }
        public Builder durationSeconds(Double value) { this.durationSeconds = value; return this; }
        public Builder concurrency(int value) { this.concurrency = value; return this; }
        public Builder requestRate(Double value) { this.requestRate = value; return this; }
        public Builder maxConcurrency(Integer value) { this.maxConcurrency = value; return this; }
        public Builder warmupRequests(int value) { this.warmupRequests = value; return this; }
        public Builder timeout(Duration value) { this.timeout = value; return this; }
        public Builder apiKey(String value) { this.apiKey = value; return this; }
        public Builder headers(Map<String, String> value) { this.headers = value; return this; }
        public Builder extraBody(Map<String, Object> value) { this.extraBody = value; return this; }
        public Builder requestUsage(boolean value) { this.requestUsage = value; return this; }
        public Builder http2(boolean value) { this.http2 = value; return this; }
        public Builder temperature(Double value) { this.temperature = value; return this; }
        public Builder seed(long value) { this.seed = value; return this; }

        public LoadConfig build() {
            return new LoadConfig(baseUrl, model, endpoint, maxTokens, numRequests, durationSeconds,
                    concurrency, requestRate, maxConcurrency, warmupRequests, timeout, apiKey, headers,
                    extraBody, requestUsage, http2, temperature, seed);
        }
    }
}
