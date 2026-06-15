package com.copytrading.broker.proxy;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.security.BrokerCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic per-user proxy HTTP client factory.
 *
 * Creates and caches proxied WebClient instances keyed by broker account ID.
 * If a broker account has proxy_host/proxy_port configured, all API calls
 * for that account will route through the user's proxy.
 *
 * If no proxy is configured, returns null (caller should use direct connection).
 *
 * Supports HTTP/HTTPS proxies with optional username/password authentication.
 */
@Component
public class ProxyHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ProxyHttpClient.class);

    private final BrokerCredentials credentials;

    // Cache HttpClients per account ID to avoid recreating connections
    private final ConcurrentHashMap<UUID, HttpClient> clientCache = new ConcurrentHashMap<>();

    public ProxyHttpClient(BrokerCredentials credentials) {
        this.credentials = credentials;
    }

    /**
     * Get a proxied HttpClient for the given broker account.
     * Returns null if no proxy is configured on this account.
     */
    public HttpClient getHttpClient(BrokerAccount account) {
        if (!account.hasProxy()) {
            return null;
        }
        return clientCache.computeIfAbsent(account.getId(), id -> buildHttpClient(account));
    }

    /**
     * Get a proxied WebClient for the given broker account and base URL.
     * Returns null if no proxy is configured (caller should use default client).
     */
    public WebClient getWebClient(BrokerAccount account, String baseUrl) {
        HttpClient httpClient = getHttpClient(account);
        if (httpClient == null) {
            return null;
        }
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Evict the cached client for a broker account (e.g., when proxy settings change).
     */
    public void evict(UUID accountId) {
        clientCache.remove(accountId);
        log.info("PROXY_CACHE_EVICT accountId={}", accountId);
    }

    /**
     * Evict all cached clients.
     */
    public void evictAll() {
        clientCache.clear();
        log.info("PROXY_CACHE_EVICT_ALL");
    }

    private HttpClient buildHttpClient(BrokerAccount account) {
        String host = account.getProxyHost();
        int port = account.getProxyPort();
        String user = account.getProxyUser();
        // Decrypt password (stored encrypted in DB)
        String pass = credentials.proxyPass(account);

        log.info("PROXY_CLIENT_BUILD accountId={} broker={} proxyHost={} proxyPort={} hasAuth={}",
                account.getId(), account.getBrokerId(), host, port,
                (user != null && !user.isBlank()));

        return HttpClient.create()
                .proxy(proxy -> {
                    ProxyProvider.Builder builder = proxy
                            .type(ProxyProvider.Proxy.HTTP)
                            .host(host)
                            .port(port);
                    if (user != null && !user.isBlank() && pass != null && !pass.isBlank()) {
                        builder.username(user).password(p -> pass);
                    }
                });
    }
}
