package io.github.nikhilucumberlands.loadgate.report;

import io.github.nikhilucumberlands.loadgate.config.LoadConfig;
import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.metrics.Distribution;
import io.github.nikhilucumberlands.loadgate.metrics.Summary;
import io.github.nikhilucumberlands.loadgate.slo.Slo;
import io.github.nikhilucumberlands.loadgate.slo.SloResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a {@link RunReport} for terminals, GitHub step summaries (Markdown) and machines (JSON). */
public final class Reports {

    private Reports() {}

    public static String console(RunReport report) {
        Summary s = report.summary();
        LoadConfig c = report.config();
        StringBuilder out = new StringBuilder();
        out.append("llm-loadgate ").append(report.version()).append('\n');
        line(out, "target", report.target() + " (model " + c.model() + ")");
        line(out, "load", c.describeLoad() + "; " + plannedText(s) + ", max_tokens " + c.maxTokens());
        line(out, "prompts", report.promptDescription());
        out.append('\n');
        line(out, "requests", requestsText(s));
        line(out, "throughput", throughputText(s));
        out.append('\n');

        out.append(String.format(Locale.ROOT, "%-14s", "latency (ms)"));
        for (String stat : Distribution.STATS) {
            out.append(String.format(Locale.ROOT, "%10s", stat));
        }
        out.append('\n');
        for (String metric : Summary.LATENCY_METRICS) {
            Distribution d = s.distribution(metric);
            out.append(String.format(Locale.ROOT, "%-14s", metric.toUpperCase(Locale.ROOT)));
            for (String stat : Distribution.STATS) {
                out.append(String.format(Locale.ROOT, "%10s", millis(d.stat(stat))));
            }
            out.append('\n');
        }

        if (s.goodput() != null) {
            out.append('\n');
            line(out, "goodput", goodputText(s));
        }
        if (!s.topErrors().isEmpty()) {
            out.append("\nerrors\n");
            s.topErrors().forEach((error, count) ->
                    out.append(String.format(Locale.ROOT, "  %4dx  %s%n", count, error)));
        }
        if (!report.slos().isEmpty()) {
            out.append("\nSLOs\n");
            for (SloResult result : report.slos()) {
                out.append(String.format(Locale.ROOT, "  %s  %-26s %s%s%n",
                        result.passed() ? "PASS" : "FAIL",
                        result.slo().expression(),
                        result.formattedActual(),
                        result.note() == null ? "" : " (" + result.note() + ")"));
            }
        }
        out.append('\n');
        line(out, "result", verdict(report));
        return out.toString();
    }

    public static String markdown(RunReport report) {
        Summary s = report.summary();
        LoadConfig c = report.config();
        StringBuilder md = new StringBuilder();
        md.append("### llm-loadgate: ").append(report.passed() ? "PASS" : "FAIL").append("\n\n");
        md.append("**Target:** `").append(report.target()).append("` (model `").append(c.model()).append("`), ")
                .append(c.describeLoad()).append(", ").append(plannedText(s)).append("  \n");
        md.append("**Requests:** ").append(requestsText(s)).append("  \n");
        md.append("**Throughput:** ").append(throughputText(s)).append("\n\n");

        md.append("| latency (ms) |");
        Distribution.STATS.forEach(stat -> md.append(' ').append(stat).append(" |"));
        md.append("\n|:--|");
        Distribution.STATS.forEach(stat -> md.append("--:|"));
        md.append('\n');
        for (String metric : Summary.LATENCY_METRICS) {
            Distribution d = s.distribution(metric);
            md.append("| ").append(metric.toUpperCase(Locale.ROOT)).append(" |");
            Distribution.STATS.forEach(stat -> md.append(' ').append(millis(d.stat(stat))).append(" |"));
            md.append('\n');
        }
        if (s.goodput() != null) {
            md.append("\n**Goodput:** ").append(goodputText(s)).append('\n');
        }
        if (!s.topErrors().isEmpty()) {
            md.append("\n| errors | count |\n|:--|--:|\n");
            s.topErrors().forEach((error, count) ->
                    md.append("| `").append(error.replace("|", "\\|").replace("`", "'")).append("` | ").append(count).append(" |\n"));
        }
        if (!report.slos().isEmpty()) {
            md.append("\n| SLO | actual | result |\n|:--|--:|:--|\n");
            for (SloResult result : report.slos()) {
                md.append("| `").append(result.slo().expression()).append("` | ")
                        .append(result.formattedActual()).append(" | ")
                        .append(result.passed() ? "✅ pass" : "❌ fail").append(" |\n");
            }
        }
        return md.toString();
    }

