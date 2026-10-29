package io.github.nikhilucumberlands.loadgate.load;

/** Sends one request and reports what happened. Implementations must be thread-safe. */
@FunctionalInterface
public interface RequestSender {

    /** Never throws for request-level failures; they are reported in the result instead. */
    RequestResult send(int index, Prompt prompt);
}
