package com.copytrading.engine;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.PlatformBrokerConfig;
import com.copytrading.broker.angelone.AngelOneApiClient;
import com.copytrading.broker.dhan.DhanApiClient;
import com.copytrading.broker.fyers.FyersApiClient;
import com.copytrading.broker.upstox.UpstoxApiClient;
import com.copytrading.broker.zerodha.ZerodhaApiClient;
import com.copytrading.logs.CopyLog;
import com.copytrading.logs.CopyLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class AsyncEntryPriceWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncEntryPriceWorker.class);

    private final CopyLogRepository copyLogs;
    private final BrokerAccountRepository brokerRepo;
    private final ZerodhaApiClient zerodhaClient;
    private final UpstoxApiClient upstoxClient;
    private final DhanApiClient dhanClient;
    private final FyersApiClient fyersClient;
    private final AngelOneApiClient angelOneClient;
    private final PlatformBrokerConfig platformConfig;

    public AsyncEntryPriceWorker(CopyLogRepository copyLogs,
                                 BrokerAccountRepository brokerRepo,
                                 ZerodhaApiClient zerodhaClient,
                                 UpstoxApiClient upstoxClient,
                                 DhanApiClient dhanClient,
                                 FyersApiClient fyersClient,
                                 AngelOneApiClient angelOneClient,
                                 PlatformBrokerConfig platformConfig) {
        this.copyLogs = copyLogs;
        this.brokerRepo = brokerRepo;
        this.zerodhaClient = zerodhaClient;
        this.upstoxClient = upstoxClient;
        this.dhanClient = dhanClient;
        this.fyersClient = fyersClient;
        this.angelOneClient = angelOneClient;
        this.platformConfig = platformConfig;
    }

    @Scheduled(fixedDelay = 5000)
    public void fetchPendingEntryPrices() {
        copyLogs.findPendingForEntryPrice("PLACED")
                .flatMap(this::processPendingLog)
                .subscribe(
                        updated -> log.debug("Updated entry price for copyLog id={}", updated.getId()),
                        error -> log.error("Error fetching entry prices", error)
                );
    }

    private Mono<CopyLog> processPendingLog(CopyLog copyLog) {
        return brokerRepo.findByUserId(copyLog.getChildId())
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .next()
                .flatMap(account -> fetchOrderDetails(account, copyLog.getChildBrokerOrderId())
                        .flatMap(details -> updateCopyLog(copyLog, details)))
                .onErrorResume(e -> {
                    log.error("Failed to fetch order details for childOrderId={}: {}", copyLog.getChildBrokerOrderId(), e.getMessage());
                    return Mono.empty(); // Skip and try again later
                });
    }

    private Mono<Map> fetchOrderDetails(BrokerAccount account, String orderId) {
        String broker = account.getBrokerId().toUpperCase();
        String token = account.getAccessToken();

        switch (broker) {
            case "ZERODHA": {
                String apiKey = (account.getApiKey() != null && !account.getApiKey().isBlank())
                        ? account.getApiKey() : platformConfig.getZerodha().getApiKey();
                return zerodhaClient.getOrderHistory(apiKey, token, orderId);
            }
            case "UPSTOX":
                return upstoxClient.getOrderDetails(token, orderId);
            case "DHAN":
                return dhanClient.getOrderDetails(token, orderId);
            case "FYERS": {
                String fyersAuth = account.getApiKey() + ":" + token;
                return fyersClient.getOrderDetails(fyersAuth, orderId);
            }
            case "ANGELONE": {
                String apiKey = account.getApiKey();
                return angelOneClient.getOrderDetails(apiKey, token, orderId);
            }
            default:
                return Mono.error(new RuntimeException("Unsupported broker for async fetch: " + broker));
        }
    }

    private Mono<CopyLog> updateCopyLog(CopyLog copyLog, Map details) {
        try {
            String status = extractStatus(details, copyLog);
            Double averagePrice = extractAveragePrice(details, copyLog);
            Integer filledQty = extractFilledQty(details, copyLog);

            if (status != null && !status.isEmpty()) {
                copyLog.setChildStatus(status);
            }

            if (averagePrice != null && averagePrice > 0) {
                copyLog.setEntryPrice(averagePrice);
                copyLog.setFilledQty(filledQty != null ? filledQty : copyLog.getChildQty());
                copyLog.setInvestedValue(copyLog.getEntryPrice() * copyLog.getFilledQty());
            }

            if ("REJECTED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                 // Even if averagePrice is null (e.g. rejected), we still want to save the final status and stop polling
                 if (copyLog.getEntryPrice() == null) {
                     copyLog.setEntryPrice(0.0); // Stop polling by making it non-null
                 }
                 return copyLogs.save(copyLog);
            }

            // If it's still OPEN, don't save yet, we want to keep polling
            return Mono.just(copyLog);
        } catch (Exception e) {
            log.error("Failed to parse order details for orderId={}: {}", copyLog.getChildBrokerOrderId(), e.getMessage());
            return Mono.error(e);
        }
    }

    // Basic extraction helpers - in a real app, use BrokerStatusNormalizer
    private String extractStatus(Map details, CopyLog log) {
        if (details.containsKey("data")) {
            Object data = details.get("data");
            if (data instanceof Map) {
                return (String) ((Map) data).getOrDefault("status", "PLACED");
            } else if (data instanceof java.util.List) {
                java.util.List list = (java.util.List) data;
                if (!list.isEmpty() && list.get(list.size() - 1) instanceof Map) {
                    return (String) ((Map) list.get(list.size() - 1)).getOrDefault("status", "PLACED");
                }
            }
        } else if (details.containsKey("status")) {
             return (String) details.getOrDefault("status", "PLACED");
        }
        return "PLACED";
    }

    private Double extractAveragePrice(Map details, CopyLog log) {
        try {
            if (details.containsKey("data")) {
                Object data = details.get("data");
                if (data instanceof Map) {
                    Map map = (Map) data;
                    if (map.containsKey("average_price")) return Double.parseDouble(map.get("average_price").toString());
                    if (map.containsKey("tradedPrice")) return Double.parseDouble(map.get("tradedPrice").toString()); // Dhan/Fyers
                } else if (data instanceof java.util.List) {
                    java.util.List list = (java.util.List) data;
                    if (!list.isEmpty() && list.get(list.size() - 1) instanceof Map) {
                        Map map = (Map) list.get(list.size() - 1);
                        if (map.containsKey("average_price")) return Double.parseDouble(map.get("average_price").toString());
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private Integer extractFilledQty(Map details, CopyLog log) {
        try {
            if (details.containsKey("data")) {
                Object data = details.get("data");
                if (data instanceof Map) {
                    Map map = (Map) data;
                    if (map.containsKey("filled_quantity")) return Integer.parseInt(map.get("filled_quantity").toString());
                    if (map.containsKey("tradedQty")) return Integer.parseInt(map.get("tradedQty").toString());
                } else if (data instanceof java.util.List) {
                    java.util.List list = (java.util.List) data;
                    if (!list.isEmpty() && list.get(list.size() - 1) instanceof Map) {
                        Map map = (Map) list.get(list.size() - 1);
                        if (map.containsKey("filled_quantity")) return Integer.parseInt(map.get("filled_quantity").toString());
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }
}
