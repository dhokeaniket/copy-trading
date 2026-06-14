package com.copytrading.positions;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.PlatformBrokerConfig;
import com.copytrading.broker.dhan.DhanApiClient;
import com.copytrading.broker.fyers.FyersApiClient;
import com.copytrading.broker.groww.GrowwApiClient;
import com.copytrading.broker.upstox.UpstoxApiClient;
import com.copytrading.broker.zerodha.ZerodhaApiClient;
import com.copytrading.broker.angelone.AngelOneApiClient;
import com.copytrading.master.MasterActiveAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that fetches live positions from a user's active broker account
 * and normalizes them into a consistent PositionDto format with real P&L.
 */
@Service
public class PositionsService {

    private static final Logger log = LoggerFactory.getLogger(PositionsService.class);

    private final BrokerAccountRepository brokerRepo;
    private final MasterActiveAccountRepository activeAccountRepo;
    private final GrowwApiClient growwClient;
    private final ZerodhaApiClient zerodhaClient;
    private final FyersApiClient fyersClient;
    private final UpstoxApiClient upstoxClient;
    private final DhanApiClient dhanClient;
    private final AngelOneApiClient angelOneClient;
    private final PlatformBrokerConfig platformConfig;

    public PositionsService(BrokerAccountRepository brokerRepo,
                            MasterActiveAccountRepository activeAccountRepo,
                            GrowwApiClient growwClient,
                            ZerodhaApiClient zerodhaClient,
                            FyersApiClient fyersClient,
                            UpstoxApiClient upstoxClient,
                            DhanApiClient dhanClient,
                            AngelOneApiClient angelOneClient,
                            PlatformBrokerConfig platformConfig) {
        this.brokerRepo = brokerRepo;
        this.activeAccountRepo = activeAccountRepo;
        this.growwClient = growwClient;
        this.zerodhaClient = zerodhaClient;
        this.fyersClient = fyersClient;
        this.upstoxClient = upstoxClient;
        this.dhanClient = dhanClient;
        this.angelOneClient = angelOneClient;
        this.platformConfig = platformConfig;
    }

    /**
     * Get positions for a master user using their active broker account.
     */
    public Mono<Map<String, Object>> getMasterPositions(UUID masterId) {
        return activeAccountRepo.findById(masterId)
                .flatMap(active -> brokerRepo.findById(active.getBrokerAccountId()))
                .switchIfEmpty(
                    // Fallback: find first active session broker account for this user
                    brokerRepo.findByUserId(masterId)
                            .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                            .next()
                )
                .flatMap(this::fetchAndNormalizePositions)
                .defaultIfEmpty(noAccountResponse());
    }

    /**
     * Get positions for a child user using their first active broker account.
     */
    public Mono<Map<String, Object>> getChildPositions(UUID childId) {
        return brokerRepo.findByUserId(childId)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .next()
                .flatMap(this::fetchAndNormalizePositions)
                .defaultIfEmpty(noAccountResponse());
    }

    /**
     * Get positions for a specific broker account (used by copied-trades enrichment).
     */
    public Mono<List<PositionDto>> getPositionsForAccount(UUID brokerAccountId) {
        return brokerRepo.findById(brokerAccountId)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .flatMap(this::fetchRawAndNormalize)
                .defaultIfEmpty(List.of());
    }

    /**
     * Get positions for a user (by userId), returns normalized list.
     */
    public Mono<List<PositionDto>> getPositionsForUser(UUID userId) {
        return brokerRepo.findByUserId(userId)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .next()
                .flatMap(this::fetchRawAndNormalize)
                .defaultIfEmpty(List.of());
    }

