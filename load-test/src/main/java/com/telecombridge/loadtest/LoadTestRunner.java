package com.telecombridge.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Open-model load generator: a scheduler fires one request every {@code 1/tps} seconds
 * regardless of how long earlier requests take, which is how real traffic behaves and
 * is the honest way to measure p95 under a fixed TPS.
 *
 * <p>Latency is recorded in an HdrHistogram (microsecond resolution, 3 significant
 * digits), progress is printed every few seconds along with the gateway's live heap
 * size read from Actuator, and a final report states PASS or FAIL against the
 * challenge constraints: p95 below the limit, zero errors.
 *
 * <pre>
 * java -jar load-test.jar --tps=100 --total=500000
 * </pre>
 */
public final class LoadTestRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestRunner.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LoadTestConfig config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private static final Pattern LATENCY_FIELD = Pattern.compile("\"latencyMs\":(\\d+)");

    private final Recorder intervalRecorder = new Recorder(TimeUnit.MINUTES.toMicros(1), 3);
    private final Histogram total = new Histogram(TimeUnit.MINUTES.toMicros(1), 3);
    /** The gateway's own CCR-to-CCA time, parsed from every response body (millisecond resolution). */
    private final Histogram internal = new Histogram(TimeUnit.MINUTES.toMillis(1), 3);
    /**
     * Per request: end-to-end microseconds minus the gateway's reported CCR-to-CCA time.
     * This is everything the gateway and the client add on top of the peer: HTTP parsing,
     * JSON, the client's own socket handling. Measured per request, not by subtracting
     * two percentiles, so it is a real distribution.
     */
    private final Histogram overhead = new Histogram(TimeUnit.SECONDS.toMicros(10), 3);

    private final AtomicLong sent = new AtomicLong();
    /** Slots dropped because the process was paused; see {@link Pacer}. */
    private final AtomicLong skippedSlots = new AtomicLong();
    private final AtomicLong longestStallMs = new AtomicLong();
    /** Slots dropped because too many requests were already in flight. */
    private final AtomicLong skippedOverload = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong http200 = new AtomicLong();
    private final AtomicLong http422 = new AtomicLong();
    private final AtomicLong http5xx = new AtomicLong();
    private final AtomicLong httpOther = new AtomicLong();
    private final AtomicLong transportErrors = new AtomicLong();
    private final AtomicLong intervalCompleted = new AtomicLong();
    private final AtomicLong intervalErrors = new AtomicLong();

    private final List<Double> heapSamplesMb = new ArrayList<>();

    private LoadTestRunner(LoadTestConfig config) {
        this.config = config;
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "load-http");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(pool)
                .build();
    }

    public static void main(String[] args) throws Exception {
        LoadTestConfig config = LoadTestConfig.fromArgs(args);
        boolean passed = new LoadTestRunner(config).run();
        System.exit(passed ? 0 : 1);
    }

    private boolean run() throws Exception {
        long grandTotal = config.warmup() + config.total();
        long periodNanos = 1_000_000_000L / config.tps();
        log.info("Target {} at {} TPS: {} warm-up + {} measured requests ({} min), timeout {} ms, p95 limit {} ms",
                config.url(), config.tps(), config.warmup(), config.total(),
                config.total() / config.tps() / 60, config.timeoutMs(), config.p95LimitMs());

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "load-scheduler");
            t.setDaemon(true);
            return t;
        });

        long startNanos = System.nanoTime();
        Pacer pacer = new Pacer(startNanos, periodNanos, Pacer.DEFAULT_MAX_LAG_NANOS);
        // Never let a stall turn into a stampede, and never let the client outrun the server.
        long maxInFlight = Math.max(1000L, config.tps() * 20L);
        AtomicLong tick = new AtomicLong();

        scheduler.scheduleAtFixedRate(() -> {
            long index = tick.getAndIncrement();
            if (sent.get() >= grandTotal) {
                return;
            }
            long now = System.nanoTime();
            if (!pacer.shouldFire(index, now)) {
                // A slot missed while the process was paused. Drop it rather than replay it.
                long lagMs = TimeUnit.NANOSECONDS.toMillis(pacer.lagNanos(index, now));
                skippedSlots.incrementAndGet();
                longestStallMs.accumulateAndGet(lagMs, Math::max);
                return;
            }
            if (sent.get() - completed.get() > maxInFlight) {
                skippedOverload.incrementAndGet();
                return;
            }
            long n = sent.getAndIncrement();
            if (n >= grandTotal) {
                sent.decrementAndGet();
                return;
            }
            fire(n, n >= config.warmup());
        }, 0, periodNanos, TimeUnit.NANOSECONDS);

        scheduler.scheduleAtFixedRate(() -> progress(startNanos), config.progressSeconds(), config.progressSeconds(),
                TimeUnit.SECONDS);

        // Wait for every request to be sent and answered (or to fail), with a grace period at the end.
        while (sent.get() < grandTotal) {
            Thread.sleep(200);
        }
        long drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs() + 2000);
        while (completed.get() < grandTotal && System.nanoTime() < drainDeadline) {
            Thread.sleep(50);
        }
        scheduler.shutdownNow();
        long elapsedNanos = System.nanoTime() - startNanos;

        String report = report(elapsedNanos);
        System.out.println(report);
        Files.writeString(Path.of(config.reportFile()), report, StandardCharsets.UTF_8);
        log.info("Report written to {}", config.reportFile());
        return report.contains("RESULT: PASS");
    }

    private void fire(long n, boolean measured) {
        String msisdn = msisdn(n);
        String body = "{\"msisdn\":\"" + msisdn + "\",\"requestType\":\"INITIAL\",\"requestNumber\":0,"
                + "\"requestedOctets\":1048576}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long start = System.nanoTime();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
                    completed.incrementAndGet();
                    intervalCompleted.incrementAndGet();
                    if (!measured) {
                        return;
                    }
                    if (error != null) {
                        transportErrors.incrementAndGet();
                        intervalErrors.incrementAndGet();
                        return;
                    }
                    intervalRecorder.recordValue(micros);
                    synchronized (total) {
                        total.recordValue(micros);
                    }
                    int status = response.statusCode();
                    if (status == 200) {
                        http200.incrementAndGet();
                        Matcher m = LATENCY_FIELD.matcher(response.body());
                        if (m.find()) {
                            long internalMs = Long.parseLong(m.group(1));
                            synchronized (internal) {
                                internal.recordValue(internalMs);
                            }
                            synchronized (overhead) {
                                overhead.recordValue(Math.max(0, micros - internalMs * 1000));
                            }
                        }
                    } else if (status == 422) {
                        http422.incrementAndGet();
                    } else if (status >= 500) {
                        http5xx.incrementAndGet();
                        intervalErrors.incrementAndGet();
                    } else {
                        httpOther.incrementAndGet();
                        intervalErrors.incrementAndGet();
                    }
                });
    }

    /**
     * Rotates through 799 900 distinct subscribers, none of which ends in one of the
     * simulator's test-hook suffixes (0000 user-unknown, 9999 credit-limit, 5555 never
     * answered), so every request in a load run is a normal success.
     *
     * <p>The suffix walks 1000..8999 with 5555 skipped; 0000 and 9999 fall outside the
     * range already. Getting this wrong is not harmless: an earlier version let the
     * sequence land on 5555, which the simulator black-holes, so one request in every
     * 8 000 sat until the 2 s gateway timeout and came back 504. That both polluted the
     * tail of the histogram and failed the "zero errors" constraint.
     */
    static String msisdn(long n) {
        long suffix = 1000 + (n % 7999);            // 1000..8999 minus one slot
        if (suffix >= 5555) {
            suffix++;                               // skip 5555
        }
        long block = (n / 7999) % 100;              // 00..99
        return String.format("9198%02d5%04d", block, suffix);
    }

    private void progress(long startNanos) {
        Histogram interval = intervalRecorder.getIntervalHistogram();
        long done = intervalCompleted.getAndSet(0);
        long errs = intervalErrors.getAndSet(0);
        double elapsedSec = (System.nanoTime() - startNanos) / 1e9;
        double intervalTps = done / (double) config.progressSeconds();
        String heap = sampleHeap();
        log.info("t={}s sent={} done={} inflight={} | last {}s: {} req/s p50={} p95={} p99={} max={} ms errors={} | gateway heap {}",
                (long) elapsedSec, sent.get(), completed.get(), sent.get() - completed.get(),
                config.progressSeconds(), fmt(intervalTps),
                ms(interval.getValueAtPercentile(50)), ms(interval.getValueAtPercentile(95)),
                ms(interval.getValueAtPercentile(99)), ms(interval.getMaxValue()), errs, heap);
    }

    private String sampleHeap() {
        if (config.actuatorUrl() == null || config.actuatorUrl().isBlank()) {
            return "n/a";
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(config.actuatorUrl() + "/metrics/jvm.memory.used?tag=area:heap"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "HTTP " + resp.statusCode();
            }
            JsonNode node = json.readTree(resp.body());
            double bytes = node.path("measurements").get(0).path("value").asDouble();
            double mb = bytes / (1024 * 1024);
            synchronized (heapSamplesMb) {
                heapSamplesMb.add(mb);
            }
            return fmt(mb) + " MB";
        } catch (IOException | InterruptedException | RuntimeException e) {
            return "unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Reads the gateway's own view from {@code /actuator/prometheus}: the CCR-to-CCA
     * timer percentiles (which include the simulator's delay but exclude HTTP handling)
     * and the pending-request gauge, which must be back at zero when the run ends.
     */
    private String gatewayInternals() {
        if (config.actuatorUrl() == null || config.actuatorUrl().isBlank()) {
            return "  (actuator sampling disabled)\n";
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.actuatorUrl() + "/prometheus"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "  prometheus endpoint returned HTTP " + resp.statusCode() + "\n";
            }
            StringBuilder sb = new StringBuilder();
            for (String line : resp.body().split("\n")) {
                if (line.startsWith("diameter_request_seconds{") && line.contains("quantile=")) {
                    String q = line.replaceAll(".*quantile=\"([0-9.]+)\".*", "$1");
                    long percentile = Math.round(Double.parseDouble(q) * 100);
                    double seconds = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                    sb.append("  CCR->CCA p").append(percentile).append(" = ")
                            .append(fmt(seconds * 1000)).append(" ms\n");
                } else if (line.startsWith("diameter_request_seconds_count")) {
                    sb.append("  CCR->CCA count = ").append(line.substring(line.lastIndexOf(' ') + 1)).append('\n');
                } else if (line.startsWith("diameter_request_timeouts_total")) {
                    sb.append("  timeouts = ").append(line.substring(line.lastIndexOf(' ') + 1)).append('\n');
                } else if (line.startsWith("diameter_pending_requests")) {
                    sb.append("  pending now = ").append(line.substring(line.lastIndexOf(' ') + 1)).append('\n');
                } else if (line.startsWith("diameter_answers_unmatched_total")) {
                    sb.append("  unmatched answers = ").append(line.substring(line.lastIndexOf(' ') + 1)).append('\n');
                }
            }
            return sb.length() == 0 ? "  (no diameter_* series found)\n" : sb.toString();
        } catch (IOException | InterruptedException | RuntimeException e) {
            return "  unavailable (" + e.getClass().getSimpleName() + ")\n";
        }
    }

    private String report(long elapsedNanos) {
        Histogram h;
        synchronized (total) {
            h = total.copy();
        }
        double elapsedSec = elapsedNanos / 1e9;
        long measuredCompleted = h.getTotalCount() + transportErrors.get();
        long errors = http5xx.get() + httpOther.get() + transportErrors.get();
        double achievedTps = measuredCompleted / Math.max(1e-9, elapsedSec - (config.warmup() / (double) config.tps()));
        double p95 = ms(h.getValueAtPercentile(95));
        // A run that paused did not actually offer the requested rate for its whole duration,
        // so its percentiles describe something other than the test that was asked for.
        boolean continuous = skippedSlots.get() == 0 && skippedOverload.get() == 0;
        boolean pass = p95 < config.p95LimitMs() && errors == 0 && measuredCompleted == config.total() && continuous;

        double heapMin = Double.NaN;
        double heapMax = Double.NaN;
        double heapFirst = Double.NaN;
        double heapLast = Double.NaN;
        synchronized (heapSamplesMb) {
            if (!heapSamplesMb.isEmpty()) {
                heapMin = heapSamplesMb.stream().mapToDouble(d -> d).min().orElse(Double.NaN);
                heapMax = heapSamplesMb.stream().mapToDouble(d -> d).max().orElse(Double.NaN);
                heapFirst = heapSamplesMb.get(0);
                heapLast = heapSamplesMb.get(heapSamplesMb.size() - 1);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("==================== Telecom-Bridge load test report ====================\n");
        sb.append("Finished        : ").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("Endpoint        : ").append(config.url()).append('\n');
        sb.append("Target          : ").append(config.tps()).append(" TPS, ").append(config.total())
                .append(" measured requests (+").append(config.warmup()).append(" warm-up)\n");
        sb.append("Elapsed         : ").append(fmt(elapsedSec)).append(" s\n");
        sb.append("Achieved rate   : ").append(fmt(achievedTps)).append(" req/s\n");
        sb.append('\n');
        sb.append("Completed       : ").append(measuredCompleted).append(" / ").append(config.total()).append('\n');
        sb.append("  HTTP 200      : ").append(http200.get()).append('\n');
        sb.append("  HTTP 422      : ").append(http422.get()).append('\n');
        sb.append("  HTTP 5xx      : ").append(http5xx.get()).append('\n');
        sb.append("  HTTP other    : ").append(httpOther.get()).append('\n');
        sb.append("  transport err : ").append(transportErrors.get()).append('\n');
        sb.append('\n');
        sb.append("Latency (ms)    : p50=").append(fmt(ms(h.getValueAtPercentile(50))))
                .append("  p90=").append(fmt(ms(h.getValueAtPercentile(90))))
                .append("  p95=").append(fmt(p95))
                .append("  p99=").append(fmt(ms(h.getValueAtPercentile(99))))
                .append("  p99.9=").append(fmt(ms(h.getValueAtPercentile(99.9))))
                .append("  max=").append(fmt(ms(h.getMaxValue())))
                .append("  mean=").append(fmt(h.getMean() / 1000.0)).append('\n');
        Histogram in;
        synchronized (internal) {
            in = internal.copy();
        }
        Histogram oh;
        synchronized (overhead) {
            oh = overhead.copy();
        }
        double internalP95 = in.getTotalCount() > 0 ? in.getValueAtPercentile(95) : Double.NaN;
        if (in.getTotalCount() > 0) {
            sb.append("Breakdown of the end-to-end figure above, per request:\n");
            sb.append("  peer round trip (CCR->CCA, dominated by the simulator's own delay)\n");
            sb.append("                : p50=").append(fmt(in.getValueAtPercentile(50)))
                    .append("  p90=").append(fmt(in.getValueAtPercentile(90)))
                    .append("  p95=").append(fmt(internalP95))
                    .append("  p99=").append(fmt(in.getValueAtPercentile(99)))
                    .append("  max=").append(fmt(in.getMaxValue()))
                    .append("  mean=").append(fmt(in.getMean())).append(" ms\n");
            sb.append("  gateway + client overhead on top of that (HTTP, JSON, sockets)\n");
            sb.append("                : p50=").append(fmt(ms(oh.getValueAtPercentile(50))))
                    .append("  p90=").append(fmt(ms(oh.getValueAtPercentile(90))))
                    .append("  p95=").append(fmt(ms(oh.getValueAtPercentile(95))))
                    .append("  p99=").append(fmt(ms(oh.getValueAtPercentile(99))))
                    .append("  max=").append(fmt(ms(oh.getMaxValue())))
                    .append("  mean=").append(fmt(oh.getMean() / 1000.0)).append(" ms\n");
        }
        sb.append('\n');
        sb.append("Gateway heap    : ");
        if (Double.isNaN(heapMin)) {
            sb.append("not sampled\n");
        } else {
            sb.append("first=").append(fmt(heapFirst)).append(" MB  last=").append(fmt(heapLast))
                    .append(" MB  min=").append(fmt(heapMin)).append(" MB  max=").append(fmt(heapMax))
                    .append(" MB  (").append(heapSamplesMb.size()).append(" samples)\n");
        }
        sb.append("Gateway internals (from /actuator/prometheus, sliding-window estimates):\n");
        sb.append(gatewayInternals());
        sb.append('\n');
        sb.append("Constraint p95 < ").append(config.p95LimitMs()).append(" ms : ")
                .append(p95 < config.p95LimitMs() ? "OK" : "VIOLATED").append('\n');
        if (p95 >= config.p95LimitMs() && !Double.isNaN(internalP95) && internalP95 >= config.p95LimitMs() * 0.9) {
            sb.append("  Note: the peer round trip alone is p95=").append(fmt(internalP95))
                    .append(" ms. A simulator delay drawn uniformly from [50, 100] ms has a p95 of\n")
                    .append("  97.5 ms by construction, so no gateway can bring the end-to-end p95 under 100 ms\n")
                    .append("  against that peer. Re-run the simulator with --max-delay=80 to measure the\n")
                    .append("  gateway against a peer that leaves headroom; the overhead row above is the\n")
                    .append("  part of the latency this service is actually responsible for.\n");
        }
        sb.append("Constraint errors == 0  : ").append(errors == 0 ? "OK" : "VIOLATED (" + errors + ")").append('\n');
        sb.append("Constraint all completed: ").append(measuredCompleted == config.total() ? "OK" : "VIOLATED").append('\n');
        sb.append("Constraint run continuous: ").append(continuous ? "OK" : "VIOLATED").append('\n');
        if (skippedSlots.get() > 0) {
            sb.append("  The generator was paused: ").append(skippedSlots.get())
                    .append(" scheduled slots were dropped and the longest gap was ")
                    .append(fmt(longestStallMs.get() / 1000.0)).append(" s. Something suspended this\n")
                    .append("  process mid-run (an idle-sleep timer is the usual culprit on a laptop). The slots were\n")
                    .append("  dropped on purpose rather than replayed in a burst, so the numbers above are still\n")
                    .append("  meaningful for the requests that were sent, but this was not a continuous run at ")
                    .append(config.tps()).append(" TPS.\n");
        }
        if (skippedOverload.get() > 0) {
            sb.append("  Overload guard tripped ").append(skippedOverload.get())
                    .append(" times: more than ").append(Math.max(1000L, config.tps() * 20L))
                    .append(" requests were in flight, so the generator stopped adding load.\n");
        }
        sb.append('\n');
        sb.append("RESULT: ").append(pass ? "PASS" : "FAIL").append('\n');
        sb.append("=========================================================================\n");
        return sb.toString();
    }

    private static double ms(long micros) {
        return micros / 1000.0;
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
