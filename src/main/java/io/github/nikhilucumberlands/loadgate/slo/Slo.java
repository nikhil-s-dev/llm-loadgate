package io.github.nikhilucumberlands.loadgate.slo;

import io.github.nikhilucumberlands.loadgate.metrics.Distribution;
import io.github.nikhilucumberlands.loadgate.metrics.Summary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pass/fail check on one run-level metric, written as {@code <metric><op><value>[unit]}:
 *
 * <pre>
 *   ttft_p95&lt;500ms      tpot_p99&lt;=0.05s     error_rate&lt;1%
 *   output_tokens_per_s&gt;=200               goodput_ratio&gt;=0.95
 * </pre>
 *
 * Latency metrics must carry a unit ({@code ms} or {@code s}): a bare {@code ttft_p95<500} would be
 * read as 500 seconds and pass silently, so it is rejected instead.
 *
 * @param threshold in base units: seconds for latencies, a 0..1 fraction for ratios
 */
public record Slo(String expression, String metric, Comparison comparison, double threshold) {

    private static final Pattern SYNTAX =
            Pattern.compile("^\\s*([a-z][a-z0-9_]*)\\s*(<=|>=|<|>)\\s*([0-9]*\\.?[0-9]+)\\s*(ms|s|%)?\\s*$");

    /** How a metric is measured, which decides the units it accepts and how it is printed. */
    public enum Kind { LATENCY, RATIO, RATE }

    public enum Comparison {
        LT("<"), LE("<="), GT(">"), GE(">=");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        boolean test(double actual, double limit) {
            return switch (this) {
                case LT -> actual < limit;
                case LE -> actual <= limit;
                case GT -> actual > limit;
                case GE -> actual >= limit;
            };
        }

        static Comparison of(String symbol) {
            for (Comparison c : values()) {
                if (c.symbol.equals(symbol)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("unknown comparison " + symbol);
        }
    }

    public static Slo parse(String expression) {
        Matcher m = SYNTAX.matcher(expression.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("SLO '" + expression
                    + "' must look like ttft_p95<500ms, error_rate<1% or output_tokens_per_s>=200");
        }
        String metric = m.group(1);
        double value = Double.parseDouble(m.group(3));
        String unit = m.group(4);
        Kind kind = kindOf(metric);
        if (kind == null) {
            throw new IllegalArgumentException("unknown metric '" + metric + "' in SLO '" + expression
                    + "'; available: " + String.join(", ", knownMetrics()));
        }
        double threshold = switch (kind) {
            case LATENCY -> {
                if (unit == null || unit.equals("%")) {
                    throw new IllegalArgumentException(metric + " is a latency; give the limit in ms or s, e.g. "
                            + metric + "<500ms");
                }
                yield unit.equals("ms") ? value / 1000 : value;
            }
            case RATIO -> {
                if ("ms".equals(unit) || "s".equals(unit)) {
                    throw new IllegalArgumentException(metric + " is a ratio; use a percentage (1%) or a fraction (0.01)");
                }
                yield "%".equals(unit) ? value / 100 : value;
            }
            case RATE -> {
                if (unit != null) {
                    throw new IllegalArgumentException(metric + " is a per-second rate; give a plain number");
                }
                yield value;
            }
        };
        return new Slo(expression.strip(), metric, Comparison.of(m.group(2)), threshold);
    }

    public SloResult evaluate(Map<String, Double> metrics) {
        Double actual = metrics.get(metric);
        if (actual == null || actual.isNaN()) {
            return new SloResult(this, Double.NaN, false, "no data for " + metric);
        }
        return new SloResult(this, actual, comparison.test(actual, threshold), null);
    }

    public Kind kind() {
        return kindOf(metric);
    }

    public static Kind kindOf(String metric) {
        for (String latency : Summary.LATENCY_METRICS) {
            for (String stat : Distribution.STATS) {
                if (metric.equals(latency + "_" + stat)) {
                    return Kind.LATENCY;
                }
            }
        }
        return switch (metric) {
            case "error_rate", "goodput_ratio" -> Kind.RATIO;
            case "requests_per_s", "output_tokens_per_s", "prompt_tokens_per_s", "goodput_requests_per_s" -> Kind.RATE;
            default -> null;
        };
    }

    public static List<String> knownMetrics() {
        List<String> names = new ArrayList<>();
        for (String latency : Summary.LATENCY_METRICS) {
            for (String stat : Distribution.STATS) {
                names.add(latency + "_" + stat);
            }
        }
        names.addAll(List.of("error_rate", "goodput_ratio", "requests_per_s", "output_tokens_per_s",
                "prompt_tokens_per_s", "goodput_requests_per_s"));
        return names;
    }

    /** Formats a value of this kind for people: latencies in ms, ratios in percent. */
    public static String format(Kind kind, double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        return switch (kind) {
            case LATENCY -> String.format(Locale.ROOT, "%.1f ms", value * 1000);
            case RATIO -> String.format(Locale.ROOT, "%.2f%%", value * 100);
            case RATE -> String.format(Locale.ROOT, "%.2f/s", value);
        };
    }
}
