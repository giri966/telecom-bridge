package com.telecombridge.simulator;

import java.util.HashMap;
import java.util.Map;

/**
 * Simulator settings. Every value can be given as {@code --name=value} on the command line
 * or as an environment variable ({@code SIM_PORT}, {@code SIM_MIN_DELAY_MS}, ...); the
 * command line wins.
 *
 * @param port                 TCP listen port; 0 picks a free port (tests)
 * @param minDelayMs           lower bound of the simulated OCS processing delay per CCR
 * @param maxDelayMs           upper bound (inclusive) of that delay
 * @param originHost           DiameterIdentity advertised in Origin-Host
 * @param originRealm          realm advertised in Origin-Realm
 * @param productName          Product-Name in the CEA
 * @param defaultGrantOctets   quota granted when the CCR carries no Requested-Service-Unit
 * @param validityTimeSeconds  Validity-Time returned with every grant
 * @param workerThreads        Netty worker event-loop threads
 */
public record SimulatorConfig(
        int port,
        int minDelayMs,
        int maxDelayMs,
        String originHost,
        String originRealm,
        String productName,
        long defaultGrantOctets,
        long validityTimeSeconds,
        int workerThreads) {

    public static final int DEFAULT_PORT = 3868;

    public SimulatorConfig {
        if (minDelayMs < 0 || maxDelayMs < minDelayMs) {
            throw new IllegalArgumentException("Delay range must satisfy 0 <= min <= max, got "
                    + minDelayMs + ".." + maxDelayMs);
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1");
        }
    }

    public static SimulatorConfig defaults() {
        return new SimulatorConfig(DEFAULT_PORT, 50, 100,
                "ocs.telecom-bridge.local", "telecom-bridge.local", "DiameterSimulator",
                1_048_576L, 3600L, 4);
    }

    public SimulatorConfig withPort(int newPort) {
        return new SimulatorConfig(newPort, minDelayMs, maxDelayMs, originHost, originRealm, productName,
                defaultGrantOctets, validityTimeSeconds, workerThreads);
    }

    public SimulatorConfig withDelay(int min, int max) {
        return new SimulatorConfig(port, min, max, originHost, originRealm, productName,
                defaultGrantOctets, validityTimeSeconds, workerThreads);
    }

    /** Parses {@code --key=value} arguments over environment defaults. */
    public static SimulatorConfig fromArgs(String[] args) {
        Map<String, String> kv = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --key=value, got: " + arg);
            }
            int eq = arg.indexOf('=');
            kv.put(arg.substring(2, eq).trim(), arg.substring(eq + 1).trim());
        }
        SimulatorConfig d = defaults();
        return new SimulatorConfig(
                intValue(kv, "port", "SIM_PORT", d.port),
                intValue(kv, "min-delay", "SIM_MIN_DELAY_MS", d.minDelayMs),
                intValue(kv, "max-delay", "SIM_MAX_DELAY_MS", d.maxDelayMs),
                stringValue(kv, "origin-host", "SIM_ORIGIN_HOST", d.originHost),
                stringValue(kv, "origin-realm", "SIM_ORIGIN_REALM", d.originRealm),
                stringValue(kv, "product-name", "SIM_PRODUCT_NAME", d.productName),
                longValue(kv, "grant-octets", "SIM_GRANT_OCTETS", d.defaultGrantOctets),
                longValue(kv, "validity-time", "SIM_VALIDITY_TIME", d.validityTimeSeconds),
                intValue(kv, "worker-threads", "SIM_WORKER_THREADS", d.workerThreads));
    }

    private static String stringValue(Map<String, String> kv, String key, String env, String def) {
        String v = kv.get(key);
        if (v == null) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? def : v;
    }

    private static int intValue(Map<String, String> kv, String key, String env, int def) {
        return Integer.parseInt(stringValue(kv, key, env, Integer.toString(def)));
    }

    private static long longValue(Map<String, String> kv, String key, String env, long def) {
        return Long.parseLong(stringValue(kv, key, env, Long.toString(def)));
    }
}
