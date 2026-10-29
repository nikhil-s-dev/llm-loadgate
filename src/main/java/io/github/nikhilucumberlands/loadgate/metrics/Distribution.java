package io.github.nikhilucumberlands.loadgate.metrics;

import java.util.Arrays;
import java.util.List;

/** Summary statistics of one latency series, in seconds. Empty series give NaN everywhere. */
public record Distribution(int count, double mean, double p50, double p90, double p95, double p99, double max) {

    /** Statistic names, in report order. Combined with a metric name they form keys like {@code ttft_p95}. */
    public static final List<String> STATS = List.of("mean", "p50", "p90", "p95", "p99", "max");

    public static Distribution of(double[] values) {
        double[] sorted = Arrays.stream(values).filter(v -> !Double.isNaN(v)).sorted().toArray();
        return new Distribution(
                sorted.length,
                Stats.mean(sorted),
                Stats.percentile(sorted, 50),
                Stats.percentile(sorted, 90),
                Stats.percentile(sorted, 95),
                Stats.percentile(sorted, 99),
                sorted.length == 0 ? Double.NaN : sorted[sorted.length - 1]);
    }

    public double stat(String name) {
        return switch (name) {
            case "mean" -> mean;
            case "p50" -> p50;
            case "p90" -> p90;
            case "p95" -> p95;
            case "p99" -> p99;
            case "max" -> max;
            default -> throw new IllegalArgumentException("unknown statistic " + name);
        };
    }
}
