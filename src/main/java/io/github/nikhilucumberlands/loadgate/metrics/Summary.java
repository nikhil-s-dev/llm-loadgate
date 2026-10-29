package io.github.nikhilucumberlands.loadgate.metrics;

import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.load.RunResult;
import io.github.nikhilucumberlands.loadgate.util.Text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated results of a run. Latency distributions only include successful requests; failures are
 * counted in {@code errorRate} instead of skewing the percentiles.
 */
public record Summary(
        int planned,
        int sent,
        int succeeded,
        int failed,
        double durationSeconds,
        double errorRate,
        double requestsPerSecond,
        double outputTokensPerSecond,
        double promptTokensPerSecond,
        String tokenCountSource,
        Distribution ttft,
        Distribution tpot,
        Distribution itl,
        Distribution e2e,
        Goodput goodput,
        double goodputRatio,
        double goodputPerSecond,
        Map<String, Integer> topErrors) {

    /** Latency series, in report order. */
    public static final List<String> LATENCY_METRICS = List.of("ttft", "tpot", "itl", "e2e");

    public Summary {
        topErrors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(topErrors));
    }

    public static Summary of(RunResult run, Goodput goodput) {
        List<RequestResult> results = run.results();
        List<RequestResult> ok = results.stream().filter(RequestResult::ok).toList();
        int sent = results.size();
        double duration = run.durationSeconds();

        long outputTokens = ok.stream().mapToLong(RequestResult::outputTokens).sum();
        boolean promptTokensKnown = !ok.isEmpty() && ok.stream().allMatch(r -> r.promptTokens() >= 0);
        long promptTokens = ok.stream().mapToLong(r -> Math.max(r.promptTokens(), 0)).sum();
        long withUsage = ok.stream().filter(RequestResult::usageReported).count();
        String tokenSource = ok.isEmpty() ? "none"
                : withUsage == ok.size() ? "server usage"
                : withUsage == 0 ? "stream chunks"
                : "mixed (usage where reported, chunks otherwise)";

        double[] itl = ok.stream().flatMapToDouble(r -> java.util.Arrays.stream(r.interTokenLatencies())).toArray();

        double goodRatio = Double.NaN;
        double goodRate = Double.NaN;
        if (goodput != null && sent > 0) {
            long good = results.stream().filter(goodput::isGood).count();
            goodRatio = (double) good / sent;
            goodRate = duration > 0 ? good / duration : Double.NaN;
        }

        Map<String, Integer> errors = results.stream()
                .filter(r -> !r.ok())
                .collect(Collectors.groupingBy(r -> Text.abbreviate(r.error(), 90), Collectors.summingInt(r -> 1)));
        Map<String, Integer> topErrors = new LinkedHashMap<>();
        errors.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .forEach(e -> topErrors.put(e.getKey(), e.getValue()));

        return new Summary(
                run.planned(),
                sent,
                ok.size(),
                sent - ok.size(),
                duration,
                sent == 0 ? Double.NaN : (double) (sent - ok.size()) / sent,
                duration > 0 ? ok.size() / duration : Double.NaN,
                duration > 0 ? outputTokens / duration : Double.NaN,
                promptTokensKnown && duration > 0 ? promptTokens / duration : Double.NaN,
                tokenSource,
                Distribution.of(ok.stream().mapToDouble(RequestResult::ttftSeconds).toArray()),
                Distribution.of(ok.stream().mapToDouble(RequestResult::tpotSeconds).toArray()),
                Distribution.of(itl),
                Distribution.of(ok.stream().mapToDouble(RequestResult::latencySeconds).toArray()),
                goodput,
                goodRatio,
                goodRate,
                topErrors);
    }

    public Distribution distribution(String metric) {
        return switch (metric) {
            case "ttft" -> ttft;
            case "tpot" -> tpot;
            case "itl" -> itl;
            case "e2e" -> e2e;
            default -> throw new IllegalArgumentException("unknown latency metric " + metric);
        };
    }

    /**
     * Flat view of every metric an SLO can reference, e.g. {@code ttft_p95} or {@code error_rate}.
     * Latencies are in seconds, ratios between 0 and 1, rates per second. NaN means no data.
     */
    public Map<String, Double> metrics() {
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (String metric : LATENCY_METRICS) {
            Distribution distribution = distribution(metric);
            for (String stat : Distribution.STATS) {
                metrics.put(metric + "_" + stat, distribution.stat(stat));
            }
        }
        metrics.put("error_rate", errorRate);
        metrics.put("requests_per_s", requestsPerSecond);
        metrics.put("output_tokens_per_s", outputTokensPerSecond);
        metrics.put("prompt_tokens_per_s", promptTokensPerSecond);
        metrics.put("goodput_ratio", goodputRatio);
        metrics.put("goodput_requests_per_s", goodputPerSecond);
        return metrics;
    }
}
