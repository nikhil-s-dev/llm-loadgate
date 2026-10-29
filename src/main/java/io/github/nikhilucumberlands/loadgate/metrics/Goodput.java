package io.github.nikhilucumberlands.loadgate.metrics;

import io.github.nikhilucumberlands.loadgate.load.RequestResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-request latency targets. Goodput is the share (and rate) of requests that succeeded and met
 * every target, which says more about user experience than averages do: a server can post high
 * throughput while most individual requests miss their TTFT budget.
 *
 * <p>Syntax: comma-separated constraints such as {@code ttft<500ms,tpot<=50ms}. Metrics: ttft,
 * tpot, e2e. Units: ms or s.
 */
public record Goodput(String expression, List<Constraint> constraints) {

    private static final Pattern PART =
            Pattern.compile("^\\s*(ttft|tpot|e2e)\\s*(<=|<)\\s*([0-9]*\\.?[0-9]+)\\s*(ms|s)\\s*$");

    /** One per-request bound, threshold in seconds. */
    public record Constraint(String metric, boolean inclusive, double thresholdSeconds) {

        boolean test(RequestResult result) {
            double value = switch (metric) {
                case "ttft" -> result.ttftSeconds();
                case "tpot" -> result.tpotSeconds();
                case "e2e" -> result.latencySeconds();
                default -> throw new IllegalStateException("unknown metric " + metric);
            };
            if (Double.isNaN(value)) {
                // A one-token answer has no TPOT; that should not count against the request.
                return metric.equals("tpot");
            }
            return inclusive ? value <= thresholdSeconds : value < thresholdSeconds;
        }
    }

    public Goodput {
        constraints = List.copyOf(constraints);
    }

    public static Goodput parse(String expression) {
        List<Constraint> constraints = new ArrayList<>();
        for (String part : expression.split(",")) {
            Matcher m = PART.matcher(part.toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                throw new IllegalArgumentException("goodput target '" + part.strip()
                        + "' must look like ttft<500ms (metrics: ttft, tpot, e2e; units: ms or s)");
            }
            double value = Double.parseDouble(m.group(3));
            double seconds = m.group(4).equals("ms") ? value / 1000 : value;
            constraints.add(new Constraint(m.group(1), m.group(2).equals("<="), seconds));
        }
        return new Goodput(expression.strip(), constraints);
    }

    public boolean isGood(RequestResult result) {
        if (!result.ok()) {
            return false;
        }
        for (Constraint constraint : constraints) {
            if (!constraint.test(result)) {
                return false;
            }
        }
        return true;
    }
}