    public static Map<String, Object> json(RunReport report, boolean includeRequests) {
        Summary s = report.summary();
        LoadConfig c = report.config();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", "llm-loadgate");
        root.put("version", report.version());
        root.put("started_at", report.startedAt().toString());
        root.put("passed", report.passed());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("target", report.target());
        config.put("model", c.model());
        config.put("load", c.openLoop() ? "open" : "closed");
        config.put("concurrency", c.openLoop() ? null : c.concurrency());
        config.put("rate_per_s", c.requestRate());
        config.put("max_concurrency", c.maxConcurrency());
        config.put("num_requests", c.numRequests());
        config.put("duration_s", c.durationSeconds());
        config.put("warmup_requests", c.warmupRequests());
        config.put("max_tokens", c.maxTokens());
        config.put("prompts", report.promptDescription());
        config.put("header_names", new ArrayList<>(c.headers().keySet()));
        config.put("seed", c.seed());
        root.put("config", config);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("planned", s.planned());
        summary.put("sent", s.sent());
        summary.put("succeeded", s.succeeded());
        summary.put("failed", s.failed());
        summary.put("duration_s", round(s.durationSeconds()));
        summary.put("token_count_source", s.tokenCountSource());
        if (s.goodput() != null) {
            summary.put("goodput_targets", s.goodput().expression());
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        s.metrics().forEach((name, value) -> metrics.put(name, round(value)));
        summary.put("metrics", metrics);
        summary.put("top_errors", s.topErrors());
        root.put("summary", summary);

        List<Object> slos = new ArrayList<>();
        for (SloResult result : report.slos()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("expression", result.slo().expression());
            entry.put("metric", result.slo().metric());
            entry.put("threshold", round(result.slo().threshold()));
            entry.put("actual", round(result.actual()));
            entry.put("passed", result.passed());
            if (result.note() != null) {
                entry.put("note", result.note());
            }
            slos.add(entry);
        }
        root.put("slos", slos);

        if (includeRequests) {
            List<Object> requests = new ArrayList<>();
            for (RequestResult r : report.requests()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", r.index());
                entry.put("ok", r.ok());
                entry.put("status", r.statusCode());
                entry.put("error", r.error());
                entry.put("ttft_s", round(r.ttftSeconds()));
                entry.put("tpot_s", round(r.tpotSeconds()));
                entry.put("e2e_s", round(r.latencySeconds()));
                entry.put("output_tokens", r.outputTokens());
                entry.put("prompt_tokens", r.promptTokens() < 0 ? null : r.promptTokens());
                requests.add(entry);
            }
            root.put("requests", requests);
        }
        return root;
    }

    private static String verdict(RunReport report) {
        if (report.summary().succeeded() == 0) {
            return "FAIL (no request succeeded)";
        }
        long failed = report.failedSlos();
        if (failed > 0) {
            return "FAIL (" + failed + " of " + report.slos().size() + " SLOs breached)";
        }
        return report.slos().isEmpty() ? "PASS (no SLOs given)" : "PASS";
    }

    private static String plannedText(Summary s) {
        return s.planned() >= 1_000_000 ? "duration-limited" : s.planned() + " requests";
    }

    private static String requestsText(Summary s) {
        return String.format(Locale.ROOT, "%d sent, %d ok, %d failed (%s) in %.2f s",
                s.sent(), s.succeeded(), s.failed(), Slo.format(Slo.Kind.RATIO, s.errorRate()), s.durationSeconds());
    }

    private static String throughputText(Summary s) {
        return String.format(Locale.ROOT, "%.2f req/s, %.1f output tokens/s (token counts from %s)",
                s.requestsPerSecond(), s.outputTokensPerSecond(), s.tokenCountSource());
    }

    private static String goodputText(Summary s) {
        return String.format(Locale.ROOT, "%s of requests met %s (%.2f req/s)",
                Slo.format(Slo.Kind.RATIO, s.goodputRatio()), s.goodput().expression(), s.goodputPerSecond());
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append(String.format(Locale.ROOT, "%-11s%s%n", label, value));
    }

    private static String millis(double seconds) {
        return Double.isNaN(seconds) ? "n/a" : String.format(Locale.ROOT, "%.1f", seconds * 1000);
    }

    private static Double round(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
