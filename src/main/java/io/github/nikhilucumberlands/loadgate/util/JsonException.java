package io.github.nikhilucumberlands.loadgate.util;

/** Thrown when text is not valid JSON or does not have the expected shape. */
public final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
