package com.telecombridge.loadtest;

import java.util.HashMap;
import java.util.Map;

/**
 * Command-line options, all {@code --key=value}.
 *
 * @param url               charge endpoint
 * @param tps               target requests per second (fixed rate, open model)
 * @param total             requests to send in the measured run
 * @param warmup            extra requests sent first and excluded from the statistics
 * @param timeoutMs         per-request HTTP timeout; anything slower counts as an error
 * @param progressSeconds   interval between progress lines
 * @param reportFile        where the final report is written (in addition to stdout)
 * @param actuatorUrl       gateway metrics base, used to sample JVM heap; blank disables sampling
 * @param p95LimitMs        pass/fail threshold for p95 latency
 */
public record LoadTestConfig(
        String url,
        int tps,
        long total,
        long warmup,
        long timeoutMs,
        int progressSeconds,
        String reportFile,
        String actuatorUrl,
        long p95LimitMs) {

    public static LoadTestConfig fromArgs(String[] args) {
        Map<String, String> kv = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --key=value, got: " + arg);
            }
            int eq = arg.indexOf('=');
            kv.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        String url = kv.getOrDefault("url", "http://localhost:8080/api/v1/charge");
        String actuator = kv.getOrDefault("actuator", deriveActuator(url));
        return new LoadTestConfig(
                url,
                Integer.parseInt(kv.getOrDefault("tps", "100")),
                Long.parseLong(kv.getOrDefault("total", "500000")),
                Long.parseLong(kv.getOrDefault("warmup", "1000")),
                Long.parseLong(kv.getOrDefault("timeout-ms", "5000")),
                Integer.parseInt(kv.getOrDefault("progress-seconds", "10")),
                kv.getOrDefault("report", "load-test-report-" + System.currentTimeMillis() + ".txt"),
                actuator,
                Long.parseLong(kv.getOrDefault("p95-limit-ms", "100")));
    }

    private static String deriveActuator(String url) {
        int idx = url.indexOf("/api/");
        return idx > 0 ? url.substring(0, idx) + "/actuator" : "";
    }
}
