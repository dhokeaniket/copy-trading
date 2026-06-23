package com.copytrading.config;

import com.copytrading.broker.PlatformBrokerConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Resolves the server's actual public egress IP at startup and stores it in
 * {@link PlatformBrokerConfig#setServerEgressIp(String)}.
 *
 * Detection order:
 *   1. EC2 IMDSv2 (token → public-ipv4) — instant, no external call
 *   2. api.ipify.org — plain-text public IP, works anywhere
 *
 * The resolved IP is used by:
 *   - Angel One: X-ClientPublicIP header (required to match the whitelisted IP on the SmartAPI app)
 *   - Groww:     IP whitelist guidance shown to users
 *   - Any broker that enforces server-side IP restrictions
 *
 * If brokers.server-egress-ip is already set in application properties, that value wins
 * and no detection is performed (useful for local dev overrides).
 */
@Component
public class ServerIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ServerIpResolver.class);

    private static final String IMDS_TOKEN_URL  = "http://169.254.169.254/latest/api/token";
    private static final String IMDS_IP_URL     = "http://169.254.169.254/latest/meta-data/public-ipv4";
    private static final String IPIFY_URL        = "https://api.ipify.org";

    private final PlatformBrokerConfig platformConfig;
    private final WebClient webClient;

    public ServerIpResolver(PlatformBrokerConfig platformConfig, WebClient.Builder builder) {
        this.platformConfig = platformConfig;
        this.webClient = builder.build();
    }

    @PostConstruct
    public void resolve() {
        // If already configured in application.properties/env, trust it — skip auto-detection
        if (platformConfig.getServerEgressIp() != null && !platformConfig.getServerEgressIp().isBlank()) {
            log.info("SERVER_IP_CONFIGURED value={} (skipping auto-detect)", platformConfig.getServerEgressIp());
            return;
        }

        // Try EC2 IMDSv2 first (instant when running on EC2, fast-fails with timeout off EC2)
        webClient.put()
                .uri(IMDS_TOKEN_URL)
                .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(2))
                .flatMap(token -> webClient.get()
                        .uri(IMDS_IP_URL)
                        .header("X-aws-ec2-metadata-token", token)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(2)))
                .map(String::trim)
                .doOnNext(ip -> {
                    platformConfig.setServerEgressIp(ip);
                    log.info("SERVER_IP_RESOLVED source=EC2_IMDS ip={}", ip);
                })
                .onErrorResume(e -> {
                    // Not on EC2 or IMDS unavailable — fall back to ipify
                    log.debug("SERVER_IP_IMDS_UNAVAILABLE reason={} — trying ipify", e.getMessage());
                    return webClient.get()
                            .uri(IPIFY_URL)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(5))
                            .map(String::trim)
                            .doOnNext(ip -> {
                                platformConfig.setServerEgressIp(ip);
                                log.info("SERVER_IP_RESOLVED source=ipify ip={}", ip);
                            })
                            .onErrorResume(e2 -> {
                                log.warn("SERVER_IP_RESOLVE_FAILED imds={} ipify={} — X-ClientPublicIP will be empty",
                                        e.getMessage(), e2.getMessage());
                                return reactor.core.publisher.Mono.just("");
                            });
                })
                .subscribe();
    }
}
