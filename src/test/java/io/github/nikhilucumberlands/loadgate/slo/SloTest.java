package io.github.nikhilucumberlands.loadgate.slo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SloTest {

    @ParameterizedTest
    @CsvSource({
        "'ttft_p95<500ms', ttft_p95, 0.5",
        "'tpot_p99 <= 0.05s', tpot_p99, 0.05",
        "'error_rate<1%', error_rate, 0.01",
        "'goodput_ratio>=0.95', goodput_ratio, 0.95",
        "'output_tokens_per_s>200', output_tokens_per_s, 200",
        "'E2E_MAX<2S', e2e_max, 2",
    })
    void parsesThresholdsIntoBaseUnits(String expression, String metric, double threshold) {
        Slo slo = Slo.parse(expression);
        assertEquals(metric, slo.metric());
        assertEquals(threshold, slo.threshold(), 1e-12);
    }

    @Test
    void rejectsLatencyWithoutUnit() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Slo.parse("ttft_p95<500"));
        assertTrue(e.getMessage().contains("ms or s"));
    }

    @Test
    void rejectsUnknownMetricsAndWrongUnits() {
        assertThrows(IllegalArgumentException.class, () -> Slo.parse("ttft_p42<1s"));
        assertThrows(IllegalArgumentException.class, () -> Slo.parse("error_rate<5ms"));
        assertThrows(IllegalArgumentException.class, () -> Slo.parse("requests_per_s>5%"));
        assertThrows(IllegalArgumentException.class, () -> Slo.parse("ttft_p95 = 1s"));
    }

    @Test
    void evaluatesAgainstMetrics() {
        Map<String, Double> metrics = Map.of("ttft_p95", 0.42, "error_rate", 0.02);
        assertTrue(Slo.parse("ttft_p95<500ms").evaluate(metrics).passed());
        assertFalse(Slo.parse("ttft_p95<400ms").evaluate(metrics).passed());
        assertFalse(Slo.parse("error_rate<=1%").evaluate(metrics).passed());
        SloResult noData = Slo.parse("goodput_ratio>0.9").evaluate(metrics);
        assertFalse(noData.passed());
        assertEquals("n/a", noData.formattedActual());
    }

    @Test
    void formatsByKind() {
        assertEquals("420.0 ms", Slo.format(Slo.Kind.LATENCY, 0.42));
        assertEquals("2.50%", Slo.format(Slo.Kind.RATIO, 0.025));
        assertEquals("12.00/s", Slo.format(Slo.Kind.RATE, 12));
    }
}
