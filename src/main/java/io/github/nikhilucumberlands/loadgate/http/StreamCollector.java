package io.github.nikhilucumberlands.loadgate.http;

import io.github.nikhilucumberlands.loadgate.config.LoadConfig.Endpoint;
import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.util.DoubleList;
import io.github.nikhilucumberlands.loadgate.util.Json;
import io.github.nikhilucumberlands.loadgate.util.JsonException;
import io.github.nikhilucumberlands.loadgate.util.Text;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Receives the response body line by line as the JDK client reads it off the socket, so the
 * timestamps taken here are when each chunk actually arrived. Methods are synchronized because the
 * client delivers lines on its own threads while the caller may cancel on timeout.
 */
final class StreamCollector implements Flow.Subscriber<String> {

    private static final int MAX_ERROR_BODY = 400;

    private final Endpoint endpoint;
    private final SseParser sse = new SseParser();
    private final DoubleList interTokenLatencies = new DoubleList();
    private final StringBuilder errorBody = new StringBuilder();

    private Flow.Subscription subscription;
    private long startNanos;
    private int status;
    private boolean gotToken;
    private long firstTokenNanos;
    private long lastTokenNanos;
    private boolean ended;
    private long endNanos;
    private int tokenChunks;
    private int events;
    private int usageCompletionTokens = -1;
    private int usagePromptTokens = -1;
    private boolean sawDone;
    private String streamError;
    private String firstForeignLine;

    StreamCollector(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    synchronized void markStart(long nanos) {
        startNanos = nanos;
    }

    synchronized void onHeaders(int statusCode) {
        status = statusCode;
    }

    synchronized void cancel() {
        if (subscription != null) {
            subscription.cancel();
        }
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public synchronized void onNext(String line) {
        long now = System.nanoTime();
        if (status != 200) {
            if (errorBody.length() < MAX_ERROR_BODY) {
                errorBody.append(line).append(' ');
            }
            return;
        }
        if (firstForeignLine == null && !SseParser.isSseLine(line)) {
            firstForeignLine = line;
        }
        String data = sse.feed(line);
        if (data != null) {
            handleEvent(data, now);
        }
    }

    @Override
    public synchronized void onError(Throwable throwable) {
        markEnded(System.nanoTime());
    }

    @Override
    public synchronized void onComplete() {
        long now = System.nanoTime();
        String data = sse.finish();
        if (data != null && status == 200) {
            handleEvent(data, now);
        }
        markEnded(now);
    }

    private void markEnded(long now) {
        if (!ended) {
            ended = true;
            endNanos = now;
        }
    }

    private void handleEvent(String data, long now) {
        events++;
        if (data.equals("[DONE]")) {
            sawDone = true;
            return;
        }
        Map<String, Object> chunk;
        try {
            chunk = Json.parseObject(data);
        } catch (JsonException e) {
            if (streamError == null) {
                streamError = "malformed SSE payload: " + Text.abbreviate(data, 120);
            }
            return;
        }
        Object error = chunk.get("error");
        if (error != null) {
            if (streamError == null) {
                streamError = "server error in stream: " + Text.abbreviate(Json.write(error), 200);
            }
            return;
        }
        if (carriesToken(chunk)) {
            if (gotToken) {
                interTokenLatencies.add((now - lastTokenNanos) / 1e9);
            } else {
                gotToken = true;
                firstTokenNanos = now;
            }
            lastTokenNanos = now;
            tokenChunks++;
        }
        Map<String, Object> usage = Json.asObject(chunk.get("usage"));
        if (usage != null) {
            double completion = Json.number(usage.get("completion_tokens"));
            double prompt = Json.number(usage.get("prompt_tokens"));
            if (!Double.isNaN(completion)) {
                usageCompletionTokens = (int) completion;
            }
            if (!Double.isNaN(prompt)) {
                usagePromptTokens = (int) prompt;
            }
        }
    }

    private boolean carriesToken(Map<String, Object> chunk) {
        List<Object> choices = Json.asList(chunk.get("choices"));
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        Map<String, Object> choice = Json.asObject(choices.get(0));
        if (choice == null) {
            return false;
        }
        if (endpoint == Endpoint.COMPLETIONS) {
            return nonEmpty(choice.get("text"));
        }
        Map<String, Object> delta = Json.asObject(choice.get("delta"));
        // Reasoning models stream their thinking in a separate field; it still counts as output.
        return delta != null && (nonEmpty(delta.get("content"))
                || nonEmpty(delta.get("reasoning_content")) || nonEmpty(delta.get("reasoning")));
    }

    private static boolean nonEmpty(Object value) {
        return value instanceof String s && !s.isEmpty();
    }

    synchronized RequestResult toResult(int index, String transportError) {
        long end = ended ? endNanos : System.nanoTime();
        String error = transportError;
        if (error == null && status != 200) {
            String body = Text.abbreviate(errorBody.toString(), 200);
            error = "HTTP " + status + (body.isEmpty() ? "" : ": " + body);
        }
        if (error == null && streamError != null) {
            error = streamError;
        }
        if (error == null && events == 0 && firstForeignLine != null) {
            error = "expected an SSE stream but got: " + Text.abbreviate(firstForeignLine, 120);
        }
        if (error == null && !gotToken) {
            error = "stream finished without any tokens";
        }
        if (error == null && !sawDone) {
            error = "stream ended before [DONE]";
        }
        boolean usageReported = usageCompletionTokens >= 0;
        return new RequestResult(
                index,
                error == null,
                Math.max(status, 0),
                error,
                startNanos,
                gotToken ? (firstTokenNanos - startNanos) / 1e9 : Double.NaN,
                (end - startNanos) / 1e9,
                usageReported ? usageCompletionTokens : tokenChunks,
                usagePromptTokens,
                usageReported,
                interTokenLatencies.toArray());
    }
}
