package com.telecombridge.loadtest;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the subscriber generator. A load run is only meaningful if every request is
 * a normal success, so the generated numbers must never collide with the simulator's
 * failure hooks; an earlier version did, and one request in 8 000 came back 504.
 */
class LoadTestRunnerTest {

    @Test
    void neverGeneratesANumberEndingInATestHookSuffix() {
        for (long n = 0; n < 200_000; n++) {
            String msisdn = LoadTestRunner.msisdn(n);
            assertThat(msisdn)
                    .as("request %d must not hit a simulator test hook", n)
                    .doesNotEndWith("0000")
                    .doesNotEndWith("9999")
                    .doesNotEndWith("5555");
        }
    }

    @Test
    void alwaysProducesAValidMsisdnForTheEndpointsValidation() {
        for (long n = 0; n < 50_000; n++) {
            assertThat(LoadTestRunner.msisdn(n)).matches("\\d{5,15}");
        }
    }

    @Test
    void rotatesThroughManyDistinctSubscribersBeforeRepeating() {
        Set<String> seen = new HashSet<>();
        for (long n = 0; n < 20_000; n++) {
            seen.add(LoadTestRunner.msisdn(n));
        }
        assertThat(seen).hasSize(20_000);
    }

    @Test
    void parsesCommandLineOptionsAndDerivesTheActuatorBase() {
        LoadTestConfig config = LoadTestConfig.fromArgs(new String[] {
                "--url=http://host:9000/api/v1/charge", "--tps=250", "--total=1000", "--p95-limit-ms=50"});

        assertThat(config.url()).isEqualTo("http://host:9000/api/v1/charge");
        assertThat(config.tps()).isEqualTo(250);
        assertThat(config.total()).isEqualTo(1000);
        assertThat(config.p95LimitMs()).isEqualTo(50);
        assertThat(config.actuatorUrl()).isEqualTo("http://host:9000/actuator");
    }
}
