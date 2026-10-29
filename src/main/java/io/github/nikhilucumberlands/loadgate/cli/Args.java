package io.github.nikhilucumberlands.loadgate.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal command-line parser: {@code --name value}, {@code --name=value} and boolean switches.
 * Unknown options are rejected so a typo fails loudly instead of being silently ignored.
 */
public final class Args {

    private final Map<String, List<String>> options = new HashMap<>();
    private final Set<String> switches = new HashSet<>();
    private final List<String> positionals = new ArrayList<>();

    private Args() {}

    public static Args parse(String[] argv, Set<String> valueOptions, Set<String> switchOptions)
            throws UsageException {
        Args args = new Args();
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i].equals("-h") ? "--help" : argv[i];
            if (!token.startsWith("--") || token.length() == 2) {
                args.positionals.add(token);
                continue;
            }
            String name = token.substring(2);
            String inline = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                inline = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (switchOptions.contains(name)) {
                if (inline != null) {
                    throw new UsageException("--" + name + " does not take a value");
                }
                args.switches.add(name);
            } else if (valueOptions.contains(name)) {
                String value = inline;
                if (value == null) {
                    if (i + 1 >= argv.length) {
                        throw new UsageException("--" + name + " needs a value");
                    }
                    value = argv[++i];
                }
                args.options.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            } else {
                throw new UsageException("unknown option --" + name);
            }
        }
        return args;
    }

    public boolean has(String name) {
        return switches.contains(name) || options.containsKey(name);
    }

    public boolean flag(String name) {
        return switches.contains(name);
    }

    /** Last value given for the option, or {@code null}. */
    public String value(String name) {
        List<String> values = options.get(name);
        return values == null ? null : values.get(values.size() - 1);
    }

    /** Every value given for a repeatable option, in order. */
    public List<String> values(String name) {
        return options.getOrDefault(name, List.of());
    }

    public List<String> positionals() {
        return positionals;
    }

    public String require(String name) throws UsageException {
        String value = value(name);
        if (value == null || value.isBlank()) {
            throw new UsageException("--" + name + " is required");
        }
        return value;
    }

    public int intValue(String name, int defaultValue, int min) throws UsageException {
        String raw = value(name);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.strip());
            if (parsed < min) {
                throw new UsageException("--" + name + " must be at least " + min);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a whole number, got '" + raw + "'");
        }
    }

    public long longValue(String name, long defaultValue) throws UsageException {
        String raw = value(name);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a whole number, got '" + raw + "'");
        }
    }

    /** Parses a number that must be strictly greater than {@code exclusiveMin}; null when absent. */
    public Double positiveDouble(String name, double exclusiveMin) throws UsageException {
        String raw = value(name);
        if (raw == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(raw.strip());
            if (!(parsed > exclusiveMin) || !Double.isFinite(parsed)) {
                throw new UsageException("--" + name + " must be greater than " + exclusiveMin);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a number, got '" + raw + "'");
        }
    }

    /** Parses a number in the closed range [min, max]; returns the default when absent. */
    public double doubleInRange(String name, double defaultValue, double min, double max)
            throws UsageException {
        String raw = value(name);
        if (raw == null) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw.strip());
            if (parsed < min || parsed > max || Double.isNaN(parsed)) {
                throw new UsageException("--" + name + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a number, got '" + raw + "'");
        }
    }
}
