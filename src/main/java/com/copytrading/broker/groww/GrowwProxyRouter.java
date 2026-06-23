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
 * exit from a unique public IP.
 *
 * Required by brokers that enforce per-IP API whitelisting (Groww, Angel One, etc.)
 *
 * IP Pool mirrors the Stockholm EC2 Squid config (EC2-MULTI-IP-SETUP-GUIDE.md):
 *   Slot 0:  13.53.246.13   → primary ENI, no proxy (direct)
 *   Slot 1:  13.61.58.89    → Squid port 8889
 *   Slot 2:  13.60.103.120  → Squid port 8890
 *   Slot 3:  56.228.67.106  → Squid port 8891
 *   Slot 4:  13.48.122.204  → Squid port 8892
 *   Slot 5:  13.63.33.92    → Squid port 8893
 *   Slot 6:  13.62.184.217  → Squid port 8894
 *   Slot 7:  13.48.142.174  → Squid port 8895
 *   Slot 8:  16.192.7.87    → Squid port 8896
 *   Slot 9:  13.63.15.121   → Squid port 8897
 *
 * Admin assigns ip_slot to each broker_account at link time.
 * Works for ALL brokers — Groww, Upstox, Fyers, AngelOne, Zerodha, Dhan.
 * Per-account explicit proxy_host/proxy_port in DB overrides this slot routing.
 */
@Component
public class GrowwProxyRouter {

    private static final Logger log = LoggerFactory.getLogger(GrowwProxyRouter.class);

    private static final String PRIMARY_IP = "13.53.246.13";

    // Slot → Squid proxy config. Slot 0 = direct (no proxy).
    // Squid listens on 127.0.0.1 and binds tcp_outgoing_address to the secondary private IP,
    // which exits via the corresponding ENI's public IP.
    private static final Map<Integer, ProxyConfig> PROXY_POOL = Map.of(
            1, new ProxyConfig("127.0.0.1", 8889, "13.61.58.89"),
            2, new ProxyConfig("127.0.0.1", 8890, "13.60.103.120"),
            3, new ProxyConfig("127.0.0.1", 8891, "56.228.67.106"),
            4, new ProxyConfig("127.0.0.1", 8892, "13.48.122.204"),
            5, new ProxyConfig("127.0.0.1", 8893, "13.63.33.92"),
            6, new ProxyConfig("127.0.0.1", 8894, "13.62.184.217"),
            7, new ProxyConfig("127.0.0.1", 8895, "13.48.142.174"),
            8, new ProxyConfig("127.0.0.1", 8896, "16.192.7.87"),
            9, new ProxyConfig("127.0.0.1", 8897, "13.63.15.121")
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