    private Mono<Map<String, Object>> fetchAndNormalizePositions(BrokerAccount account) {
        return fetchRawAndNormalize(account)
                .map(positions -> {
                    double totalPnl = positions.stream().mapToDouble(PositionDto::getPnl).sum();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("positions", positions);
                    result.put("totalPnl", totalPnl);
                    result.put("count", positions.size());
                    result.put("brokerAccountId", account.getId().toString());
                    result.put("brokerId", account.getBrokerId());
                    return result;
                })
                .onErrorResume(e -> {
                    log.error("POSITIONS_FETCH_ERROR broker={} error={}", account.getBrokerId(), e.getMessage());
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("positions", List.of());
                    fallback.put("totalPnl", 0);
                    fallback.put("count", 0);
                    fallback.put("error", "Failed to fetch positions: " + e.getMessage());
                    fallback.put("errorCode", "SESSION_EXPIRED");
                    fallback.put("action", "RE_LOGIN");
                    return Mono.just(fallback);
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<List<PositionDto>> fetchRawAndNormalize(BrokerAccount account) {
        String broker = account.getBrokerId();
        String token = account.getAccessToken();

        Mono<Map> rawMono;
        switch (broker) {
            case "GROWW":
                rawMono = growwClient.getPositions(token, null);
                break;
            case "ZERODHA":
                rawMono = zerodhaClient.getPositions(platformConfig.getZerodha().getApiKey(), token);
                break;
            case "FYERS":
                String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + token;
                rawMono = fyersClient.getPositions(fyersAuth);
                break;
            case "UPSTOX":
                rawMono = upstoxClient.getPositions(token);
                break;
            case "DHAN":
                rawMono = dhanClient.getPositions(token);
                break;
            case "ANGELONE":
                rawMono = angelOneClient.getPositions(platformConfig.getAngelone().getApiKey(), token);
                break;
            default:
                return Mono.just(List.of());
        }

        return rawMono.map(raw -> normalizePositions(broker, raw));
    }

    @SuppressWarnings("unchecked")
    private List<PositionDto> normalizePositions(String broker, Map raw) {
        try {
            List<Map<String, Object>> positions = extractPositionsList(broker, raw);
            if (positions == null || positions.isEmpty()) return List.of();

            return positions.stream()
                    .map(p -> mapToPositionDto(broker, p))
                    .filter(Objects::nonNull)
                    .filter(p -> p.getQty() != 0) // Only open positions
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to normalize {} positions: {}", broker, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractPositionsList(String broker, Map raw) {
        switch (broker) {
            case "GROWW": {
                Object data = raw.get("data");
                if (data instanceof List) {
                    return (List<Map<String, Object>>) data;
                } else if (data instanceof Map m && m.containsKey("positions")) {
                    Object p = m.get("positions");
                    if (p instanceof List) return (List<Map<String, Object>>) p;
                }
                Object payload = raw.get("payload");
                if (payload instanceof List) return (List<Map<String, Object>>) payload;
                return List.of();
            }
            case "ZERODHA": {
                Object data = raw.get("data");
                if (data instanceof Map) {
                    // Zerodha returns {data: {net: [...], day: [...]}}
                    Object net = ((Map) data).get("net");
                    if (net instanceof List) return (List<Map<String, Object>>) net;
                }
                if (data instanceof List) return (List<Map<String, Object>>) data;
                return List.of();
            }
            case "FYERS": {
                Object netPositions = raw.get("netPositions");
                if (netPositions instanceof List) return (List<Map<String, Object>>) netPositions;
                return List.of();
            }
            case "UPSTOX": {
                Object data = raw.get("data");
                if (data instanceof List) return (List<Map<String, Object>>) data;
                return List.of();
            }
            case "DHAN": {
                // Dhan may return raw JSON string or list
                Object rawData = raw.get("raw");
                if (rawData instanceof List) return (List<Map<String, Object>>) rawData;
                // If it's the map itself with positions
                if (raw.containsKey("dhanClientId")) {
                    // Single position object, wrap
                    return List.of((Map<String, Object>) raw);
                }
                return List.of();
            }
            case "ANGELONE": {
                Object data = raw.get("data");
                if (data instanceof List) return (List<Map<String, Object>>) data;
                return List.of();
            }
            default:
                return List.of();
        }
    }

    private PositionDto mapToPositionDto(String broker, Map<String, Object> p) {
        try {
            switch (broker) {
                case "GROWW":
                    return mapGrowwPosition(p);
                case "ZERODHA":
                    return mapZerodhaPosition(p);
                case "FYERS":
                    return mapFyersPosition(p);
                case "UPSTOX":
                    return mapUpstoxPosition(p);
                case "DHAN":
                    return mapDhanPosition(p);
                case "ANGELONE":
                    return mapAngelOnePosition(p);
                default:
                    return null;
            }
        } catch (Exception e) {
            log.debug("Failed to map {} position: {}", broker, e.getMessage());
            return null;
        }
    }

    private PositionDto mapGrowwPosition(Map<String, Object> p) {
        String symbol = getString(p, "tradingSymbol", getString(p, "symbol", "UNKNOWN"));
        int qty = getInt(p, "netQty", getInt(p, "quantity", 0));
        double avgPrice = getDouble(p, "averagePrice", getDouble(p, "buyAvgPrice", 0));
        double ltp = getDouble(p, "ltp", getDouble(p, "lastPrice", 0));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "product", "CNC");
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapZerodhaPosition(Map<String, Object> p) {
        String symbol = getString(p, "tradingsymbol", "UNKNOWN");
        int qty = getInt(p, "quantity", 0);
        double avgPrice = getDouble(p, "average_price", 0);
        double ltp = getDouble(p, "last_price", 0);
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "product", "CNC");
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapFyersPosition(Map<String, Object> p) {
        String symbol = getString(p, "symbol", "UNKNOWN");
        // Fyers symbol format: NSE:RELIANCE-EQ, extract just the name
        if (symbol.contains(":")) symbol = symbol.substring(symbol.indexOf(":") + 1);
        int qty = getInt(p, "netQty", getInt(p, "qty", 0));
        double avgPrice = getDouble(p, "avgPrice", getDouble(p, "buyAvg", 0));
        double ltp = getDouble(p, "ltp", 0);
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "productType", "CNC");
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapUpstoxPosition(Map<String, Object> p) {
        String symbol = getString(p, "trading_symbol", getString(p, "tradingsymbol", "UNKNOWN"));
        int qty = getInt(p, "quantity", getInt(p, "net_quantity", 0));
        double avgPrice = getDouble(p, "average_price", 0);
        double ltp = getDouble(p, "last_price", getDouble(p, "ltp", 0));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "product", "D");
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapDhanPosition(Map<String, Object> p) {
        String symbol = getString(p, "tradingSymbol", getString(p, "securityId", "UNKNOWN"));
        int qty = getInt(p, "netQty", getInt(p, "quantity", 0));
        double avgPrice = getDouble(p, "averagePrice", getDouble(p, "costPrice", 0));
        double ltp = getDouble(p, "ltp", getDouble(p, "currentPrice", 0));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchangeSegment", "NSE_EQ");
        String product = getString(p, "productType", "CNC");
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapAngelOnePosition(Map<String, Object> p) {
        String symbol = getString(p, "tradingsymbol", getString(p, "symbolname", "UNKNOWN"));
        int qty = getInt(p, "netqty", getInt(p, "quantity", 0));
        double avgPrice = getDouble(p, "averageprice", getDouble(p, "buyavgprice", 0));
        double ltp = getDouble(p, "ltp", 0);
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "producttype", "DELIVERY");
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    // --- Utility methods ---

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return defaultVal; }
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object v = map.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return defaultVal; }
    }

    private Map<String, Object> noAccountResponse() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("positions", List.of());
        r.put("totalPnl", 0);
        r.put("count", 0);
        r.put("error", "No active broker session found. Please login to your broker.");
        r.put("errorCode", "NO_ACTIVE_SESSION");
        r.put("action", "LOGIN_BROKER");
        return r;
    }
}
