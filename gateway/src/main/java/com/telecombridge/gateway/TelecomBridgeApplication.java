package com.telecombridge.gateway;

import com.telecombridge.gateway.config.DiameterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * REST-to-Diameter gateway. Boots a WebFlux server (non-blocking HTTP) and one
 * persistent Diameter peer connection managed by {@link com.telecombridge.gateway.diameter.DiameterClient}.
 */
@SpringBootApplication
@EnableConfigurationProperties(DiameterProperties.class)
public class TelecomBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelecomBridgeApplication.class, args);
    }
}
