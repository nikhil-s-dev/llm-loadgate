package io.github.nikhilucumberlands.loadgate;

import io.github.nikhilucumberlands.loadgate.cli.MockServerCommand;
import io.github.nikhilucumberlands.loadgate.cli.RunCommand;
import io.github.nikhilucumberlands.loadgate.cli.UsageException;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Map;

/** Command-line entry point. */
public final class Main {

    private static final String USAGE = """
            llm-loadgate: load-test LLM API endpoints and gate releases on latency SLOs.

            Usage:
              llm-loadgate run --base-url URL [options]    generate load and check SLOs
              llm-loadgate mock-server [options]           start a fake LLM server for trying things out
              llm-loadgate --version

            Run 'llm-loadgate <command> --help' for the options of each command.
            """;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.getenv()));
    }

    /** Runs a command and returns the exit status instead of exiting, so it can be tested. */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
        if (args.length == 0) {
            err.print(USAGE);
            return 2;
        }
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (command) {
                case "run" -> new RunCommand(out, err, env, version()).execute(rest);
                case "mock-server" -> new MockServerCommand(out).execute(rest);
                case "--version", "version" -> {
                    out.println("llm-loadgate " + version());
                    yield 0;
                }
                case "--help", "-h", "help" -> {
                    out.print(USAGE);
                    yield 0;
                }
                default -> throw new UsageException("unknown command '" + command + "'");
            };
        } catch (UsageException e) {
            err.println("error: " + e.getMessage());
            err.println("Run 'llm-loadgate --help' for usage.");
            return 2;
        }
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
