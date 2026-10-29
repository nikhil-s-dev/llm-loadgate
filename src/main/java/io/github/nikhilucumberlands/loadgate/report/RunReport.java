package io.github.nikhilucumberlands.loadgate.report;

import io.github.nikhilucumberlands.loadgate.config.LoadConfig;
import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.metrics.Summary;
import io.github.nikhilucumberlands.loadgate.slo.SloResult;

import java.time.Instant;
import java.util.List;

/**
 * Everything the report writers need about one run.
 *
 * @param requests per-request results, included in the JSON report on request
 */
public record RunReport(
        String version,
        Instant startedAt,
        LoadConfig config,
        String promptDescription,
        Summary summary,
        List<SloResult> slos,
        List<RequestResult> requests) {

    public RunReport {
        slos = List.copyOf(slos);
        requests = List.copyOf(requests);
    }

    /** A run passes when at least one request succeeded and every SLO held. */
    public boolean passed() {
        return summary.succeeded() > 0 && slos.stream().allMatch(SloResult::passed);
    }

    public long failedSlos() {
        return slos.stream().filter(s -> !s.passed()).count();
    }

    public String target() {
        return config.baseUrl() + config.endpoint().path();
    }
}
