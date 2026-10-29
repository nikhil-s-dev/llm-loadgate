package io.github.nikhilucumberlands.loadgate.load;

import io.github.nikhilucumberlands.loadgate.config.LoadConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives load with one virtual thread per in-flight request.
 *
 * <ul>
 *   <li><b>Closed loop</b> ({@code --concurrency N}): N workers each send a request, wait for the
 *       stream to finish, then send the next. Models a fixed pool of users or an upstream service
 *       with N connections.</li>
 *   <li><b>Open loop</b> ({@code --rate R}): requests arrive as a Poisson process at R per second
 *       whether or not earlier ones finished. Models independent users and is the right choice
 *       for finding the rate where queueing makes latency climb.</li>
 * </ul>
 */
public final class LoadRunner {

    /** Called after each measured request completes. May be invoked from many threads. */
    @FunctionalInterface
    public interface Progress {
        void completed(RequestResult result, int done, int planned);
    }

    private final LoadConfig config;
    private final RequestSender sender;
    private final PromptSource prompts;
    private final Progress progress;

    public LoadRunner(LoadConfig config, RequestSender sender, PromptSource prompts, Progress progress) {
        this.config = config;
        this.sender = sender;
        this.prompts = prompts;
        this.progress = progress;
    }

    public RunResult run() throws InterruptedException {
        if (config.warmupRequests() > 0) {
            int warmupWorkers = Math.min(config.warmupRequests(),
                    config.openLoop() && config.maxConcurrency() != null ? config.maxConcurrency() : config.concurrency());
            // Warm-up prompts come after the measured ones so the measured run does not reuse them.
            closedLoop(config.warmupRequests(), warmupWorkers, config.numRequests(), null, null);
        }
        long start = System.nanoTime();
        Long deadline = config.durationSeconds() == null ? null : start + (long) (config.durationSeconds() * 1e9);
        List<RequestResult> results = config.openLoop()
                ? openLoop(config.numRequests(), deadline)
                : closedLoop(config.numRequests(), config.concurrency(), 0, deadline, progress);
        double elapsed = (System.nanoTime() - start) / 1e9;
        results.sort(Comparator.comparingInt(RequestResult::index));
        return new RunResult(results, elapsed, config.numRequests());
    }

    private List<RequestResult> closedLoop(int planned, int workers, int indexOffset, Long deadline,
                                           Progress listener) throws InterruptedException {
        Queue<RequestResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < workers; w++) {
                executor.execute(() -> {
                    while (true) {
                        int i = next.getAndIncrement();
                        if (i >= planned || expired(deadline)) {
                            return;
                        }
                        RequestResult result = sendSafely(indexOffset + i);
                        results.add(result);
                        if (listener != null) {
                            listener.completed(result, done.incrementAndGet(), planned);
                        }
                    }
                });
            }
        }
        if (Thread.interrupted()) {
            throw new InterruptedException("load run interrupted");
        }
        return new ArrayList<>(results);
    }

    private List<RequestResult> openLoop(int planned, Long deadline) throws InterruptedException {
        Queue<RequestResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger done = new AtomicInteger();
        Semaphore slots = config.maxConcurrency() == null ? null : new Semaphore(config.maxConcurrency());
        Random arrivals = new Random(config.seed());
        double rate = config.requestRate();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            double offsetSeconds = 0;
            for (int i = 0; i < planned; i++) {
                long due = start + (long) (offsetSeconds * 1e9);
                if (deadline != null && due - deadline >= 0) {
                    break;
                }
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(Duration.ofNanos(wait));
                }
                int index = i;
                executor.execute(() -> {
                    if (slots != null) {
                        slots.acquireUninterruptibly();
                    }
                    try {
                        RequestResult result = sendSafely(index);
                        results.add(result);
                        if (progress != null) {
                            progress.completed(result, done.incrementAndGet(), planned);
                        }
                    } finally {
                        if (slots != null) {
                            slots.release();
                        }
                    }
                });
                // Exponential gaps between arrivals give a Poisson process with the target mean rate.
                offsetSeconds += -Math.log(1.0 - arrivals.nextDouble()) / rate;
            }
        }
        return new ArrayList<>(results);
    }

    private RequestResult sendSafely(int index) {
        long start = System.nanoTime();
        try {
            return sender.send(index, prompts.get(index));
        } catch (RuntimeException e) {
            return RequestResult.failure(index, start, "client error: " + e);
        }
    }

    private static boolean expired(Long deadline) {
        return deadline != null && System.nanoTime() - deadline >= 0;
    }
}
