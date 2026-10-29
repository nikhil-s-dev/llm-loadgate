package io.github.nikhilucumberlands.loadgate.http;

/**
 * Incremental parser for server-sent events, fed one line at a time (without line terminators).
 * Returns an event's {@code data} when the blank line that ends the event arrives. Only the
 * {@code data} field matters for streamed responses; {@code event}, {@code id}, {@code retry} and
 * comments are skipped.
 */
public final class SseParser {

    private final StringBuilder data = new StringBuilder();
    private boolean hasData;

    /** Feeds one line; returns the completed event's data, or null if the event is still open. */
    public String feed(String line) {
        if (line.isEmpty()) {
            return dispatch();
        }
        if (line.charAt(0) == ':') {
            return null;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        if (!field.equals("data")) {
            return null;
        }
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if (hasData) {
            data.append('\n');
        }
        data.append(value);
        hasData = true;
        return null;
    }

    /** Call when the stream ends; returns data of a final event that had no trailing blank line. */
    public String finish() {
        return dispatch();
    }

    /** True if the line is part of the SSE wire format rather than some other payload. */
    public static boolean isSseLine(String line) {
        return line.isEmpty() || line.startsWith(":") || line.startsWith("data:") || line.equals("data")
                || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:");
    }

    private String dispatch() {
        if (!hasData) {
            return null;
        }
        String result = data.toString();
        data.setLength(0);
        hasData = false;
        return result;
    }
}
