package com.copytrading.engine;

import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.trade.Trade;
import com.copytrading.trade.TradeRepository;
import com.copytrading.ws.TradeUpdatesHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

/**
 * Receives instant order updates from brokers that support postback/webhook.
 * 
 * Zerodha Postback: POST /api/v1/brokers/postback/zerodha
 *   - Set this URL in Zerodha developer console as "Postback URL"
 *   - Zerodha POSTs JSON when order status changes (COMPLETE, CANCEL, REJECTED)
 *   
 * This gives ~100ms detection vs 1-3s polling.
 */
@RestController
@RequestMapping("/api/v1/brokers/postback")
public class BrokerPostbackController {

    private static final Logger log = LoggerFactory.getLogger(BrokerPostbackController.class);

    private final CopyEngineService copyEngine;
    private final BrokerAccountRepository brokerRepo;
    private final TradeRepository tradeRepo;
    private final TradeUpdatesHub hub;

    public BrokerPostbackController(CopyEngineService copyEngine, BrokerAccountRepository brokerRepo,
                                     TradeRepository tradeRepo, TradeUpdatesHub hub) {
        this.copyEngine = copyEngine;
        this.brokerRepo = brokerRepo;
        this.tradeRepo = tradeRepo;
        this.hub = hub;
    }

    /**
     * Zerodha Postback — receives order updates instantly.
     * Zerodha sends: { order_id, status, tradingsymbol, transaction_type, quantity, ... }
     */
    @PostMapping(value = "/zerodha", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> zerodhaPostback(@RequestBody Map<String, Object> payload) {
        log.info("ZERODHA_POSTBACK received: {}", payload);

        String status = String.valueOf(payload.getOrDefault("status", ""));
        if (!"COMPLETE".equalsIgnoreCase(status)) {
            return Mono.just(Map.of("message", "Ignored non-complete order: " + status));
        }

        String symbol = String.valueOf(payload.getOrDefault("tradingsymbol", ""));
        String side = String.valueOf(payload.getOrDefault("transaction_type", ""));
        int qty = payload.containsKey("quantity") ? ((Number) payload.get("quantity")).intValue() : 0;
        String product = String.valueOf(payload.getOrDefault("product", "MIS"));
        String orderId = String.valueOf(payload.getOrDefault("order_id", ""));
        String userId = String.valueOf(payload.getOrDefault("user_id", ""));

        if (symbol.isBlank() || side.isBlank() || qty == 0) {
            return Mono.just(Map.of("message", "Missing required fields"));
        }

        // Find the master who owns this Zerodha account
        return brokerRepo.findByBrokerId("ZERODHA")
                .filter(a -> a.getClientId() != null && a.getClientId().equals(userId))
                .next()
                .flatMap(account -> {
                    UUID masterId = account.getUserId();
                    log.info("ZERODHA_POSTBACK_MATCHED master={} symbol={} side={} qty={}", masterId, symbol, side, qty);

                    // Save master trade
                    Trade t = new Trade();
                    t.setUserId(masterId);
                    t.setBrokerAccountId(account.getId());
                    t.setBrokerOrderId(orderId);
                    t.setInstrument(symbol);
                    t.setExchange("NSE");
                    t.setTransactionType(side);
                    t.setQuantity(qty);
                    t.setProduct(product);
                    t.setStatus("EXECUTED");
                    t.setOrderType("MARKET");
                    t.setSegment("EQUITY");
                    t.setPlacedAt(Instant.now());
                    t.setExecutedAt(Instant.now());
                    tradeRepo.save(t).subscribe();

                    hub.publish("{\"event\":\"TRADE_DETECTED\",\"source\":\"POSTBACK\",\"broker\":\"ZERODHA\",\"instrument\":\"" + symbol + "\",\"side\":\"" + side + "\",\"qty\":" + qty + ",\"latency\":\"<100ms\"}");

                    // Copy to children
                    CopyTradeRequest req = new CopyTradeRequest();
                    req.setSymbol(symbol);
                    req.setQty(qty);
                    req.setSide(side);
                    req.setProduct(product);
                    req.setOrderType("MARKET");
                    req.setPrice(0);

                    return copyEngine.copyTrade(masterId, req)
                            .thenReturn(Map.of("message", "Trade detected and copied via postback"));
                })
                .defaultIfEmpty(Map.of("message", "No matching master account found for user_id: " + userId));
    }
}
