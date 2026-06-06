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
import com.copytrading.security.BrokerCredentials;
import com.copytrading.trade.Trade;
import com.copytrading.trade.TradeRepository;
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
    private final BrokerCredentials credentials;
    private final TradeRepository tradeRepo;

    public PositionsService(BrokerAccountRepository brokerRepo,
                            MasterActiveAccountRepository activeAccountRepo,
                            GrowwApiClient growwClient,
                            ZerodhaApiClient zerodhaClient,
                            FyersApiClient fyersClient,
                            UpstoxApiClient upstoxClient,
                            DhanApiClient dhanClient,
                            AngelOneApiClient angelOneClient,
                            PlatformBrokerConfig platformConfig,
                            BrokerCredentials credentials,
                            TradeRepository tradeRepo) {
        this.brokerRepo = brokerRepo;
        this.activeAccountRepo = activeAccountRepo;
        this.growwClient = growwClient;
        this.zerodhaClient = zerodhaClient;
        this.fyersClient = fyersClient;
        this.upstoxClient = upstoxClient;
        this.dhanClient = dhanClient;
        this.angelOneClient = angelOneClient;
        this.platformConfig = platformConfig;
        this.credentials = credentials;
        this.tradeRepo = tradeRepo;
    }

    /**
     * Get positions for a master user using their active broker account.
     * Falls back to trades table if broker session unavailable.
     */
    public Mono<Map<String, Object>> getMasterPositions(UUID masterId) {
        return activeAccountRepo.findById(masterId)
                .flatMap(active -> brokerRepo.findById(active.getBrokerAccountId()))
                .filter(a -> a.getAccessToken() != null)
                .switchIfEmpty(
                    brokerRepo.findByUserId(masterId)
                            .filter(a -> a.getAccessToken() != null)
                            .next()
                )
                .flatMap(this::fetchAndNormalizePositions)
                .switchIfEmpty(Mono.defer(() -> fallbackFromTrades(masterId)))
                .defaultIfEmpty(noAccountResponse());
    }

    /**
     * Get positions for a child user using their first active broker account.
     * Falls back to trades table if broker session unavailable.
     */
    public Mono<Map<String, Object>> getChildPositions(UUID childId) {
        return brokerRepo.findByUserId(childId)
                .filter(a -> a.getAccessToken() != null)
                .next()
                .flatMap(this::fetchAndNormalizePositions)
                .switchIfEmpty(Mono.defer(() -> fallbackFromTrades(childId)))
                .defaultIfEmpty(noAccountResponse());
    }

    /**
     * Get positions for a specific broker account (used by copied-trades enrichment).
     */
    public Mono<List<PositionDto>> getPositionsForAccount(UUID brokerAccountId) {
        return brokerRepo.findById(brokerAccountId)
                .filter(a -> a.getAccessToken() != null)
                .flatMap(this::fetchRawAndNormalize)
                .defaultIfEmpty(List.of());
    }

    /**
     * Get positions for a user (by userId), returns normalized list.
     */
    public Mono<List<PositionDto>> getPositionsForUser(UUID userId) {
        return brokerRepo.findByUserId(userId)
                .filter(a -> a.getAccessToken() != null)
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
                    return Mono.empty(); // fall through to DB fallback
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<List<PositionDto>> fetchRawAndNormalize(BrokerAccount account) {
        String broker = account.getBrokerId();
        String token = credentials.accessToken(account);
        if (token == null || token.isBlank()) {
            return Mono.error(new IllegalStateException("Broker session token missing"));
        }

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
                Object positions = raw.get("positions");
                if (positions instanceof List<?> list) {
                    return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
                }
                Object rawData = raw.get("raw");
                if (rawData instanceof List<?> list) {
                    return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
                }
                if (raw.containsKey("dhanClientId")) {
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
        double brokerPnl = getDouble(p, "pnl", getDouble(p, "unrealisedPnl", getDouble(p, "realizedPnl", Double.NaN)));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "product", "CNC");
        if (!Double.isNaN(brokerPnl)) {
            return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product, brokerPnl);
        }
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapZerodhaPosition(Map<String, Object> p) {
        String symbol = getString(p, "tradingsymbol", "UNKNOWN");
        int qty = getInt(p, "quantity", 0);
        double avgPrice = getDouble(p, "average_price", 0);
        double ltp = getDouble(p, "last_price", 0);
        double brokerPnl = getDouble(p, "pnl", getDouble(p, "unrealised", Double.NaN));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "product", "CNC");
        if (!Double.isNaN(brokerPnl)) {
            return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product, brokerPnl);
        }
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapFyersPosition(Map<String, Object> p) {
        String symbol = getString(p, "symbol", "UNKNOWN");
        if (symbol.contains(":")) symbol = symbol.substring(symbol.indexOf(":") + 1);
        int qty = getInt(p, "netQty", getInt(p, "qty", 0));
        double avgPrice = getDouble(p, "avgPrice", getDouble(p, "buyAvg", 0));
        double ltp = getDouble(p, "ltp", 0);
        double brokerPnl = getDouble(p, "pl", getDouble(p, "unrealized_profit", Double.NaN));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "productType", "CNC");
        if (!Double.isNaN(brokerPnl)) {
            return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product, brokerPnl);
        }
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapUpstoxPosition(Map<String, Object> p) {
        String symbol = getString(p, "trading_symbol", getString(p, "tradingsymbol",
                getString(p, "symbol", "UNKNOWN")));
        // Upstox v2: quantity = net qty (positive=long, negative=short)
        // Also try buy_quantity - sell_quantity for net calculation
        int qty = getInt(p, "quantity", 0);
        if (qty == 0) {
            int buyQty = getInt(p, "buy_quantity", getInt(p, "day_buy_quantity", 0));
            int sellQty = getInt(p, "sell_quantity", getInt(p, "day_sell_quantity", 0));
            qty = buyQty - sellQty;
        }
        // Upstox: average_price may be 0; fall back to buy_price or sell_price
        double avgPrice = getDouble(p, "average_price", 0);
        if (avgPrice == 0) {
            avgPrice = getDouble(p, "buy_price", getDouble(p, "buy_avg",
                    getDouble(p, "day_buy_price", 0)));
        }
        if (avgPrice == 0) {
            avgPrice = getDouble(p, "sell_price", getDouble(p, "sell_avg",
                    getDouble(p, "day_sell_price", 0)));
        }
        double ltp = getDouble(p, "last_price", getDouble(p, "ltp",
                getDouble(p, "close_price", 0)));
        double brokerPnl = getDouble(p, "pnl", getDouble(p, "unrealised",
                getDouble(p, "realised", Double.NaN)));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        // Upstox product: I=intraday, D=delivery, CO=cover, OCO=bracket
        String product = getString(p, "product", "D");
        if (!Double.isNaN(brokerPnl)) {
            return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product, brokerPnl);
        }
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapDhanPosition(Map<String, Object> p) {
        String symbol = getString(p, "tradingSymbol", getString(p, "trading_symbol",
                getString(p, "symbol", getString(p, "securityId", "UNKNOWN"))));
        int qty = getInt(p, "netQty", getInt(p, "net_qty", getInt(p, "quantity", getInt(p, "netQuantity", 0))));
        double avgPrice = getDouble(p, "averagePrice", getDouble(p, "costPrice", 0));
        double ltp = getDouble(p, "ltp", getDouble(p, "currentPrice", 0));
        double brokerPnl = getDouble(p, "unrealizedProfit", getDouble(p, "realizedProfit",
                getDouble(p, "dayPnl", Double.NaN)));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchangeSegment", "NSE_EQ");
        String product = getString(p, "productType", "CNC");
        if (!Double.isNaN(brokerPnl)) {
            return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product, brokerPnl);
        }
        return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product);
    }

    private PositionDto mapAngelOnePosition(Map<String, Object> p) {
        String symbol = getString(p, "tradingsymbol", getString(p, "symbolname", "UNKNOWN"));
        int qty = getInt(p, "netqty", getInt(p, "quantity", 0));
        double avgPrice = getDouble(p, "averageprice", getDouble(p, "buyavgprice", 0));
        double ltp = getDouble(p, "ltp", 0);
        double brokerPnl = getDouble(p, "pnl", getDouble(p, "unrealised", Double.NaN));
        String side = qty >= 0 ? "BUY" : "SELL";
        String exchange = getString(p, "exchange", "NSE");
        String product = getString(p, "producttype", "DELIVERY");
        if (!Double.isNaN(brokerPnl)) {
            return new PositionDto(symbol, Math.abs(qty), avgPrice, ltp, side, exchange, product, brokerPnl);
        }
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

    /**
     * Fallback: build positions-like data from the trades table when broker is offline.
     * Shows executed trades grouped by instrument with realized P&L from DB.
     */
    private Mono<Map<String, Object>> fallbackFromTrades(UUID userId) {
        return tradeRepo.findByUserIdOrderByPlacedAtDesc(userId)
                .filter(t -> "EXECUTED".equalsIgnoreCase(t.getStatus()) || "COMPLETE".equalsIgnoreCase(t.getStatus()))
                .collectList()
                .map(trades -> {
                    if (trades.isEmpty()) return noAccountResponse();
                    // Group by instrument, net out qty (BUY adds, SELL subtracts)
                    Map<String, int[]> netQty = new LinkedHashMap<>(); // instrument -> [qty, totalBuyValue, totalSellValue]
                    Map<String, double[]> prices = new LinkedHashMap<>(); // instrument -> [avgBuyPrice, lastPrice, realized]
                    for (Trade t : trades) {
                        String inst = t.getInstrument() != null ? t.getInstrument() : "UNKNOWN";
                        netQty.putIfAbsent(inst, new int[]{0});
                        prices.putIfAbsent(inst, new double[]{0, 0, 0});
                        int qty = t.getQuantity();
                        if ("BUY".equalsIgnoreCase(t.getTransactionType())) {
                            netQty.get(inst)[0] += qty;
                            if (t.getPrice() > 0) prices.get(inst)[0] = t.getPrice(); // last known buy price
                        } else {
                            netQty.get(inst)[0] -= qty;
                            if (t.getPrice() > 0) prices.get(inst)[1] = t.getPrice(); // last known sell price
                        }
                    }
                    List<PositionDto> positions = new ArrayList<>();
                    double totalPnl = 0;
                    for (Map.Entry<String, int[]> e : netQty.entrySet()) {
                        int qty = e.getValue()[0];
                        if (qty == 0) continue; // closed position
                        double[] p = prices.get(e.getKey());
                        double avgPrice = p[0] > 0 ? p[0] : p[1];
                        double ltp = p[1] > 0 ? p[1] : avgPrice; // use sell price as last known or same as buy
                        String side = qty > 0 ? "BUY" : "SELL";
                        PositionDto dto = new PositionDto(e.getKey(), Math.abs(qty), avgPrice, ltp, side, "NSE", "MIS");
                        totalPnl += dto.getPnl();
                        positions.add(dto);
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("positions", positions);
                    result.put("totalPnl", totalPnl);
                    result.put("count", positions.size());
                    result.put("source", "DB_FALLBACK");
                    result.put("note", "Live broker data unavailable. Showing positions from trade history.");
                    return result;
                });
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
