package io.github.nikhilucumberlands.loadgate.load;

import io.github.nikhilucumberlands.loadgate.util.Json;
import io.github.nikhilucumberlands.loadgate.util.JsonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Supplies the prompt for request {@code i}. File and built-in prompts are reused in order once
 * exhausted; synthetic prompts are unique per request.
 *
 * <p>Reusing prompts matters for servers with prefix caching (vLLM, SGLang): repeated prompts skip
 * prefill and make TTFT look better than real traffic. Use synthetic prompts or a file with at
 * least as many lines as requests when that matters.
 */
public final class PromptSource {

    private static final List<String> BUILT_IN = List.of(
            "Explain the difference between latency and throughput in two short paragraphs.",
            "Write a Java method that checks whether a string is a palindrome, with Javadoc.",
            "Summarize the main causes of the 2008 financial crisis for a general audience.",
            "List five practical ways to reduce the cold-start time of a containerized service.",
            "Translate to French: The deployment finished and all health checks passed.",
            "Describe how a hash map handles collisions, with a small example.",
            "Draft a polite message asking a colleague to review a pull request by Friday.",
            "What are the trade-offs between batch and streaming data pipelines?");

    private static final String[] VOCABULARY = ("the a of to and in for on with as by at from that this it be is are was "
            + "system data model request server cache token memory network queue batch stream latency "
            + "throughput cluster node service storage index query result user time value error retry "
            + "update policy budget report metric signal window buffer thread worker schedule region "
            + "shard replica version release config secret record event table column field document "
            + "engine kernel device driver process module packet route bridge gateway proxy client "
            + "account invoice payment order customer product market price supply demand quarter "
            + "river mountain forest city harbor garden winter summer morning evening history culture")
            .split(" ");

    private final List<Prompt> prompts;
    private final int syntheticWords;
    private final long seed;
    private final String description;

    private PromptSource(List<Prompt> prompts, int syntheticWords, long seed, String description) {
        this.prompts = prompts;
        this.syntheticWords = syntheticWords;
        this.seed = seed;
        this.description = description;
    }

    public static PromptSource builtIn() {
        return of(BUILT_IN.stream().map(Prompt::ofText).toList(), "built-in prompts (" + BUILT_IN.size() + ")");
    }

    public static PromptSource of(List<Prompt> prompts, String description) {
        if (prompts.isEmpty()) {
            throw new IllegalArgumentException("need at least one prompt");
        }
        return new PromptSource(List.copyOf(prompts), 0, 0, description);
    }

    /**
     * Unique prompts of roughly {@code approxInputTokens} tokens. Common English words are about
     * one token each in Llama- and GPT-style tokenizers, so the count is approximate.
     */
    public static PromptSource synthetic(int approxInputTokens, long seed) {
        if (approxInputTokens < 1) {
            throw new IllegalArgumentException("approxInputTokens must be at least 1");
        }
        return new PromptSource(null, approxInputTokens, seed,
                "synthetic prompts (~" + approxInputTokens + " tokens, unique per request)");
    }

    /**
     * Reads prompts from a file. {@code .jsonl}: one object per line with either {@code "prompt"}
     * (string) or {@code "messages"} (chat messages). Any other extension: one prompt per line.
     */
    public static PromptSource fromFile(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Prompt> prompts = new ArrayList<>();
        boolean jsonl = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jsonl");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            prompts.add(jsonl ? parseJsonLine(line, path, i + 1) : Prompt.ofText(line));
        }
        if (prompts.isEmpty()) {
            throw new IOException(path + " contains no prompts");
        }
        return new PromptSource(List.copyOf(prompts), 0, 0, path.getFileName() + " (" + prompts.size() + " prompts)");
    }

    private static Prompt parseJsonLine(String line, Path path, int lineNumber) throws IOException {
        try {
            Map<String, Object> object = Json.parseObject(line);
            String text = Json.string(object.get("prompt"));
            if (text != null) {
                return Prompt.ofText(text);
            }
            List<Object> rawMessages = Json.asList(object.get("messages"));
            if (rawMessages != null && !rawMessages.isEmpty()) {
                List<Map<String, Object>> messages = new ArrayList<>();
                for (Object raw : rawMessages) {
                    Map<String, Object> message = Json.asObject(raw);
                    if (message == null || Json.string(message.get("role")) == null) {
                        throw new JsonException("each message needs a \"role\"");
                    }
                    messages.add(message);
                }
                return Prompt.ofMessages(messages);
            }
            throw new JsonException("expected a \"prompt\" string or a \"messages\" array");
        } catch (JsonException e) {
            throw new IOException(path + " line " + lineNumber + ": " + e.getMessage(), e);
        }
    }

    public Prompt get(int index) {
        if (prompts != null) {
            return prompts.get(Math.floorMod(index, prompts.size()));
        }
        Random random = new Random(seed * 1_000_003L + index);
        StringBuilder text = new StringBuilder("Request ").append(index).append(':');
        for (int i = 0; i < syntheticWords; i++) {
            text.append(' ').append(VOCABULARY[random.nextInt(VOCABULARY.length)]);
        }
        text.append(". Continue this text.");
        return Prompt.ofText(text.toString());
    }

    public String describe() {
        return description;
    }
}
