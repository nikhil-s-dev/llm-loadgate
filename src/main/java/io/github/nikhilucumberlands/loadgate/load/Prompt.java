package io.github.nikhilucumberlands.loadgate.load;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One prompt in both shapes the API accepts: plain text for {@code /completions} and a message
 * list for {@code /chat/completions}.
 */
public record Prompt(String text, List<Map<String, Object>> messages) {

    public Prompt {
        messages = List.copyOf(messages);
    }

    public static Prompt ofText(String text) {
        return new Prompt(text, List.of(Map.<String, Object>of("role", "user", "content", text)));
    }

    public static Prompt ofMessages(List<Map<String, Object>> messages) {
        String text = messages.stream()
                .map(message -> String.valueOf(message.getOrDefault("content", "")))
                .collect(Collectors.joining("\n"));
        return new Prompt(text, messages);
    }
}
