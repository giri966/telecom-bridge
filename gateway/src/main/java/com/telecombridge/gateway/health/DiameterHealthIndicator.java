package com.telecombridge.gateway.health;

import com.telecombridge.gateway.diameter.DiameterClient;
import com.telecombridge.gateway.diameter.PeerState;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Surfaces the peer state under {@code /actuator/health} as the "diameter" component. */
@Component("diameter")
public class DiameterHealthIndicator implements HealthIndicator {

    private final DiameterClient client;

    public DiameterHealthIndicator(DiameterClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        PeerState state = client.state();
        Health.Builder builder = state == PeerState.OPEN ? Health.up() : Health.down();
        return builder
                .withDetail("state", state.name())
                .withDetail("peer", client.peerHost())
                .withDetail("pendingRequests", client.pendingCount())
                .build();
    }
}
