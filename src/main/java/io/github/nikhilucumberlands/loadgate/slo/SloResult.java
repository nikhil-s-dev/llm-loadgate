package io.github.nikhilucumberlands.loadgate.slo;

/**
 * Outcome of one SLO check.
 *
 * @param actual measured value in base units (NaN when there was no data)
 * @param note   why the check could not be evaluated, or null
 */
public record SloResult(Slo slo, double actual, boolean passed, String note) {

    public String formattedActual() {
        return Slo.format(slo.kind(), actual);
    }
}
