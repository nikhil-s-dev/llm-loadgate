package io.github.nikhilucumberlands.loadgate.load;

import java.util.List;

/**
 * Results of the measured phase.
 *
 * @param results          one entry per request, ordered by index
 * @param durationSeconds  wall clock from the first send until the last request finished
 * @param planned          requests the run intended to send
 */
public record RunResult(List<RequestResult> results, double durationSeconds, int planned) {

    public RunResult {
        results = List.copyOf(results);
    }
}
