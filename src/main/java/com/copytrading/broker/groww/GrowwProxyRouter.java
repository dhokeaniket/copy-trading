package com.copytrading.broker.groww;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes broker API calls through per-user proxies so each user's requests
 * exit from a unique public IP (required by Groww's per-user IP whitelisting,
 * and useful for Angel One static IP requirement).
 *
 * IP Pool:
 *   - Slot 0: 13.53.246.13  → default (no proxy, primary ENI)
 *   - Slot 1+: proxy through secondary ENI (configured in Squid)
 *
 * Admin assigns an ip_slot to each broker_account.
 * Slot 0 = default IP (no proxy), Slot 1+ = proxy through secondary ENI.
 *
 * NOTE: Most accounts use the per-account proxy_host/proxy_port fields in DB
 * (managed via the frontend API). This slot-based routing is only a fallback
 * for legacy Groww accounts that use ip_slot without explicit proxy fields.
 */
@Component
public class GrowwProxyRouter {

    private static final Logger log = LoggerFactory.getLogger(GrowwProxyRouter.class);

    private static final String PRIMARY_IP = "13.53.246.13";

    // Map of slot → proxy address. Slot 0 = no proxy (direct).
    // These are only used for legacy ip_slot-based routing.
    // New accounts should use proxy_host/proxy_port fields in DB instead.
    private static final Map<Integer, ProxyConfig> PROXY_POOL = Map.of(
            1, new ProxyConfig("127.0.0.1", 8889, "13.61.58.89")
    );

    // Cache HttpClients per slot to avoid recreating them
    private final ConcurrentHashMap<Integer, HttpClient> clientCache = new ConcurrentHashMap<>();

    /**
     * Get an HttpClient that routes through the proxy for the given slot.
     * Slot 0 returns null (use default WebClient, goes out via primary IP).
     * Works for ANY broker — not just Groww.
     */
    public HttpClient getProxiedClient(int slot) {
        if (slot <= 0) return null;

        ProxyConfig config = PROXY_POOL.get(slot);
        if (config == null) {
            log.warn("PROXY_SLOT_NOT_FOUND slot={} — falling back to default IP", slot);
            return null;
        }

        return clientCache.computeIfAbsent(slot, s ->
                HttpClient.create()
                        .proxy(proxy -> proxy
                                .type(ProxyProvider.Proxy.HTTP)
                                .host(config.host())
                                .port(config.port()))
        );
    }

    /**
     * Build a WebClient that routes through the proxy for the given slot.
     * If slot is 0, returns null (caller should use their default client).
     */
    public WebClient getProxiedWebClient(int slot, String baseUrl) {
        HttpClient httpClient = getProxiedClient(slot);
        if (httpClient == null) return null;

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Get the public IP for a given slot (for display to the user).
     */
    public String getPublicIp(int slot) {
        if (slot <= 0) return PRIMARY_IP;
        ProxyConfig config = PROXY_POOL.get(slot);
        return config != null ? config.publicIp() : PRIMARY_IP;
    }

    /**
     * Get all available IPs (for the frontend/admin to show users which IP to whitelist).
     */
    public Map<Integer, String> getAvailableIps() {
        Map<Integer, String> ips = new LinkedHashMap<>();
        ips.put(0, PRIMARY_IP);
        PROXY_POOL.forEach((slot, config) -> ips.put(slot, config.publicIp()));
        return ips;
    }

    private record ProxyConfig(String host, int port, String publicIp) {}
}
