package io.github.nikhilucumberlands.loadgate.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nikhilucumberlands.loadgate.load.RequestResult;
import io.github.nikhilucumberlands.loadgate.load.RunResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private static RequestResult ok(int index, double ttft, double e2e, int tokens) {
        return new RequestResult(index, true, 200, null, 0, ttft, e2e, tokens, 10, true, new double[] {0.01, 0.02});
    }

    private static RequestResult failed(int index, String error) {
        return new RequestResult(index, false, 503, error, 0, Double.NaN, 0.05, 0, -1, false, new double[0]);
    }

    @Test
    void percentilesInterpolateLikeNumpy() {
        double[] sorted = {1, 2, 3, 4};
        assertEquals(2.5, Stats.percentile(sorted, 50), 1e-12);
        assertEquals(3.85, Stats.percentile(sorted, 95), 1e-12);
        assertEquals(1.0, Stats.percentile(sorted, 0), 1e-12);
        assertEquals(4.0, Stats.percentile(sorted, 100), 1e-12);
        assertTrue(Double.isNaN(Stats.percentile(new double[0], 50)));
    }

    @Test
    void distributionIgnoresMissingValues() {
        Distribution d = Distribution.of(new double[] {0.3, Double.NaN, 0.1, 0.2});
        assertEquals(3, d.count());
        assertEquals(0.2, d.p50(), 1e-12);
        assertEquals(0.3, d.max(), 1e-12);
    }

    @Test
    void tpotExcludesTheFirstToken() {
        RequestResult r = ok(0, 0.2, 1.2, 11);
        assertEquals(0.1, r.tpotSeconds(), 1e-12);
        assertTrue(Double.isNaN(ok(1, 0.2, 0.2, 1).tpotSeconds()));
    }

    @Test
    void summaryCountsFailuresSeparatelyFromLatency() {
        RunResult run = new RunResult(List.of(
                ok(0, 0.1, 1.0, 10),
                ok(1, 0.3, 2.0, 30),
                failed(2, "HTTP 503: overloaded"),
                failed(3, "HTTP 503: overloaded")), 2.0, 4);
        Summary s = Summary.of(run, null);
        assertEquals(2, s.succeeded());
        assertEquals(0.5, s.errorRate(), 1e-12);
        assertEquals(1.0, s.requestsPerSecond(), 1e-12);
        assertEquals(20.0, s.outputTokensPerSecond(), 1e-12);
        assertEquals(10.0, s.promptTokensPerSecond(), 1e-12);
        assertEquals(2, s.ttft().count());
        assertEquals(0.2, s.ttft().mean(), 1e-12);
        assertEquals(4, s.itl().count());
        assertEquals("server usage", s.tokenCountSource());
        assertEquals(2, s.topErrors().get("HTTP 503: overloaded"));
        assertEquals(0.3, s.metrics().get("ttft_max"), 1e-12);
    }

    @Test
    void goodputRequiresEveryTarget() {
        Goodput goodput = Goodput.parse("ttft<250ms, tpot<=0.1s");
        RunResult run = new RunResult(List.of(
                ok(0, 0.1, 1.0, 10),   // tpot 0.1 -> meets <=0.1s
                ok(1, 0.3, 1.0, 10),   // ttft too slow
                failed(2, "timeout")), 1.0, 3);
        Summary s = Summary.of(run, goodput);
        assertEquals(1.0 / 3, s.goodputRatio(), 1e-12);
        assertEquals(1.0, s.goodputPerSecond(), 1e-12);
        assertFalse(goodput.isGood(failed(3, "x")));
    }

    @Test
    void goodputRejectsBadSyntax() {
        assertThrows(IllegalArgumentException.class, () -> Goodput.parse("ttft<500"));
        assertThrows(IllegalArgumentException.class, () -> Goodput.parse("latency<5s"));
        assertThrows(IllegalArgumentException.class, () -> Goodput.parse("ttft>5s"));
    }
}
