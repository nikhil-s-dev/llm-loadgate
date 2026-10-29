package io.github.nikhilucumberlands.loadgate.load;

/**
 * Timing and outcome of one streamed request. Durations are in seconds; NaN means "not measured"
 * (for example TTFT of a request that failed before the first token).
 *
 * @param statusCode           HTTP status, or 0 when no response arrived
 * @param startNanos           {@link System#nanoTime()} when the request was sent
 * @param ttftSeconds          time to first token: send until the first non-empty content chunk
 * @param latencySeconds       end-to-end: send until the stream finished (or failed)
 * @param outputTokens         from server-reported usage when available, else content chunks
 * @param promptTokens         from server-reported usage, or -1 when unknown
 * @param usageReported        whether {@code outputTokens} came from the server's usage block
 * @param interTokenLatencies  gaps between consecutive content chunks
 */
public record RequestResult(
        int index,
        boolean ok,
        int statusCode,
        String error,
        long startNanos,
        double ttftSeconds,
        double latencySeconds,
        int outputTokens,
        int promptTokens,
        boolean usageReported,
        double[] interTokenLatencies) {

    /** Time per output token after the first: {@code (e2e - ttft) / (tokens - 1)}. */
    public double tpotSeconds() {
        if (!ok || Double.isNaN(ttftSeconds) || outputTokens < 2) {
            return Double.NaN;
        }
        return (latencySeconds - ttftSeconds) / (outputTokens - 1);
    }

    /** A request that failed on the client side before any response was processed. */
    public static RequestResult failure(int index, long startNanos, String error) {
        return new RequestResult(index, false, 0, error, startNanos, Double.NaN,
                (System.nanoTime() - startNanos) / 1e9, 0, -1, false, new double[0]);
    }
}
