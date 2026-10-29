package io.github.nikhilucumberlands.loadgate.cli;

/** Invalid command-line input. The CLI prints the message and exits with status 2. */
public final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
        super(message);
    }
}
